package ir.vigoapp.user;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import net.logstash.logback.encoder.LogstashEncoder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    private List<PlanFieldValue> savedAnswers(ManagedCoachPlan plan) {
        return plan.fieldValues().stream().map(field -> new PlanFieldValue(field.fieldId(), field.value())).toList();
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
        assertThat(savedAnswers(created)).isEqualTo(required);

        var fields = new ArrayList<>(required);
        fields.add(new PlanFieldValue(field.id(), json.readTree("14")));
        fields.sort(Comparator.comparingLong(PlanFieldValue::fieldId));
        var edit = json.readTree("""
                {"features":[{"featureId":%d,"fields":[{"fieldId":%d,"value":14}]}]}
                """.formatted(feature.id(), field.id()));
        var updated = service.patch(coachA, planId, edit);
        assertThat(updated.featureIds()).containsExactly(feature.id());
        assertThat(savedAnswers(updated)).isEqualTo(fields);

        request.remove("featureIds");
        request.set("fieldValues", json.valueToTree(fields));
        assertThatThrownBy(() -> service.create(coachA, request))
                .isInstanceOfSatisfying(PlanValidationException.class, e -> assertThat(e.issues())
                        .extracting(PlanValidationIssue::code).containsExactly("FEATURE_NOT_SELECTED"));
        assertThat(service.own(coachA)).singleElement()
                .satisfies(view -> assertThat(savedAnswers(view)).isEqualTo(fields));
        var cleared = service.patch(coachA, planId, json.readTree("""
                {"features":[{"featureId":%d,"fields":null}]}
                """.formatted(feature.id())));
        assertThat(cleared.featureIds()).isEmpty();
        assertThat(savedAnswers(cleared)).isEqualTo(required);
    }

    @Test
    void managementResponsesIncludeSavedFeatureAndFieldLabels() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new CoachPlanManagementController(service))
                .setControllerAdvice(new Errors()).build();
        var coach = new TestingAuthenticationToken(Long.toString(coachA), null, "ROLE_COACH");
        var options = formOptions();
        var feature = options.features().stream().filter(item -> item.code().equals("PERIODIC_PLAN_REVIEW"))
                .findFirst().orElseThrow();
        var field = catalog.findFeatureDetails(feature.id()).orElseThrow().fields().getFirst();
        var level = options.generalFields().stream().filter(item -> item.code().equals("PLAN_LEVEL"))
                .findFirst().orElseThrow();
        var request = plan("Workout plan", 100000, "WORKOUT");
        request.putArray("featureIds").add(feature.id());
        request.withArray("fieldValues").addObject().put("fieldId", field.id()).put("value", 14);

        String path = "/api/v1/coaches/me/plans";
        var created = mvc.perform(post(path).principal(coach).contentType("application/json").content(request.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var plan = json.readTree(created.getContentAsByteArray()).path("data");
        assertThat(plan.path("featureIds").get(0).longValue()).isEqualTo(feature.id());
        var savedFeature = plan.path("features").get(0);
        assertThat(savedFeature.path("id").longValue()).isEqualTo(feature.id());
        assertThat(savedFeature.path("code").stringValue()).isEqualTo(feature.code());
        assertThat(savedFeature.path("label").stringValue()).isEqualTo(feature.label());
        assertThat(plan.path("fieldValues").values()).anySatisfy(answer -> {
            assertThat(answer.path("fieldId").longValue()).isEqualTo(field.id());
            assertThat(answer.path("featureId").longValue()).isEqualTo(feature.id());
            assertThat(answer.path("code").stringValue()).isEqualTo(field.code());
            assertThat(answer.path("label").stringValue()).isEqualTo(field.label());
            assertThat(answer.path("value").intValue()).isEqualTo(14);
        }).anySatisfy(answer -> {
            assertThat(answer.path("fieldId").longValue()).isEqualTo(level.id());
            assertThat(answer.path("featureId").isNull()).isTrue();
            assertThat(answer.path("code").stringValue()).isEqualTo(level.code());
            assertThat(answer.path("label").stringValue()).isEqualTo(level.label());
            assertThat(answer.at("/selectedOptions/0/code").stringValue()).isEqualTo("BEGINNER");
            assertThat(answer.at("/selectedOptions/0/label").stringValue()).isNotBlank();
        });

        String planPath = path + "/" + plan.path("planId").longValue();
        mvc.perform(put(planPath).principal(coach).contentType("application/json").content(request.toString()))
                .andExpect(status().isMethodNotAllowed());
        var patched = mvc.perform(patch(planPath).principal(coach).contentType("application/json").content("{\"price\":120000}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var listed = mvc.perform(get(path).principal(coach)).andExpect(status().isOk()).andReturn().getResponse();
        for (var view : List.of(json.readTree(patched.getContentAsByteArray()).path("data"),
                json.readTree(listed.getContentAsByteArray()).at("/data/0"))) {
            assertThat(view.path("features")).isEqualTo(plan.path("features"));
            assertThat(view.path("fieldValues")).isEqualTo(plan.path("fieldValues"));
        }
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

        var updated = inTx(() -> service.patch(coachA, planId,
                json.readTree("{\"includes\":[{\"code\":\"VIDEO_REVIEW\"}]}")));
        assertThat(updated.includes()).containsExactly("VIDEO_REVIEW");
    }

    @Test
    void anotherCoachCannotEditArchiveOrRestoreSomeoneElsesPlan() {
        long planId = inTx(() -> service.create(coachA, plan("Mine", 100000, "WORKOUT"))).planId();
        assertThatThrownBy(() -> inTx(() -> service.patch(coachB, planId, json.createObjectNode().put("title", "Hijack"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(403));
        assertThatThrownBy(() -> inTx(() -> { service.archive(coachB, planId); return null; }))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(403));
        assertThatThrownBy(() -> inTx(() -> { service.unarchive(coachB, planId); return null; }))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(403));
    }

    @Test
    void archivedPlanLeavesMarketplaceAndCannotBeEdited() {
        long planId = inTx(() -> service.create(coachA, plan("Season", 100000, "WORKOUT"))).planId();
        assertThat(inTx(() -> service.marketplace(0, 20, null)).content()).hasSize(1);
        inTx(() -> { service.archive(coachA, planId); return null; });
        assertThat(inTx(() -> service.marketplace(0, 20, null)).content()).isEmpty();
        assertThatThrownBy(() -> inTx(() -> service.patch(coachA, planId, json.createObjectNode().put("title", "Season 2"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code).isEqualTo("PLAN_ARCHIVED"));
        assertThatThrownBy(() -> inTx(() -> { service.archive(coachA, planId); return null; }))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(409));
    }

    @Test
    void storedActivationAndArchivingIndependentlyControlVisibilityAndPurchases() {
        var created = inTx(() -> service.create(coachA, plan("Season", 100000, "WORKOUT")));
        long planId = created.planId();
        assertThat(created.isActive()).isTrue();
        assertThat(created.isArchived()).isFalse();
        assertThat(created.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(service.marketplace(0, 20, null).content()).hasSize(1);
        assertThat(service.requirePurchasable(planId).getId()).isEqualTo(planId);

        setStoredActivation(planId, false);
        assertThatThrownBy(() -> service.patch(coachA, planId, json.createObjectNode().put("isActive", true)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status).isEqualTo(409);
                    assertThat(e.code).isEqualTo("PLAN_INACTIVE");
                });
        assertThatThrownBy(() -> service.requirePurchasable(planId))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code).isEqualTo("PLAN_INACTIVE"));

        setStoredActivation(planId, true);
        assertThat(inTx(() -> service.marketplace(0, 20, coachA)).content()).hasSize(1);
        inTx(() -> { service.archive(coachA, planId); return null; });
        assertThat(service.own(coachA).getFirst()).extracting(ManagedCoachPlan::isActive, ManagedCoachPlan::isArchived)
                .containsExactly(true, true);
        assertThat(inTx(() -> service.marketplace(0, 20, coachA)).content()).isEmpty();
        assertThatThrownBy(() -> inTx(() -> service.requirePurchasable(planId)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code).isEqualTo("PLAN_ARCHIVED"));

        setStoredActivation(planId, false);
        inTx(() -> { service.unarchive(coachA, planId); return null; });
        assertThat(service.own(coachA).getFirst()).extracting(ManagedCoachPlan::isActive, ManagedCoachPlan::isArchived)
                .containsExactly(false, false);
        assertThat(inTx(() -> service.marketplace(0, 20, null)).content()).isEmpty();

        setStoredActivation(planId, true);
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
        assertThat(stored.compareAtPrice()).isEqualTo(10000);
        assertThat(stored.firstDeliveryDays()).isNull();

        assertThatThrownBy(() -> service.patch(coachA, created.planId(), json.createObjectNode().put("currency", "USD")))
                .isInstanceOfSatisfying(PlanValidationException.class, e -> assertThat(e.issues()).containsExactly(
                        PlanValidationIssue.at("currency", "READ_ONLY_FIELD")));
        // Historical amounts retain their denomination when the coach edits a plan.
        new JdbcTemplate(dataSource).update("UPDATE coach_plans SET currency='USD', price=25, compare_at_price=NULL WHERE id=?", created.planId());
        var updated = service.patch(coachA, created.planId(), json.createObjectNode().put("price", 30));
        assertThat(updated.price()).isEqualTo(30);
        assertThat(updated.compareAtPrice()).isEqualTo(30);
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
        request.put("currency", "USD").put("compareAtPrice", 15000);
        request.putArray("featureIds").add(999999);
        request.putArray("fieldValues").addObject().put("fieldId", sessions).put("value", 8);
        var invalid = mvc.perform(post(url).principal(coach).contentType("application/json").content(request.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn().getResponse();
        var issues = json.treeToValue(json.readTree(invalid.getContentAsByteArray()).path("data"), PlanValidationIssue[].class);
        assertThat(issues).contains(
                PlanValidationIssue.at("currency", "UNKNOWN_FIELD"),
                PlanValidationIssue.at("compareAtPrice", "UNKNOWN_FIELD"),
                PlanValidationIssue.at("includes[1]", "DUPLICATE_CODE"),
                PlanValidationIssue.at("includes[2]", "UNKNOWN_CODE"),
                PlanValidationIssue.forFeature("featureIds[0]", "UNKNOWN_ID", 999999),
                PlanValidationIssue.forField("fieldValues", "REQUIRED_FIELD_MISSING", level),
                new PlanValidationIssue("fieldValues[0].value", "INTEGER_TOO_LARGE", sessions, null, Map.of("max", 7)),
                new PlanValidationIssue("price", "INTEGER_TOO_SMALL", null, null, Map.of("min", 10000)));
        assertThat(service.own(coachA)).isEmpty();

        var corrected = plan("تمرین قدرتی", 10000, "WORKOUT");
        corrected.put("summary", "  خلاصه  ").put("firstDeliveryDays", 0)
                .put("description", "توضیحات پلن");
        mvc.perform(post(url).principal(coach).contentType("application/json").content(corrected.toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.isActive").value(true)).andExpect(jsonPath("$.data.isArchived").value(false))
                .andExpect(jsonPath("$.data.currency").value("IRR")).andExpect(jsonPath("$.data.planId").isNumber())
                .andExpect(jsonPath("$.data.price").value(10000)).andExpect(jsonPath("$.data.compareAtPrice").value(10000));
        var saved = service.own(coachA).getFirst();
        assertThat(saved.summary()).isEqualTo("خلاصه");
        assertThat(saved.description()).isEqualTo("توضیحات پلن");
        assertThat(saved.firstDeliveryDays()).isZero();
        assertThat(saved.compareAtPrice()).isEqualTo(10000);

        var edit = json.createObjectNode().put("price", 11000);
        String planUrl = url + "/" + saved.planId();
        mvc.perform(patch(planUrl).principal(coach).contentType("application/json").content(edit.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.compareAtPrice").value(11000));
        edit.put("price", 12000).put("compareAtPrice", 15000);
        mvc.perform(patch(planUrl).principal(coach).contentType("application/json").content(edit.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.price").value(12000))
                .andExpect(jsonPath("$.data.compareAtPrice").value(15000));
        service.patch(coachA, saved.planId(),
                json.createObjectNode().putNull("summary").putNull("compareAtPrice").putNull("firstDeliveryDays"));
        var cleared = service.detail(saved.planId());
        assertThat(cleared.summary()).isNull();
        assertThat(cleared.compareAtPrice()).isNull();
        assertThat(cleared.firstDeliveryDays()).isNull();
    }

    @Test
    void patchRetainsOmittedPropertiesAndRemovesOnlyExplicitlyDeselectedFeatures() throws Exception {
        var options = formOptions().features();
        var feature = options.stream().filter(f -> f.code().equals("PERIODIC_PLAN_REVIEW"))
                .findFirst().orElseThrow();
        var answerId = catalog.findFeatureDetails(feature.id()).orElseThrow().fields().getFirst().id();
        long tracking = options.stream().filter(f -> f.code().equals("PROGRESS_TRACKING")).findFirst().orElseThrow().id();
        long frequency = catalog.findFeatureDetails(tracking).orElseThrow().fields().getFirst().id();
        var request = plan("Workout plan", 100000, "WORKOUT");
        request.put("description", "Original description");
        request.putArray("featureIds").add(feature.id()).add(tracking);
        request.withArray("fieldValues").addObject().put("fieldId", answerId).put("value", 14);
        request.withArray("fieldValues").addObject().put("fieldId", frequency).put("value", "WEEKLY");
        var created = service.create(coachA, request);

        var mvc = MockMvcBuilders.standaloneSetup(new CoachPlanManagementController(service))
                .setControllerAdvice(new Errors()).build();
        var coach = new TestingAuthenticationToken(Long.toString(coachA), null, "ROLE_COACH");
        String url = "/api/v1/coaches/me/plans/" + created.planId();
        mvc.perform(patch(url).principal(coach).contentType("application/json")
                        .content("{\"price\":110000,\"compareAtPrice\":110000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.price").value(110000))
                .andExpect(jsonPath("$.data.compareAtPrice").value(110000));
        mvc.perform(patch(url).principal(coach).contentType("application/json").content("{\"compareAtPrice\":150000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.price").value(110000))
                .andExpect(jsonPath("$.data.compareAtPrice").value(150000));
        var summaryEdit = service.patch(coachA, created.planId(), json.readTree("{\"summary\":\"New summary\"}"));
        assertThat(summaryEdit.price()).isEqualTo(110000);
        assertThat(summaryEdit.compareAtPrice()).isEqualTo(150000);
        mvc.perform(patch(url)
                        .principal(coach).contentType("application/json").content("{\"price\":120000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.price").value(120000))
                .andExpect(jsonPath("$.data.compareAtPrice").value(120000));

        var edited = service.detail(created.planId());
        assertThat(edited.description()).isEqualTo(created.description());
        assertThat(edited.compareAtPrice()).isEqualTo(120000);
        assertThat(edited.includes()).isEqualTo(created.includes());
        assertThat(edited.featureIds()).isEqualTo(created.featureIds());
        assertThat(edited.fieldValues()).isEqualTo(created.fieldValues());

        var emptyFeatures = service.patch(coachA, created.planId(), json.readTree("{\"features\":[]}"));
        assertThat(emptyFeatures.featureIds()).isEqualTo(edited.featureIds());
        assertThat(emptyFeatures.fieldValues()).isEqualTo(edited.fieldValues());

        var featureEdit = json.readTree("""
                {"features":[{"featureId":%d,"fields":[{"fieldId":%d,"value":21}]}]}
                """.formatted(feature.id(), answerId));
        var reviewed = service.patch(coachA, created.planId(), featureEdit);
        var trackingAnswer = new PlanFieldValue(frequency, json.readTree("\"WEEKLY\""));
        assertThat(reviewed.featureIds()).isEqualTo(edited.featureIds());
        assertThat(savedAnswers(reviewed)).contains(trackingAnswer, new PlanFieldValue(answerId, json.readTree("21")));

        var invalid = json.readTree("""
                {"price":160000,"compareAtPrice":150000,"features":[{"featureId":%d,"fields":null}]}
                """.formatted(feature.id()));
        assertThatThrownBy(() -> service.patch(coachA, created.planId(), invalid))
                .isInstanceOfSatisfying(PlanValidationException.class, e -> assertThat(e.issues())
                        .extracting(PlanValidationIssue::code).containsExactly("COMPARE_AT_PRICE_NOT_GREATER_THAN_PRICE"));
        assertThat(service.detail(created.planId())).isEqualTo(reviewed);

        var clear = json.readTree("""
                {"compareAtPrice":null,"description":null,"features":[{"featureId":%d,"fields":null}]}
                """.formatted(feature.id()));
        var cleared = service.patch(coachA, created.planId(), clear);
        assertThat(cleared.description()).isNull();
        assertThat(cleared.compareAtPrice()).isNull();
        assertThat(cleared.featureIds()).containsExactly(tracking);
        assertThat(savedAnswers(cleared)).hasSize(requiredPlanFields().size() + 1)
                .contains(trackingAnswer).containsAll(requiredPlanFields());
        var retried = service.patch(coachA, created.planId(), clear);
        assertThat(retried.featureIds()).isEqualTo(cleared.featureIds());
        assertThat(retried.fieldValues()).isEqualTo(cleared.fieldValues());
    }

    @Test
    void writesEmitCorrelatedTypedJsonForCommittedChangesAndFailures() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new CoachPlanManagementController(service))
                .setControllerAdvice(new Errors()).build();
        var coach = new TestingAuthenticationToken(Long.toString(coachA), null, "ROLE_COACH");
        String url = "/api/v1/coaches/me/plans";
        String traceId = "0123456789abcdef0123456789abcdef";
        var description = "First line\nSecond line";
        var feature = formOptions().features().stream().filter(f -> f.code().equals("PERIODIC_PLAN_REVIEW"))
                .findFirst().orElseThrow();
        long answerId = catalog.findFeatureDetails(feature.id()).orElseThrow().fields().getFirst().id();
        var request = plan("Workout plan", 100000, "WORKOUT").put("description", description);
        request.putArray("featureIds").add(feature.id());
        request.withArray("fieldValues").addObject().put("fieldId", answerId).put("value", 14);

        var logger = (Logger) LoggerFactory.getLogger("ir.vigoapp.user");
        var appender = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        var encoder = new LogstashEncoder();
        encoder.setContext(logger.getLoggerContext());
        encoder.start();
        appender.start();
        logger.addAppender(appender);
        try (var trace = MDC.putCloseable("traceId", traceId)) {
            var response = mvc.perform(post(url).principal(coach).contentType("application/json").content(request.toString()))
                    .andExpect(status().isCreated()).andReturn().getResponse();
            long id = json.readTree(response.getContentAsByteArray()).path("data").path("planId").longValue();
            String planPath = url + "/" + id;
            mvc.perform(get(url).principal(coach)).andExpect(status().isOk());
            mvc.perform(patch(planPath).principal(coach).contentType("application/json")
                            .content("""
                                    {"price":120000,"description":null,"features":[{"featureId":%d,"fields":null}]}
                                    """.formatted(feature.id())))
                    .andExpect(status().isOk());
            mvc.perform(patch(planPath).principal(coach).contentType("application/json").content("{\"price\":1}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(patch(planPath).principal(coach).contentType("application/json").content("{\"private-input\":"))
                    .andExpect(status().isBadRequest());
            mvc.perform(patch(planPath).principal(coach).contentType("application/json")
                            .content("{\"title\":\"Updated plan\"}"))
                    .andExpect(status().isOk());
            mvc.perform(patch(planPath).principal(coach).contentType("application/json").content("{}"))
                    .andExpect(status().isOk());

            int logged = appender.list.size();
            tx.executeWithoutResult(transaction -> {
                service.patch(coachA, id, json.readTree("{\"price\":130000}"));
                assertThat(appender.list).hasSize(logged);
                transaction.setRollbackOnly();
            });
            assertThat(service.detail(id).price()).isEqualTo(120000);

            mvc.perform(delete(planPath).principal(coach)).andExpect(status().isOk());
            mvc.perform(post(planPath + "/unarchive").principal(coach)).andExpect(status().isOk());

            var events = appender.list.stream().map(encoder::encode).map(json::readTree)
                    .filter(event -> event.path("event").asString("").startsWith("coach_plan_write_"))
                    .toList();
            assertThat(events).extracting(event -> event.path("result").stringValue()).containsExactly(
                    "REQUESTED", "SUCCESS", "REQUESTED", "SUCCESS", "REQUESTED", "REJECTED", "REJECTED",
                    "REQUESTED", "SUCCESS", "REQUESTED", "NO_CHANGE", "FAILURE",
                    "REQUESTED", "SUCCESS", "REQUESTED", "SUCCESS");
            assertThat(events).allSatisfy(event -> assertThat(event.path("traceId").stringValue()).isEqualTo(traceId));
            assertThat(events.get(0).path("coach_id").isIntegralNumber()).isTrue();
            assertThat(events.get(1).path("plan_id").longValue()).isEqualTo(id);
            assertThat(events.get(1).at("/changes/description").stringValue()).isEqualTo(description);
            assertThat(events.get(1).at("/changes/field_values").values()).anySatisfy(answer -> {
                assertThat(answer.path("field_id").longValue()).isEqualTo(answerId);
                assertThat(answer.path("value_integer").isIntegralNumber()).isTrue();
                assertThat(answer.path("value_integer").longValue()).isEqualTo(14);
            });
            assertThat(events.get(1).at("/changes/field_values").values()).anySatisfy(answer ->
                    assertThat(answer.path("value_options")).isEqualTo(json.readTree("[\"BEGINNER\",\"INTERMEDIATE\"]")));
            var patch = events.get(3).path("changes");
            assertThat(patch.propertyNames()).containsExactlyInAnyOrder("price", "compare_at_price", "description", "feature_ids", "field_values");
            assertThat(patch.path("price").longValue()).isEqualTo(120000);
            assertThat(patch.path("compare_at_price").longValue()).isEqualTo(120000);
            assertThat(patch.path("description").isNull()).isTrue();
            assertThat(patch.path("feature_ids").isEmpty()).isTrue();
            assertThat(patch.path("field_values").get(0)).isEqualTo(json.readTree("{\"field_id\":" + answerId + ",\"removed\":true}"));
            assertThat(events.get(5).path("validation_codes").get(0).stringValue()).isEqualTo("INTEGER_TOO_SMALL");
            assertThat(events.get(6).path("stage").stringValue()).isEqualTo("request_binding");
            assertThat(events.get(6).path("plan_id").longValue()).isEqualTo(id);
            assertThat(events.get(6).toString()).doesNotContain("private-input");
            assertThat(events.get(8).path("changes")).isEqualTo(json.readTree("{\"title\":\"Updated plan\"}"));
            assertThat(events.get(10).path("actor_user_id").longValue()).isEqualTo(coachA);
            assertThat(events.get(10).path("coach_id").longValue()).isEqualTo(coachA);
            assertThat(events.get(11).path("reason").stringValue()).isEqualTo("transaction_rolled_back");
            assertThat(events.get(11).has("changes")).isFalse();
            assertThat(events.get(13).at("/changes/is_archived")).isEqualTo(json.readTree("true"));
            assertThat(events.get(15).at("/changes/is_archived")).isEqualTo(json.readTree("false"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            encoder.stop();
        }
    }

    @Test
    void nestedPatchResolvesScopesAndMergesAnswersByFieldId() {
        var id = service.create(coachA, plan("Editable", 120000, "WORKOUT")).planId();
        var options = formOptions();
        long sessions = options.generalFields().stream().filter(f -> f.code().equals("WEEKLY_TRAINING_SESSIONS"))
                .findFirst().orElseThrow().id();
        long review = options.features().stream().filter(f -> f.code().equals("PERIODIC_PLAN_REVIEW"))
                .findFirst().orElseThrow().id();
        long interval = catalog.findFeatureDetails(review).orElseThrow().fields().getFirst().id();
        var changes = json.readTree("""
                {"includes":[{"code":"NUTRITION"}],"fields":[{"fieldId":%d,"value":4}],
                 "features":[{"featureId":%d,"fields":[{"fieldId":%d,"value":10}]}]}
                """.formatted(sessions, review, interval));
        var updated = service.patch(coachA, id, changes);
        assertThat(updated.includes()).containsExactly("NUTRITION");
        assertThat(updated.featureIds()).containsExactly(review);
        assertThat(savedAnswers(updated)).hasSize(options.generalFields().size() + 1)
                .contains(new PlanFieldValue(sessions, json.readTree("4")), new PlanFieldValue(interval, json.readTree("10")));
        assertThat(service.detail(id).fieldValues()).isEqualTo(updated.fieldValues());

        var invalid = json.readTree("""
                {"summary":"invalid edit","price":null,"isActive":false,"planId":777,"includes":null,
                 "fields":[{"fieldId":%d,"value":null}],
                 "features":[{"featureId":%d,"fields":[{"fieldId":%d,"value":15}]}]}
                """.formatted(sessions, review, options.generalFields().getFirst().id()));
        assertThatThrownBy(() -> service.patch(coachA, id, invalid))
                .isInstanceOfSatisfying(PlanValidationException.class, e -> assertThat(e.issues())
                        .extracting(PlanValidationIssue::field, PlanValidationIssue::code).contains(
                                tuple("fields[0].value", "REQUIRED_FIELD_MISSING"),
                                tuple("features[0].fields[0].fieldId", "FIELD_SCOPE_MISMATCH"),
                                tuple("price", "REQUIRED_FIELD_MISSING"), tuple("includes", "REQUIRED_FIELD_MISSING"),
                                tuple("planId", "READ_ONLY_FIELD"), tuple("isActive", "READ_ONLY_FIELD")));
        assertThat(service.detail(id).summary()).isNull();
        assertThat(service.detail(id).fieldValues()).isEqualTo(updated.fieldValues());

        var removeAnswer = json.readTree("""
                {"features":[{"featureId":%d,"fields":[{"fieldId":%d,"value":null}]}]}
                """.formatted(review, interval));
        assertThatThrownBy(() -> service.patch(coachA, id, removeAnswer))
                .isInstanceOfSatisfying(PlanValidationException.class, e -> assertThat(e.issues()).containsExactly(
                        PlanValidationIssue.forField("features[0].fields[0].value", "REQUIRED_FIELD_MISSING", interval)));
        assertThat(service.detail(id)).isEqualTo(updated);
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
        assertThat(selectionStatements.stream().filter(sql -> sql.startsWith("SELECT"))).hasSize(5);
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

        migration.target("14").load().migrate();

        assertThat(jdbc.queryForList("""
                SELECT title, price, compare_at_price, currency, is_active, is_archived
                FROM legacy_plans.coach_plans ORDER BY title
                """)).containsExactly(
                Map.of("title", "Archived", "price", 100000L, "compare_at_price", 110000L,
                        "currency", "IRR", "is_active", true, "is_archived", true),
                Map.of("title", "Public", "price", 200000L, "compare_at_price", 210000L,
                        "currency", "IRR", "is_active", true, "is_archived", false));
        assertThat(jdbc.update("UPDATE legacy_plans.coach_plans SET compare_at_price = price WHERE title = 'Public'"))
                .isEqualTo(1);
    }

    private void setStoredActivation(long planId, boolean active) {
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
        inTx(() -> { service.archive(coachA, planId); return null; });
        assertThatThrownBy(() -> inTx(() -> service.detail(planId)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status).isEqualTo(404));
    }
}
