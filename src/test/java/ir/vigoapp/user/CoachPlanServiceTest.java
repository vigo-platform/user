package ir.vigoapp.user;

import ir.vigoapp.user.entity.CoachProfile;
import ir.vigoapp.user.entity.UserAccount;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.domain.plan.PlanStatus;
import ir.vigoapp.user.dto.Requests;
import ir.vigoapp.user.dto.plan.PlanFieldValue;
import ir.vigoapp.user.entity.plan.catalog.PlanIncludeOption;
import ir.vigoapp.user.repository.CoachPlanIncludeRepository;
import ir.vigoapp.user.repository.CoachPlanRepository;
import ir.vigoapp.user.repository.CoachRepository;
import ir.vigoapp.user.repository.plan.CoachPlanSelectionRepository;
import ir.vigoapp.user.repository.plan.PlanCatalogRepository;
import ir.vigoapp.user.service.CoachPlanService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
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
    PlanCatalogRepository catalog;
    JsonMapper json = JsonMapper.builder().build();

    long coachA;
    long coachB;

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
        var jdbc = JdbcClient.create(dataSource);
        catalog = new PlanCatalogRepository(jdbc, json);
        service = new CoachPlanService(factory.getRepository(CoachPlanRepository.class),
                factory.getRepository(CoachPlanIncludeRepository.class),
                factory.getRepository(CoachRepository.class), catalog, new CoachPlanSelectionRepository(jdbc, json));
        coachA = inTx(() -> seedCoach("a"));
        coachB = inTx(() -> seedCoach("b"));
    }

    private class JdbcTemplateReset {
        void run() {
            new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                    "delete from plan_subscriptions; delete from coach_plan_includes; delete from coach_plans; delete from coach_profiles; delete from users");
        }
    }

    private long seedCoach(String name) {
        var u = new UserAccount();
        u.setEmail(name + "@example.test");
        u.setDisplayName("Coach " + name); u.replacePassword("hash");
        u.setRole(Role.COACH); u.setEmailVerified(true);
        em.persist(u);
        em.flush(); // @MapsId needs the generated identity before the profile is persisted.
        var c = new CoachProfile();
        c.setUser(u); c.setDisplayName(u.getDisplayName()); c.setBio("Synthetic coach biography");
        c.setSpecialties("[]"); c.setStatus(CoachStatus.APPROVED);
        em.persist(c);
        return u.getId();
    }

    private <T> T inTx(java.util.function.Supplier<T> work) { return tx.execute(s -> work.get()); }

    private Requests.Plan plan(String title, long price, String... includes) {
        return new Requests.Plan(title, "Plan description", price, 30, List.of(includes), null,
                requiredPlanFields());
    }

    private List<PlanFieldValue> requiredPlanFields() {
        var values = Map.of("PRIMARY_GOALS", "[\"STRENGTH\"]", "TRAINING_LOCATION", "[\"GYM\"]",
                "WEEKLY_TRAINING_SESSIONS", "3", "PLAN_LEVEL", "[\"BEGINNER\",\"INTERMEDIATE\"]");
        return catalog.findGeneralFields().stream()
                .map(field -> new PlanFieldValue(field.id(), json.readTree(values.get(field.code()))))
                .sorted(Comparator.comparingLong(PlanFieldValue::fieldId)).toList();
    }

    @Test
    void requiresLevelAndValidatesItsOptions() {
        var level = catalog.findGeneralFields().stream().filter(field -> field.code().equals("PLAN_LEVEL"))
                .findFirst().orElseThrow();
        assertThat(catalog.findGeneralFields()).allMatch(field -> field.required());
        assertThat(catalog.findFieldDetails(level.id()).orElseThrow().validationRules().path("options").size())
                .isEqualTo(4);
        var fields = new ArrayList<>(requiredPlanFields());
        fields.removeIf(field -> field.fieldId() == level.id());
        var request = new Requests.Plan("Workout", null, 100000L, 30, List.of("WORKOUT"), null, fields);

        assertThatThrownBy(() -> inTx(() -> service.create(coachA, request)))
                .isInstanceOf(ApiException.class).hasMessage("Missing required field: PLAN_LEVEL");
        fields.add(new PlanFieldValue(level.id(), json.readTree("[\"UNKNOWN_LEVEL\"]")));
        assertThatThrownBy(() -> inTx(() -> service.create(coachA, request)))
                .isInstanceOf(ApiException.class).hasMessage("Invalid or duplicate value for PLAN_LEVEL");
        assertThat(inTx(() -> service.own(coachA))).isEmpty();
    }

    @Test
    void featureFieldsAreOptionalAndBelongToSelectedFeatures() {
        var feature = catalog.findFeatures().stream().filter(option -> option.code().equals("PERIODIC_PLAN_REVIEW"))
                .findFirst().orElseThrow();
        var field = catalog.findFeatureDetails(feature.id()).orElseThrow().fields().getFirst();
        assertThat(field.required()).isFalse();
        var required = requiredPlanFields();
        var request = new Requests.Plan("Workout", null, 100000L, 30, List.of("WORKOUT"), List.of(feature.id()), required);
        var created = inTx(() -> service.create(coachA, request));
        var planId = (Long) created.get("planId");
        assertThat(created).containsEntry("featureIds", List.of(feature.id())).containsEntry("fieldValues", required);

        var fields = new ArrayList<>(required);
        fields.add(new PlanFieldValue(field.id(), json.readTree("14")));
        fields.sort(Comparator.comparingLong(PlanFieldValue::fieldId));
        var updated = new Requests.Plan("Updated workout", null, 100000L, 30, List.of("WORKOUT"), List.of(feature.id()), fields);
        assertThat(inTx(() -> service.update(coachA, planId, updated)))
                .containsEntry("featureIds", List.of(feature.id())).containsEntry("fieldValues", fields);

        var unselected = new Requests.Plan("Updated workout", null, 100000L, 30, List.of("WORKOUT"), null, fields);
        assertThatThrownBy(() -> inTx(() -> service.update(coachA, planId, unselected)))
                .isInstanceOf(ApiException.class).hasMessage("Select the feature before setting REVIEW_INTERVAL_DAYS");
        assertThat(inTx(() -> service.detail(planId))).containsEntry("fieldValues", fields);
        assertThat(inTx(() -> service.update(coachA, planId, plan("Workout", 100000, "WORKOUT"))))
                .containsEntry("featureIds", List.of()).containsEntry("fieldValues", required);
    }

    @Test
    void migrationPreservesExistingLevelSelections() {
        new JdbcTemplate(dataSource).execute("CREATE DATABASE plan_level_upgrade");
        var legacyDataSource = postgres.getDatabase("postgres", "plan_level_upgrade");
        var legacy = new JdbcTemplate(legacyDataSource);
        Flyway.configure().dataSource(legacyDataSource).target("9").load().migrate();
        legacy.update("""
                INSERT INTO users (id, public_id, email, password_hash, display_name, role, status,
                                   credentials_changed_at, created_at, updated_at)
                VALUES (1, ?, 'legacy@example.test', 'test-hash', 'Legacy coach', 'COACH', 'ACTIVE', now(), now(), now())
                """, UUID.randomUUID());
        legacy.update("""
                INSERT INTO coach_profiles (user_id, display_name, bio, specialties, experience_years,
                                            certifications, result_image_urls, status, created_at, updated_at)
                VALUES (1, 'Legacy coach', 'Coach biography', '[]', 1, '[]', 'APPROVED', now(), now())
                """);
        legacy.update("""
                INSERT INTO coach_plans (id, coach_user_id, title, price_rial, duration_days, created_at, updated_at)
                VALUES (1, 1, 'Legacy plan', 100000, 30, now(), now())
                """);
        legacy.update("INSERT INTO coach_plan_levels (plan_id, level) VALUES (1, 'ADVANCED'), (1, 'BEGINNER')");
        legacy.update("""
                INSERT INTO coach_plan_field_values (plan_id, field_id, value)
                SELECT 1, id, '["STRENGTH"]'::jsonb FROM plan_field_definitions WHERE code = 'PRIMARY_GOALS'
                """);

        Flyway.configure().dataSource(legacyDataSource).load().migrate();

        assertThat(legacy.queryForObject("SELECT to_regclass('coach_plan_levels')::text", String.class)).isNull();
        assertThat(legacy.query("""
                SELECT code, value::text FROM coach_plan_field_values
                JOIN plan_field_definitions ON id = field_id
                WHERE plan_id = 1
                ORDER BY code
                """, (row, index) -> Map.entry(row.getString("code"), json.readTree(row.getString("value")))))
                .containsExactly(Map.entry("PLAN_LEVEL", json.readTree("[\"BEGINNER\",\"ADVANCED\"]")),
                        Map.entry("PRIMARY_GOALS", json.readTree("[\"STRENGTH\"]")));
        assertThat(legacy.queryForObject("""
                SELECT count(*) FROM plan_field_definitions WHERE is_required <> (feature_id IS NULL)
                """, Long.class)).isZero();
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
        Long planId = (Long) created.get("planId");
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
        Long planId = (Long) inTx(() -> service.create(coachA, plan("Mine", 100000, "WORKOUT"))).get("planId");
        assertThatThrownBy(() -> inTx(() -> service.update(coachB, planId, plan("Hijack", 100000, "WORKOUT"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(403));
        assertThatThrownBy(() -> inTx(() -> { service.archive(coachB, planId); return null; }))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(403));
        assertThatThrownBy(() -> inTx(() -> { service.activate(coachB, planId); return null; }))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(403));
    }

    @Test
    void archivedPlanLeavesMarketplaceAndCannotBeEdited() {
        Long planId = (Long) inTx(() -> service.create(coachA, plan("Season", 100000, "WORKOUT"))).get("planId");
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
        Long planId = (Long) inTx(() -> service.create(coachA, plan("Season", 100000, "WORKOUT"))).get("planId");
        inTx(() -> { service.archive(coachA, planId); return null; });
        inTx(() -> { service.activate(coachA, planId); return null; });
        assertThat(inTx(() -> service.marketplace(0, 20, null)).content()).hasSize(1);
        assertThat(inTx(() -> service.detail(planId))).containsEntry("status", PlanStatus.ACTIVE);
    }

    @Test
    void unapprovedCoachCannotCreatePlans() {
        long pending = inTx(() -> {
            var u = new UserAccount();
            u.setEmail("pending@example.test"); u.setDisplayName("Pending");
            u.replacePassword("hash"); u.setRole(Role.ATHLETE); u.setEmailVerified(true);
            em.persist(u);
            em.flush();
            return u.getId();
        });
        assertThatThrownBy(() -> inTx(() -> service.create(pending, plan("Nope", 100000, "WORKOUT"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(403));
    }

    @Test
    void detailOfArchivedPlanIsNotFoundForCustomers() {
        Long planId = (Long) inTx(() -> service.create(coachA, plan("Season", 100000, "WORKOUT"))).get("planId");
        inTx(() -> { service.archive(coachA, planId); return null; });
        assertThatThrownBy(() -> inTx(() -> service.detail(planId)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(404));
    }
}
