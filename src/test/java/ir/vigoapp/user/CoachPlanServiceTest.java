package ir.vigoapp.user;

import ir.vigoapp.user.entity.CoachProfile;
import ir.vigoapp.user.entity.UserAccount;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.domain.plan.PlanStatus;
import ir.vigoapp.user.dto.Requests;
import ir.vigoapp.user.entity.plan.catalog.PlanIncludeOption;
import ir.vigoapp.user.repository.CoachPlanIncludeRepository;
import ir.vigoapp.user.repository.CoachPlanRepository;
import ir.vigoapp.user.repository.CoachRepository;
import ir.vigoapp.user.repository.PlanIncludeOptionRepository;
import ir.vigoapp.user.service.CoachPlanService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
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

/** Plan ownership, activation state and public visibility on a real database. */
class CoachPlanServiceTest {
    static EmbeddedPostgres postgres;
    static DataSource dataSource;
    static EntityManagerFactory emf;
    EntityManager em;
    TransactionTemplate tx;
    CoachPlanService service;

    UUID coachA = UUID.randomUUID();
    UUID coachB = UUID.randomUUID();

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
        new JdbcTemplateReset().run();
        em = SharedEntityManagerCreator.createSharedEntityManager(emf);
        tx = new TransactionTemplate(new JpaTransactionManager(emf));
        var factory = new JpaRepositoryFactory(em);
        service = new CoachPlanService(factory.getRepository(CoachPlanRepository.class),
                factory.getRepository(CoachPlanIncludeRepository.class),
                factory.getRepository(CoachRepository.class),
                factory.getRepository(PlanIncludeOptionRepository.class));
        inTx(() -> { seedCoach(coachA); seedCoach(coachB); return null; });
    }

    private class JdbcTemplateReset {
        void run() {
            new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                    "delete from plan_subscriptions; delete from coach_plan_includes; delete from coach_plans; delete from coach_profiles; delete from users");
        }
    }

    private void seedCoach(UUID publicId) {
        var u = new UserAccount();
        u.setPublicId(publicId); u.setEmail(publicId + "@example.test");
        u.setDisplayName("Coach " + publicId); u.replacePassword("hash");
        u.setRole(Role.COACH); u.setEmailVerified(true);
        em.persist(u);
        em.flush(); // @MapsId needs the generated identity before the profile is persisted.
        var c = new CoachProfile();
        c.setUser(u); c.setDisplayName(u.getDisplayName()); c.setBio("Synthetic coach biography");
        c.setSpecialties("[]"); c.setStatus(CoachStatus.APPROVED);
        em.persist(c);
    }

    private <T> T inTx(java.util.function.Supplier<T> work) { return tx.execute(s -> work.get()); }

    private static Requests.Plan plan(String title, long price, String... includes) {
        return new Requests.Plan(title, "Plan description", price, 30, List.of(includes));
    }

    @Test
    void coachCreatesAndListsOnlyOwnPlans() {
        inTx(() -> { service.create(coachA, plan("A plan", 100000, "WORKOUT")); return null; });
        inTx(() -> { service.create(coachB, plan("B plan", 200000, "NUTRITION")); return null; });
        var a = inTx(() -> service.own(coachA));
        var b = inTx(() -> service.own(coachB));
        assertThat(a).extracting(m -> m.get("title")).containsExactly("A plan");
        assertThat(b).extracting(m -> m.get("title")).containsExactly("B plan");
        assertThat(List.of("WORKOUT")).isEqualTo(a.get(0).get("includes"));
    }

    @Test
    void combinedAndSingleCategoriesAreJustDifferentIncludes() {
        var combined = inTx(() -> service.create(coachA, plan("Combined", 300000,
                "WORKOUT", "NUTRITION")));
        assertThat(List.of("WORKOUT", "NUTRITION")).isEqualTo(combined.get("includes"));
    }

    @Test
    void newCatalogOptionCanBeSelectedAndExistingSelectionsSurviveDeactivation() {
        var option = new PlanIncludeOption();
        option.setCode("VIDEO_REVIEW");
        option.setLabel("Video review");
        option.setDisplayOrder(50);
        inTx(() -> { em.persist(option); return null; });

        var created = inTx(() -> service.create(coachA, plan("Complete plan", 100000,
                "WORKOUT", "NUTRITION", "SUPPLEMENT", "CHAT_SUPPORT", "VIDEO_REVIEW")));
        Long planId = (Long) created.get("plan_id");
        assertThat(created).containsEntry("includes",
                List.of("WORKOUT", "NUTRITION", "SUPPLEMENT", "CHAT_SUPPORT", "VIDEO_REVIEW"));

        inTx(() -> { em.find(PlanIncludeOption.class, option.getId()).setActive(false); return null; });
        assertThatThrownBy(() -> inTx(() -> service.create(coachB, plan("New plan", 100000, "VIDEO_REVIEW"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code).isEqualTo("VALIDATION_FAILED"));

        var updated = inTx(() -> service.update(coachA, planId, plan("Edited plan", 100000, "VIDEO_REVIEW")));
        assertThat(updated).containsEntry("includes", List.of("VIDEO_REVIEW"));
    }

    @Test
    void anotherCoachCannotEditArchiveOrActivateSomeoneElsesPlan() {
        Long planId = (Long) inTx(() -> service.create(coachA, plan("Mine", 100000, "WORKOUT"))).get("plan_id");
        assertThatThrownBy(() -> inTx(() -> service.update(coachB, planId, plan("Hijack", 100000, "WORKOUT"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(403));
        assertThatThrownBy(() -> inTx(() -> { service.archive(coachB, planId); return null; }))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(403));
        assertThatThrownBy(() -> inTx(() -> { service.activate(coachB, planId); return null; }))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(403));
    }

    @Test
    void archivedPlanLeavesMarketplaceAndCannotBeEdited() {
        Long planId = (Long) inTx(() -> service.create(coachA, plan("Season", 100000, "WORKOUT"))).get("plan_id");
        assertThat(inTx(() -> service.marketplace(0, 20, null)).content()).hasSize(1);
        inTx(() -> { service.archive(coachA, planId); return null; });
        assertThat(inTx(() -> service.marketplace(0, 20, null)).content()).isEmpty();
        assertThatThrownBy(() -> inTx(() -> service.update(coachA, planId, plan("Season 2", 100000, "WORKOUT"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code).isEqualTo("PLAN_ARCHIVED"));
        assertThatThrownBy(() -> inTx(() -> { service.archive(coachA, planId); return null; }))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(409));
    }

    @Test
    void reactivatingRestoresMarketplaceVisibility() {
        Long planId = (Long) inTx(() -> service.create(coachA, plan("Season", 100000, "WORKOUT"))).get("plan_id");
        inTx(() -> { service.archive(coachA, planId); return null; });
        inTx(() -> { service.activate(coachA, planId); return null; });
        assertThat(inTx(() -> service.marketplace(0, 20, null)).content()).hasSize(1);
        assertThat(inTx(() -> service.detail(planId))).containsEntry("status", PlanStatus.ACTIVE);
    }

    @Test
    void unapprovedCoachCannotCreatePlans() {
        UUID pending = UUID.randomUUID();
        inTx(() -> {
            var u = new UserAccount();
            u.setPublicId(pending); u.setEmail("pending@example.test"); u.setDisplayName("Pending");
            u.replacePassword("hash"); u.setRole(Role.ATHLETE); u.setEmailVerified(true);
            em.persist(u);
            em.flush();
            return null;
        });
        assertThatThrownBy(() -> inTx(() -> service.create(pending, plan("Nope", 100000, "WORKOUT"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(403));
    }

    @Test
    void detailOfArchivedPlanIsNotFoundForCustomers() {
        Long planId = (Long) inTx(() -> service.create(coachA, plan("Season", 100000, "WORKOUT"))).get("plan_id");
        inTx(() -> { service.archive(coachA, planId); return null; });
        assertThatThrownBy(() -> inTx(() -> service.detail(planId)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(404));
    }
}
