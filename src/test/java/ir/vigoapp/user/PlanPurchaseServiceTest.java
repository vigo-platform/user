package ir.vigoapp.user;

import ir.vigoapp.user.dto.order.CreateOrderResponse;
import ir.vigoapp.user.entity.CoachProfile;
import ir.vigoapp.user.entity.UserAccount;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.domain.plan.subscription.SubscriptionStatus;
import ir.vigoapp.user.dto.Requests;
import ir.vigoapp.user.repository.CoachPlanIncludeRepository;
import ir.vigoapp.user.repository.CoachPlanRepository;
import ir.vigoapp.user.repository.CoachRepository;
import ir.vigoapp.user.repository.PlanIncludeOptionRepository;
import ir.vigoapp.user.repository.PlanSubscriptionRepository;
import ir.vigoapp.user.repository.UserRepository;
import ir.vigoapp.user.service.CoachPlanService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Purchase baseline: no activation without payment, idempotent event handling. */
class PlanPurchaseServiceTest {
    static EmbeddedPostgres postgres;
    static DataSource dataSource;
    static EntityManagerFactory emf;
    EntityManager em;
    TransactionTemplate tx;
    CoachPlanService planService;
    PlanPurchaseService purchases;
    OrderClient orders;

    UUID coachId = UUID.randomUUID();
    UUID athleteId = UUID.randomUUID();
    Long athleteUserId;

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        dataSource = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).load().migrate();
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(UserAccount.class.getPackageName());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
        factory.afterPropertiesSet();
        emf = factory.getObject();
    }

    @AfterAll
    static void stop() throws Exception { if (emf != null) emf.close(); if (postgres != null) postgres.close(); }

    @BeforeEach
    void wire() {
        new JdbcTemplate(dataSource).update(
                "delete from consumed_events; delete from plan_subscriptions; delete from coach_plan_includes; delete from coach_plans; delete from coach_profiles; delete from users");
        em = SharedEntityManagerCreator.createSharedEntityManager(emf);
        tx = new TransactionTemplate(new JpaTransactionManager(emf));
        var factory = new JpaRepositoryFactory(em);
        planService = new CoachPlanService(factory.getRepository(CoachPlanRepository.class),
                factory.getRepository(CoachPlanIncludeRepository.class),
                factory.getRepository(CoachRepository.class),
                factory.getRepository(PlanIncludeOptionRepository.class));
        orders = mock(OrderClient.class);
        purchases = new PlanPurchaseService(planService, factory.getRepository(PlanSubscriptionRepository.class),
                orders, new JdbcTemplate(dataSource), factory.getRepository(UserRepository.class));
        inTx(() -> {
            seedCoach();
            var athlete = new UserAccount();
            athlete.setPublicId(athleteId);
            athlete.setEmail("athlete@example.test");
            athlete.setDisplayName("Athlete");
            athlete.replacePassword("hash");
            em.persist(athlete);
            em.flush();
            athleteUserId = athlete.getId();
            return null;
        });
    }

    private void seedCoach() {
        var u = new UserAccount();
        u.setPublicId(coachId); u.setEmail("coach@example.test"); u.setDisplayName("Coach");
        u.replacePassword("hash"); u.setRole(Role.COACH); u.setEmailVerified(true);
        em.persist(u); em.flush();
        var c = new CoachProfile();
        c.setUser(u); c.setDisplayName("Coach"); c.setBio("Synthetic coach biography");
        c.setSpecialties("[]"); c.setStatus(CoachStatus.APPROVED);
        em.persist(c); em.flush();
    }

    private <T> T inTx(java.util.function.Supplier<T> work) { return tx.execute(s -> work.get()); }

    private Long activePlan() {
        Map<String, Object> created = inTx(() -> planService.create(coachId,
                new Requests.Plan("Monthly", "Plan description", 500_000L, 30,
                        List.of("WORKOUT", "NUTRITION"))));
        return (Long) created.get("plan_id");
    }

    private int subs() {
        return new JdbcTemplate(dataSource).queryForObject("select count(*) from plan_subscriptions", Integer.class);
    }

    @Test
    void purchaseCreatesPendingSubscriptionAndNeverActivatesIt() {
        Long planId = activePlan();
        when(orders.createOrder(any())).thenReturn(new CreateOrderResponse(1L, "PENDING"));
        var result = inTx(() -> purchases.purchase(athleteId, planId));
        assertThat(result).containsEntry("status", SubscriptionStatus.PENDING_PAYMENT)
                .containsEntry("plan_id", planId)
                .containsEntry("order_id", 1L)
                .hasEntrySatisfying("subscription_id", id -> assertThat(id).isInstanceOf(Long.class));
        assertThat(result).containsEntry("payment_required", true);
        assertThat(result).containsEntry("amount_due", null);
        var row = new JdbcTemplate(dataSource).queryForMap("select status, athlete_id, coach_id from plan_subscriptions");
        assertThat(row.get("status")).isEqualTo("PENDING_PAYMENT");
        assertThat(row.get("athlete_id")).isEqualTo(athleteUserId);
    }

    @Test
    void orderFailureLeavesNoSubscriptionBehind() {
        Long planId = activePlan();
        when(orders.createOrder(any())).thenThrow(new ApiException(503, "ORDER_UNAVAILABLE", "down", "پایین"));
        assertThatThrownBy(() -> inTx(() -> purchases.purchase(athleteId, planId)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code).isEqualTo("ORDER_UNAVAILABLE"));
        assertThat(subs()).isZero();
    }

    @Test
    void archivedPlanCannotBePurchased() {
        Long planId = activePlan();
        inTx(() -> { planService.archive(coachId, planId); return null; });
        assertThatThrownBy(() -> inTx(() -> purchases.purchase(athleteId, planId)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code).isEqualTo("PLAN_ARCHIVED"));
        verifyNoInteractions(orders);
    }

    @Test
    void coachCannotBuyOwnPlan() {
        Long planId = activePlan();
        assertThatThrownBy(() -> inTx(() -> purchases.purchase(coachId, planId)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(403));
        verifyNoInteractions(orders);
    }

    @Test
    void paymentSuccessActivatesOnceAndDuplicatesAreNoOps() {
        Long planId = activePlan();
        Long orderId = 1L;
        when(orders.createOrder(any())).thenReturn(new CreateOrderResponse(orderId, "PENDING"));
        inTx(() -> purchases.purchase(athleteId, planId));
        UUID eventId = UUID.randomUUID();
        Instant paidAt = Instant.parse("2026-11-18T10:00:00Z");
        assertThat(inTx(() -> purchases.applyPaymentSuccess(eventId, orderId, paidAt))).isTrue();
        var first = new JdbcTemplate(dataSource).queryForMap(
                "select status, starts_at, ends_at from plan_subscriptions where order_id=?", orderId);
        assertThat(first.get("status")).isEqualTo("ACTIVE");

        // Same event id: ignored before touching state.
        assertThat(inTx(() -> purchases.applyPaymentSuccess(eventId, orderId, paidAt))).isFalse();
        // Different event id, same order: activation is conditional on pending, so still no change.
        assertThat(inTx(() -> purchases.applyPaymentSuccess(UUID.randomUUID(), orderId, paidAt.plusSeconds(3600)))).isFalse();
        var second = new JdbcTemplate(dataSource).queryForMap(
                "select starts_at, ends_at from plan_subscriptions where order_id=?", orderId);
        assertThat(second.get("starts_at")).isEqualTo(first.get("starts_at"));
        assertThat(second.get("ends_at")).isEqualTo(first.get("ends_at"));
    }

    @Test
    void unknownOrderIsAcknowledgedWithoutChangingAnything() {
        assertThat(inTx(() -> purchases.applyPaymentSuccess(UUID.randomUUID(), 999L, Instant.now())))
                .isFalse();
        assertThat(new JdbcTemplate(dataSource).queryForObject("select count(*) from consumed_events", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void subscriptionDurationComesFromThePlan() {
        Long planId = activePlan();
        Long orderId = 1L;
        when(orders.createOrder(any())).thenReturn(new CreateOrderResponse(orderId, "PENDING"));
        inTx(() -> purchases.purchase(athleteId, planId));
        Instant paidAt = Instant.parse("2026-11-18T10:00:00Z");
        inTx(() -> purchases.applyPaymentSuccess(UUID.randomUUID(), orderId, paidAt));
        var row = new JdbcTemplate(dataSource).queryForMap(
                "select starts_at, ends_at from plan_subscriptions where order_id=?", orderId);
        var starts = ((java.sql.Timestamp) row.get("starts_at")).toInstant();
        var ends = ((java.sql.Timestamp) row.get("ends_at")).toInstant();
        assertThat(java.time.Duration.between(starts, ends).toDays()).isEqualTo(30);
    }
}
