package ir.vigoapp.user;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import ir.vigoapp.contracts.user.v1.CreateUserRequest;
import ir.vigoapp.contracts.user.v1.UserAuthInfo;
import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.api.Requests;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.outbox.OutboxWriter;
import ir.vigoapp.user.service.CoachApplicationService;
import ir.vigoapp.user.service.CredentialService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Real PostgreSQL coverage of the anonymous application workflow: no account is
 * created on submission or approval, and only registration proving the invited
 * email can grant COACH.
 */
class CoachApplicationWorkflowTest {
    static EmbeddedPostgres postgres;
    static DataSource dataSource;
    static EntityManagerFactory entityManagerFactory;
    static EntityManager entityManager;
    CoachApplicationService service;
    CredentialService credentials;
    OutboxWriter outbox;
    TransactionTemplate tx;

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
        entityManagerFactory = factory.getObject();
        // Shared proxy: repositories join the TransactionTemplate-bound EntityManager.
        entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    }

    @AfterAll
    static void stop() throws Exception {
        if (entityManager != null) entityManager.close();
        if (entityManagerFactory != null) entityManagerFactory.close();
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void wire() {
        new JdbcTemplate(dataSource).update("delete from coach_invitations; delete from coach_application_tokens; delete from coach_applications; delete from outbox; delete from coach_profiles; delete from users");
        var factory = new JpaRepositoryFactory(entityManager);
        var applications = factory.getRepository(CoachApplicationRepository.class);
        var tokens = factory.getRepository(CoachApplicationTokenRepository.class);
        var invitations = factory.getRepository(CoachInvitationRepository.class);
        var users = factory.getRepository(UserRepository.class);
        var coaches = factory.getRepository(CoachRepository.class);
        var json = JsonMapper.builder().findAndAddModules().build();
        // Real outbox writer so the assertions cover the actual staged event rows.
        outbox = new OutboxWriter(new JdbcTemplate(dataSource), json);
        service = new CoachApplicationService(applications, tokens, invitations, users, outbox, json,
                Duration.ofDays(30), Duration.ofDays(90));
        credentials = new CredentialService(users, outbox, Duration.ZERO, invitations, applications, coaches);
        tx = new TransactionTemplate(new JpaTransactionManager(entityManagerFactory));
    }

    /** Production calls run inside @Transactional proxies; tests must supply the transaction. */
    private <T> T inTx(java.util.function.Supplier<T> work) { return tx.execute(status -> work.get()); }

    private static Requests.Application application(String email) {
        return new Requests.Application("Sara Coach", email, null, "Head coach",
                "Fifteen years coaching strength athletes.", List.of("Strength"), 12, null, null, "fa");
    }

    private long accounts() { return new JdbcTemplate(dataSource).queryForObject("select count(*) from users", Long.class); }
    private long outboxRows() { return new JdbcTemplate(dataSource).queryForObject("select count(*) from outbox", Long.class); }

    @Test
    void submissionCreatesNoAccountAndNoInvitation() {
        Map<String, Object> result = inTx(() -> service.submit(application("new@example.com")));
        assertThat(result.get("status")).isEqualTo(CoachApplicationStatus.PENDING);
        assertThat(result.get("lookup_token")).isNotNull();
        assertThat(accounts()).isZero();
        assertThat(new JdbcTemplate(dataSource).queryForObject("select count(*) from coach_invitations", Long.class)).isZero();
        assertThat(outboxRows()).isEqualTo(1);
    }

    @Test
    void duplicateSubmissionReusesPendingAndIssuesAnotherLookupToken() {
        var first = inTx(() -> service.submit(application("dupe@example.com")));
        var second = inTx(() -> service.submit(application("DUPE@example.com")));
        assertThat(second.get("application_id")).isEqualTo(first.get("application_id"));
        assertThat((String) second.get("lookup_token")).isNotEqualTo(first.get("lookup_token"));
        assertThat(new JdbcTemplate(dataSource).queryForObject("select count(*) from coach_applications", Long.class)).isEqualTo(1);
        assertThat(outboxRows()).isEqualTo(1);
    }

    @Test
    void validInputButInvalidEmailIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> inTx(() -> service.submit(
                new Requests.Application("Sara", "not-an-email", null, null, "Fifteen years coaching athletes.",
                        List.of("Strength"), 5, null, null, "fa"))));
        assertThat(accounts()).isZero();
    }

    @Test
    void statusLookupReturnsSummaryAndRejectsUnknownToken() {
        var submitted = inTx(() -> service.submit(application("status@example.com")));
        var status = service.status((String) submitted.get("lookup_token"));
        assertThat(status.get("status")).isEqualTo(CoachApplicationStatus.PENDING);
        assertThat(status).doesNotContainKeys("email", "full_name", "phone");
        assertThatThrownBy(() -> service.status("not-a-real-token")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status).isEqualTo(404));
    }

    @Test
    void approvalIssuesInvitationWithoutCreatingAccount() {
        var submitted = inTx(() -> service.submit(application("approve@example.com")));
        var approved = inTx(() -> service.approve(java.util.UUID.randomUUID(), (java.util.UUID) submitted.get("application_id")));
        assertThat(approved.get("status")).isEqualTo(CoachApplicationStatus.APPROVED);
        assertThat(approved.get("invitation_expires_at")).isNotNull();
        assertThat(accounts()).isZero();
        assertThat(new JdbcTemplate(dataSource).queryForObject("select count(*) from coach_invitations where consumed_at is null", Long.class)).isEqualTo(1);
        assertThat(outboxRows()).isEqualTo(2);
    }

    @Test
    void repeatedApprovalOrRejectionReturnsConflict() {
        var submitted = inTx(() -> service.submit(application("repeat@example.com")));
        var id = (java.util.UUID) submitted.get("application_id");
        inTx(() -> service.approve(java.util.UUID.randomUUID(), id));
        assertThatThrownBy(() -> inTx(() -> service.approve(java.util.UUID.randomUUID(), id)))
                .isInstanceOfSatisfying(ApiException.class, e -> { assertThat(e.status).isEqualTo(409); assertThat(e.code).isEqualTo("INVALID_STATE"); });
        assertThatThrownBy(() -> inTx(() -> service.reject(java.util.UUID.randomUUID(), id, "Too late")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(409));
    }

    @Test
    void rejectionCreatesNoInvitation() {
        var submitted = inTx(() -> service.submit(application("reject@example.com")));
        var rejected = inTx(() -> service.reject(java.util.UUID.randomUUID(), (java.util.UUID) submitted.get("application_id"), "Insufficient evidence"));
        assertThat(rejected.get("status")).isEqualTo(CoachApplicationStatus.REJECTED);
        assertThat(rejected.get("rejection_reason")).isEqualTo("Insufficient evidence");
        assertThat(new JdbcTemplate(dataSource).queryForObject("select count(*) from coach_invitations", Long.class)).isZero();
    }

    @Test
    void invitedRegistrationCreatesCoachOnceAndConsumesInvitation() {
        var submitted = inTx(() -> service.submit(application("invited@example.com")));
        inTx(() -> service.approve(java.util.UUID.randomUUID(), (java.util.UUID) submitted.get("application_id")));

        UserAuthInfo created = inTx(() -> credentials.create(CreateUserRequest.newBuilder()
                .setEmail("invited@example.com").setFirstName("Sara").setLastName("Coach")
                .setPasswordHash("encoded").build()));
        assertThat(created.getRolesList()).containsExactly("COACH");
        assertThat(accounts()).isEqualTo(1);
        assertThat(new JdbcTemplate(dataSource).queryForObject("select count(*) from coach_profiles where status='APPROVED'", Long.class)).isEqualTo(1);
        assertThat(new JdbcTemplate(dataSource).queryForObject("select count(*) from coach_invitations where consumed_at is not null", Long.class)).isEqualTo(1);

        // A second account for the same address is refused by uniqueness, and the
        // invitation cannot be replayed even if that constraint were bypassed.
        assertThatThrownBy(() -> inTx(() -> credentials.create(CreateUserRequest.newBuilder()
                .setEmail("invited@example.com").setFirstName("Sara").setLastName("Coach")
                .setPasswordHash("encoded").build())));
    }

    @Test
    void ordinaryRegistrationStaysAthlete() {
        UserAuthInfo created = inTx(() -> credentials.create(CreateUserRequest.newBuilder()
                .setEmail("plain@example.com").setFirstName("Plain").setLastName("User")
                .setPasswordHash("encoded").build()));
        assertThat(created.getRolesList()).containsExactly("ATHLETE");
    }

    @Test
    void expiredInvitationDoesNotGrantCoach() {
        var submitted = inTx(() -> service.submit(application("expired@example.com")));
        var id = (java.util.UUID) submitted.get("application_id");
        inTx(() -> service.approve(java.util.UUID.randomUUID(), id));
        new JdbcTemplate(dataSource).update("update coach_invitations set expires_at = now() - interval '1 day' where application_id = ?", id);
        UserAuthInfo created = inTx(() -> credentials.create(CreateUserRequest.newBuilder()
                .setEmail("expired@example.com").setFirstName("Late").setLastName("Applicant")
                .setPasswordHash("encoded").build()));
        assertThat(created.getRolesList()).containsExactly("ATHLETE");
        assertThat(new JdbcTemplate(dataSource).queryForObject("select count(*) from coach_invitations where consumed_at is null", Long.class)).isEqualTo(1);
    }
}
