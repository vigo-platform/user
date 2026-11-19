package ir.vigoapp.user;

import ir.vigoapp.user.entity.CoachProfile;
import ir.vigoapp.user.entity.UserAccount;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.domain.plan.PlanStatus;
import ir.vigoapp.user.api.Errors;
import ir.vigoapp.user.api.plan.CoachPlanManagementController;
import ir.vigoapp.user.dto.plan.ManagedCoachPlan;
import ir.vigoapp.user.dto.plan.PlanValidationIssue;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions;
import ir.vigoapp.user.dto.plan.PlanFieldValue;
import ir.vigoapp.user.entity.plan.catalog.PlanIncludeOption;
import ir.vigoapp.user.repository.CoachPlanIncludeRepository;
import ir.vigoapp.user.repository.CoachPlanRepository;
import ir.vigoapp.user.repository.CoachRepository;
import ir.vigoapp.user.repository.plan.CoachPlanSelectionRepository;
import ir.vigoapp.user.repository.plan.PlanCatalogRepository;
import ir.vigoapp.user.service.plan.CoachPlanManagementService;
import ir.vigoapp.user.service.plan.validation.PlanSubmissionValidator;
import ir.vigoapp.user.service.plan.validation.PlanValidationException;
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
import tools.jackson.databind.node.ObjectNode;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
    CoachPlanManagementService service;
    CoachPlanSelectionRepository selections;
    List<String> selectionStatements = new ArrayList<>();
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
        var database = JdbcClient.create(dataSource);
        JdbcClient jdbc = sql -> {
            selectionStatements.add(sql);
            return database.sql(sql);
        };
        catalog = new PlanCatalogRepository(jdbc, json);
        var includes = factory.getRepository(CoachPlanIncludeRepository.class);
        selections = new CoachPlanSelectionRepository(jdbc, json);
        var management = new CoachPlanManagementService(factory.getRepository(CoachPlanRepository.class),
                includes, factory.getRepository(CoachRepository.class), new PlanSubmissionValidator(catalog),
                selections);
        var transactions = new TransactionInterceptor();
        transactions.setTransactionManager(tx.getTransactionManager());
        transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(management);
        proxy.addAdvice(transactions);
        service = (CoachPlanManagementService) proxy.getProxy();
        coachA = inTx(() -> seedCoach("a"));
        coachB = inTx(() -> seedCoach("b"));
    }

    private class JdbcTemplateReset {
        void run() {
            new JdbcTemplate(dataSource).update(
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

    private ObjectNode plan(String title, long price, String... includes) {
        var request = json.createObjectNode().put("title", title).put("price", price).put("durationDays", 30);
        request.set("includes", json.valueToTree(includes));
        request.set("fieldValues", json.valueToTree(requiredPlanFields()));
        return request;
    }

    private List<PlanFieldValue> requiredPlanFields() {
        var values = Map.of("PRIMARY_GOALS", "[\"STRENGTH\"]", "TRAINING_LOCATION", "[\"GYM\"]",
                "WEEKLY_TRAINING_SESSIONS", "3", "PLAN_LEVEL", "[\"BEGINNER\",\"INTERMEDIATE\"]");
        return formOptions().generalFields().stream()
                .map(field -> new PlanFieldValue(field.id(), json.readTree(values.get(field.code()))))
                .sorted(Comparator.comparingLong(PlanFieldValue::fieldId)).toList();
    }

    private CreatePlanFormOptions formOptions() {
        return new ir.vigoapp.user.service.plan.CoachPlanService(catalog).getFormOptions();
    }

    @Test
    void requiresLevelAndValidatesItsOptions() {
        var generalFields = formOptions().generalFields();
        var level = generalFields.stream().filter(field -> field.code().equals("PLAN_LEVEL"))
                .findFirst().orElseThrow();
        assertThat(generalFields).allMatch(field -> field.required());
        assertThat(level.validationRules().path("options").size()).isEqualTo(4);
        var fields = new ArrayList<>(requiredPlanFields());
        fields.removeIf(field -> field.fieldId() == level.id());
        var request = plan("Workout", 100000, "WORKOUT");
        request.set("fieldValues", json.valueToTree(fields));
        assertThatThrownBy(() -> service.create(coachA, request))
                .isInstanceOfSatisfying(PlanValidationException.class, e -> assertThat(e.issues()).containsExactly(
                        PlanValidationIssue.forField("fieldValues", "REQUIRED_FIELD_MISSING", level.id())));
        fields.add(new PlanFieldValue(level.id(), json.readTree("[\"UNKNOWN_LEVEL\"]")));
        request.set("fieldValues", json.valueToTree(fields));
        assertThatThrownBy(() -> service.create(coachA, request))
                .isInstanceOfSatisfying(PlanValidationException.class, e -> assertThat(e.issues()).containsExactly(
                        PlanValidationIssue.forField("fieldValues[" + (fields.size() - 1) + "].value[0]",
                                "OPTION_NOT_ALLOWED", level.id())));
        assertThat(inTx(() -> service.own(coachA))).isEmpty();
    }

    @Test
    void featureFieldsAreOptionalAndBelongToSelectedFeatures() {
        var feature = formOptions().features().stream().filter(option -> option.code().equals("PERIODIC_PLAN_REVIEW"))
                .findFirst().orElseThrow();
        var field = catalog.findFeatureDetails(feature.id()).orElseThrow().fields().getFirst();
        assertThat(field.required()).isFalse();
        var required = requiredPlanFields();
        var request = plan("Workout", 100000, "WORKOUT");
        request.putArray("featureIds").add(feature.id());
        var created = service.create(coachA, request);
        long planId = created.planId();
        assertThat(created.featureIds()).containsExactly(feature.id());
        assertThat(created.fieldValues()).isEqualTo(required);

        var fields = new ArrayList<>(required);
        fields.add(new PlanFieldValue(field.id(), json.readTree("14")));
        fields.sort(Comparator.comparingLong(PlanFieldValue::fieldId));
        request.put("title", "Updated workout").set("fieldValues", json.valueToTree(fields));
        var updated = service.update(coachA, planId, request);
        assertThat(updated.featureIds()).containsExactly(feature.id());
        assertThat(updated.fieldValues()).isEqualTo(fields);

        request.remove("featureIds");
        assertThatThrownBy(() -> service.update(coachA, planId, request))
                .isInstanceOfSatisfying(PlanValidationException.class, e -> assertThat(e.issues())
                        .extracting(PlanValidationIssue::code).containsExactly("FEATURE_NOT_SELECTED"));
        assertThat(service.own(coachA)).singleElement()
                .satisfies(view -> assertThat(view.fieldValues()).isEqualTo(fields));
        var cleared = service.update(coachA, planId, plan("Workout", 100000, "WORKOUT"));
        assertThat(cleared.featureIds()).isEmpty();
        assertThat(cleared.fieldValues()).isEqualTo(required);
    }

    @Test
    void coachCreatesAndListsOnlyOwnPlans() {
        inTx(() -> { service.create(coachA, plan("A plan", 100000, "WORKOUT")); return null; });
        inTx(() -> { service.create(coachB, plan("B plan", 200000, "NUTRITION")); return null; });
        var a = inTx(() -> service.own(coachA));
        var b = inTx(() -> service.own(coachB));
        assertThat(a).extracting(ManagedCoachPlan::title).containsExactly("A plan");
        assertThat(b).extracting(ManagedCoachPlan::title).containsExactly("B plan");
        assertThat(List.of("WORKOUT")).isEqualTo(a.getFirst().includes());
    }

    @Test
    void combinedAndSingleCategoriesAreJustDifferentIncludes() {
        var combined = inTx(() -> service.create(coachA, plan("Combined", 300000,
                "WORKOUT", "NUTRITION")));
        assertThat(List.of("WORKOUT", "NUTRITION")).isEqualTo(combined.includes());
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
        long planId = created.planId();
        assertThat(created.includes()).containsExactly("WORKOUT", "NUTRITION", "SUPPLEMENT", "CHAT_SUPPORT", "VIDEO_REVIEW");

        inTx(() -> { em.find(PlanIncludeOption.class, option.getId()).setActive(false); return null; });
        assertThatThrownBy(() -> inTx(() -> service.create(coachB, plan("New plan", 100000, "VIDEO_REVIEW"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code).isEqualTo("VALIDATION_FAILED"));

        var updated = inTx(() -> service.update(coachA, planId, plan("Edited plan", 100000, "VIDEO_REVIEW")));
        assertThat(updated.includes()).containsExactly("VIDEO_REVIEW");
    }

    @Test
    void anotherCoachCannotEditArchiveOrRestoreSomeoneElsesPlan() {
        long planId = inTx(() -> service.create(coachA, plan("Mine", 100000, "WORKOUT"))).planId();
        assertThatThrownBy(() -> inTx(() -> service.update(coachB, planId, plan("Hijack", 100000, "WORKOUT"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(403));
        assertThatThrownBy(() -> inTx(() -> { service.archive(coachB, planId); return null; }))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(403));
        assertThatThrownBy(() -> inTx(() -> { service.unarchive(coachB, planId); return null; }))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(403));
    }

    @Test
    void archivedPlanLeavesMarketplaceAndCannotBeEdited() {
        long planId = inTx(() -> service.create(coachA, plan("Season", 100000, "WORKOUT"))).planId();
        setActive(planId, true);
        assertThat(inTx(() -> service.marketplace(0, 20, null)).content()).hasSize(1);
        inTx(() -> { service.archive(coachA, planId); return null; });
        assertThat(inTx(() -> service.marketplace(0, 20, null)).content()).isEmpty();
        assertThatThrownBy(() -> inTx(() -> service.update(coachA, planId, plan("Season 2", 100000, "WORKOUT"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code).isEqualTo("PLAN_ARCHIVED"));
        assertThatThrownBy(() -> inTx(() -> { service.archive(coachA, planId); return null; }))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(409));
    }

    @Test
    void activationAndArchivingIndependentlyControlVisibilityAndPurchases() {
        var created = inTx(() -> service.create(coachA, plan("Season", 100000, "WORKOUT")));
        long planId = created.planId();
        assertThat(created.isActive()).isTrue();
        assertThat(created.isArchived()).isFalse();
        assertThat(created.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(service.marketplace(0, 20, null).content()).hasSize(1);
        assertThat(service.requirePurchasable(planId).getId()).isEqualTo(planId);

        setActive(planId, false);
        assertThat(service.update(coachA, planId, plan("Edited season", 120000, "WORKOUT")).isActive()).isFalse();
        assertThatThrownBy(() -> service.requirePurchasable(planId))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code).isEqualTo("PLAN_INACTIVE"));

        setActive(planId, true);
        assertThat(inTx(() -> service.marketplace(0, 20, coachA)).content()).hasSize(1);
        inTx(() -> { service.archive(coachA, planId); return null; });
        assertThat(service.own(coachA).getFirst()).extracting(ManagedCoachPlan::isActive, ManagedCoachPlan::isArchived)
                .containsExactly(true, true);
        assertThat(inTx(() -> service.marketplace(0, 20, coachA)).content()).isEmpty();
        assertThatThrownBy(() -> inTx(() -> service.requirePurchasable(planId)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code).isEqualTo("PLAN_ARCHIVED"));

        setActive(planId, false);
        inTx(() -> { service.unarchive(coachA, planId); return null; });
        assertThat(service.own(coachA).getFirst()).extracting(ManagedCoachPlan::isActive, ManagedCoachPlan::isArchived)
                .containsExactly(false, false);
        assertThat(inTx(() -> service.marketplace(0, 20, null)).content()).isEmpty();

        setActive(planId, true);
        assertThat(inTx(() -> service.marketplace(0, 20, null)).content()).hasSize(1);
        assertThat(service.detail(planId).status()).isEqualTo(PlanStatus.ACTIVE);
    }

    @Test
    void serverOwnsCurrencyAndOptionalColumnsAcceptNull() {
        var request = plan("Standard plan", 10000, "WORKOUT");
        var created = service.create(coachA, request);
        assertThat(created.currency()).isEqualTo("IRR");
        var stored = service.detail(created.planId());
        assertThat(stored.summary()).isNull();
        assertThat(stored.description()).isNull();
        assertThat(stored.compareAtPrice()).isNull();
        assertThat(stored.firstDeliveryDays()).isNull();

        request.put("currency", "USD");
        assertThatThrownBy(() -> service.update(coachA, created.planId(), request))
                .isInstanceOfSatisfying(PlanValidationException.class, e -> assertThat(e.issues()).containsExactly(
                        PlanValidationIssue.at("currency", "UNKNOWN_FIELD")));
        // Historical amounts retain their denomination when the coach edits a plan.
        new JdbcTemplate(dataSource).update("UPDATE coach_plans SET currency='USD', price=25 WHERE id=?", created.planId());
        request.remove("currency");
        request.put("price", 30);
        var updated = service.update(coachA, created.planId(), request);
        assertThat(updated.price()).isEqualTo(30);
        assertThat(updated.currency()).isEqualTo("USD");
    }

    @Test
    void httpSubmissionReportsErrorsTogetherAndCreatesOnlyTheCorrectedPlan() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new CoachPlanManagementController(service))
                .setControllerAdvice(new Errors()).build();
        var coach = new TestingAuthenticationToken(Long.toString(coachA), null, "ROLE_COACH");
        String url = "/api/v1/coaches/me/plans";
        var fields = formOptions().generalFields();
        long sessions = fields.stream().filter(f -> f.code().equals("WEEKLY_TRAINING_SESSIONS")).findFirst().orElseThrow().id();
        long level = fields.stream().filter(f -> f.code().equals("PLAN_LEVEL")).findFirst().orElseThrow().id();
        var request = plan("تمرین قدرتی", 9999, "WORKOUT", "WORKOUT", "MISSING");
        request.put("currency", "USD");
        request.putArray("featureIds").add(999999);
        request.putArray("fieldValues").addObject().put("fieldId", sessions).put("value", 8);
        var invalid = mvc.perform(post(url).principal(coach).contentType("application/json").content(request.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn().getResponse();
        var issues = json.treeToValue(json.readTree(invalid.getContentAsByteArray()).path("data"), PlanValidationIssue[].class);
        assertThat(issues).contains(
                PlanValidationIssue.at("currency", "UNKNOWN_FIELD"),
                PlanValidationIssue.at("includes[1]", "DUPLICATE_CODE"),
                PlanValidationIssue.at("includes[2]", "UNKNOWN_CODE"),
                PlanValidationIssue.forFeature("featureIds[0]", "UNKNOWN_ID", 999999),
                PlanValidationIssue.forField("fieldValues", "REQUIRED_FIELD_MISSING", level),
                new PlanValidationIssue("fieldValues[0].value", "INTEGER_TOO_LARGE", sessions, null, Map.of("max", 7)),
                new PlanValidationIssue("price", "INTEGER_TOO_SMALL", null, null, Map.of("min", 10000)));
        assertThat(service.own(coachA)).isEmpty();

        var corrected = plan("تمرین قدرتی", 10000, "WORKOUT");
        corrected.put("summary", "  خلاصه  ").put("firstDeliveryDays", 0)
                .put("compareAtPrice", 15000).put("description", "توضیحات پلن");
        mvc.perform(post(url).principal(coach).contentType("application/json").content(corrected.toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.isActive").value(true)).andExpect(jsonPath("$.data.isArchived").value(false))
                .andExpect(jsonPath("$.data.currency").value("IRR")).andExpect(jsonPath("$.data.planId").isNumber());
        var saved = service.own(coachA).getFirst();
        assertThat(saved.summary()).isEqualTo("خلاصه");
        assertThat(saved.description()).isEqualTo("توضیحات پلن");
        assertThat(saved.firstDeliveryDays()).isZero();
        assertThat(saved.compareAtPrice()).isEqualTo(15000);

        var edit = plan("ویرایش", 11000, "WORKOUT");
        assertThat(service.update(coachA, saved.planId(), edit).compareAtPrice()).isEqualTo(15000);
        edit.putNull("summary").putNull("compareAtPrice").putNull("firstDeliveryDays");
        service.update(coachA, saved.planId(), edit);
        var cleared = service.detail(saved.planId());
        assertThat(cleared.summary()).isNull();
        assertThat(cleared.compareAtPrice()).isNull();
        assertThat(cleared.firstDeliveryDays()).isNull();
    }

    @Test
    void selectedTextUsesCatalogLengthRulesAndRoundTripsWithoutOptionLabels() {
        var feature = formOptions().features().getFirst();
        long id = new JdbcTemplate(dataSource).queryForObject("""
                INSERT INTO plan_field_definitions (feature_id, code, label, value_type, validation_rules,
                    is_required, is_active, display_order, created_at, updated_at)
                VALUES (?, 'EQUIPMENT_NOTES', 'تجهیزات', 'TEXT', '{"minLength":2,"maxLength":5}',
                    false, true, 1000, now(), now()) RETURNING id
                """, Long.class, feature.id());
        var request = plan("تمرین در خانه", 10000, "WORKOUT");
        request.putArray("featureIds").add(feature.id());
        var answer = request.withArray("fieldValues").addObject().put("fieldId", id);
        for (var invalid : Map.of("1", "INVALID_VALUE_TYPE", "\"  \"", "REQUIRED_FIELD_MISSING",
                "\"A\"", "TEXT_TOO_SHORT", "\"123456\"", "TEXT_TOO_LONG").entrySet()) {
            answer.set("value", json.readTree(invalid.getKey()));
            assertThatThrownBy(() -> service.create(coachA, request))
                    .isInstanceOfSatisfying(PlanValidationException.class, e -> assertThat(e.issues())
                            .singleElement().satisfies(issue -> {
                                assertThat(issue.fieldId()).isEqualTo(id);
                                assertThat(issue.code()).isEqualTo(invalid.getValue());
                            }));
        }
        answer.put("value", "  دمبل  ");
        long planId = service.create(coachA, request).planId();
        assertThat(selections.findSelectedFields(planId)).filteredOn(f -> f.field().id() == id).singleElement()
                .satisfies(selection -> {
                    assertThat(selection.field().value().stringValue()).isEqualTo("دمبل");
                    assertThat(selection.field().selectedOptions()).isEmpty();
                });
    }

    @Test
    void catalogReadsAndSelectionWritesStayBatchedAsTheRequestGrows() {
        var options = formOptions();
        var request = plan("Batch plan", 10000, "WORKOUT");
        var features = request.putArray("featureIds");
        var featureIds = options.features().stream().limit(3).map(CreatePlanFormOptions.Item::id).toList();
        features.add(featureIds.getFirst());
        selectionStatements.clear();
        service.create(coachA, request);
        var single = List.copyOf(selectionStatements);
        features.add(featureIds.get(1)).add(featureIds.get(2));
        request.withArray("includes").add("NUTRITION");
        selectionStatements.clear();
        var created = service.create(coachA, request);
        assertThat(created.featureIds()).containsExactlyInAnyOrderElementsOf(featureIds);
        assertThat(selectionStatements).isEqualTo(single);
        assertThat(selectionStatements.stream().filter(sql -> sql.startsWith("SELECT"))).hasSize(3);
        assertThat(selectionStatements.stream().filter(sql -> sql.startsWith("INSERT"))).hasSize(3);
    }

    @Test
    void migrationPreservesLegacyPricesAndArchivedVisibility() {
        var migration = Flyway.configure().dataSource(dataSource).schemas("legacy_plans");
        migration.target("11").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                WITH account AS (
                    INSERT INTO legacy_plans.users
                        (email, password_hash, display_name, role, status, credentials_changed_at, created_at, updated_at)
                    VALUES ('legacy@example.test', 'hash', 'Legacy coach', 'COACH', 'ACTIVE', now(), now(), now())
                    RETURNING id
                ), coach AS (
                    INSERT INTO legacy_plans.coach_profiles
                        (user_id, display_name, bio, specialties, experience_years, certifications, result_image_urls,
                         status, created_at, updated_at)
                    SELECT id, 'Legacy coach', 'Synthetic biography', '[]', 0, '[]', '[]', 'APPROVED', now(), now()
                    FROM account RETURNING user_id
                )
                INSERT INTO legacy_plans.coach_plans
                    (coach_user_id, title, price_rial, compare_at_price_rial, duration_days, is_active,
                     created_at, updated_at, version)
                SELECT coach.user_id, plan.title, plan.price, plan.price + 10000, 30, plan.active, now(), now(), 0
                FROM coach CROSS JOIN (VALUES ('Archived', 100000, false), ('Public', 200000, true)) AS plan(title, price, active)
                """);

        migration.target("12").load().migrate();

        assertThat(jdbc.queryForList("""
                SELECT title, price, compare_at_price, currency, is_active, is_archived
                FROM legacy_plans.coach_plans ORDER BY title
                """)).containsExactly(
                Map.of("title", "Archived", "price", 100000L, "compare_at_price", 110000L,
                        "currency", "IRR", "is_active", true, "is_archived", true),
                Map.of("title", "Public", "price", 200000L, "compare_at_price", 210000L,
                        "currency", "IRR", "is_active", true, "is_archived", false));
    }

    private void setActive(Long planId, boolean active) {
        new JdbcTemplate(dataSource).update("UPDATE coach_plans SET is_active = ? WHERE id = ?", active, planId);
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
        long planId = inTx(() -> service.create(coachA, plan("Season", 100000, "WORKOUT"))).planId();
        setActive(planId, true);
        inTx(() -> { service.archive(coachA, planId); return null; });
        assertThatThrownBy(() -> inTx(() -> service.detail(planId)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(404));
    }
}
