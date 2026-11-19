package ir.vigoapp.user;

import ir.vigoapp.user.api.Errors;
import ir.vigoapp.user.api.UserController;
import ir.vigoapp.user.api.plan.PublicCoachPlanController;
import ir.vigoapp.user.dto.plan.PlanFieldValue;
import ir.vigoapp.user.entity.CoachProfile;
import ir.vigoapp.user.entity.UserAccount;
import ir.vigoapp.user.entity.plan.CoachPlan;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.outbox.OutboxWriter;
import ir.vigoapp.user.repository.CoachPlanIncludeRepository;
import ir.vigoapp.user.repository.CoachPlanRepository;
import ir.vigoapp.user.repository.CoachRepository;
import ir.vigoapp.user.repository.UserRepository;
import ir.vigoapp.user.repository.plan.CoachPlanSelectionRepository;
import ir.vigoapp.user.service.ProfileService;
import ir.vigoapp.user.service.plan.PublicCoachPlanService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Hibernate and temporary PostgreSQL; no application server, Kafka or Docker required. */
@SpringJUnitConfig(CoachLookupRegressionTest.Config.class)
class CoachLookupRegressionTest {
 @Configuration
 @EnableJpaRepositories(basePackageClasses=CoachRepository.class)
 @EnableTransactionManagement
 static class Config {
  @Bean(destroyMethod="close") EmbeddedPostgres postgres() throws IOException { return EmbeddedPostgres.builder().start(); }
  @Bean DataSource dataSource(EmbeddedPostgres postgres) { return postgres.getPostgresDatabase(); }
  @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
   var factory=new LocalContainerEntityManagerFactoryBean();
   factory.setDataSource(dataSource);factory.setPackagesToScan(UserAccount.class.getPackageName());
   factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
   factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create-drop"));return factory;
  }
  @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
  @Bean EntityManager entityManager(EntityManagerFactory factory) { return SharedEntityManagerCreator.createSharedEntityManager(factory); }
  @Bean ObjectMapper objectMapper() { return JsonMapper.builder().findAndAddModules().build(); }
  @Bean CoachPlanSelectionRepository selections(DataSource dataSource,ObjectMapper json) {
   return new CoachPlanSelectionRepository(JdbcClient.create(dataSource),json);
  }
  @Bean PublicCoachPlanService publicPlans(CoachPlanRepository plans,CoachPlanIncludeRepository includes,
    CoachPlanSelectionRepository selections) {
   return new PublicCoachPlanService(plans,includes,selections);
  }
  @Bean ProfileService profiles(UserRepository users,CoachRepository coaches,ObjectMapper json,CoachPlanRepository plans,
    PublicCoachPlanService publicPlans) {
   return new ProfileService(users,coaches,mock(OutboxWriter.class),json,plans,publicPlans);
  }
 }
 @Autowired ProfileService profiles;
 @Autowired UserRepository users;
 @Autowired CoachRepository coaches;
 @Autowired @org.springframework.beans.factory.annotation.Qualifier("entityManager") EntityManager em;
 @Autowired PlatformTransactionManager manager;
 @Autowired PublicCoachPlanService publicPlans;
 @Autowired CoachPlanSelectionRepository selections;
 @Autowired DataSource dataSource;
 @Autowired ObjectMapper json;

 @Test void httpPublicListIdResolvesDetailsAndApprovedDecisionsReturn409() throws Exception {
  var id=seed(CoachStatus.APPROVED);
  seedPlan(id,true,true,5,null,"USD");
  seedPlan(id,false,false,6,null,"USD");
  seedPlan(id,true,false,120,130L,"USD");
  seedPlan(id,true,false,100,150L,"USD");
  seedPlan(id,true,false,100,null,"USD");
  seedPlan(id,true,false,50,null,"EUR");
  var archivedOnly=seed(CoachStatus.APPROVED);
  seedPlan(archivedOnly,true,true,80,95L,"USD");

  // Exercise controller routing and error translation with the real transactional service.
  // JWT authorization itself is covered separately by JwtSecurityTest.
  var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders
   .standaloneSetup(new ir.vigoapp.user.api.UserController(profiles))
   .setControllerAdvice(new ir.vigoapp.user.api.Errors()).build();
  var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/coaches"))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
  var tree=JsonMapper.builder().build().readTree(response.getResponse().getContentAsString());
  var content=new ArrayList<JsonNode>();
  tree.path("data").path("content").forEach(content::add);
  var coachId=content.stream().filter(coach->("Coach "+id).equals(coach.path("displayName").asText()))
   .findFirst().orElseThrow().path("coachId").longValue();
  assertThat(coachId).isEqualTo(id);
  assertThat(content).filteredOn(coach->coach.path("coachId").longValue()==id)
   .singleElement().satisfies(coach->{
    assertThat(coach.has("plans")).isFalse();
    assertThat(coach.path("has_active_plan").asBoolean()).isTrue();
    assertThat(coach.path("price").longValue()).isEqualTo(100);
    assertThat(coach.path("compare_at_price").longValue()).isEqualTo(150);
    assertThat(coach.path("currency").asText()).isEqualTo("USD");
   });
  assertThat(content).filteredOn(coach->coach.path("coachId").longValue()==archivedOnly)
   .singleElement().satisfies(coach->{
    assertThat(coach.path("has_active_plan").isBoolean()).isTrue();
    assertThat(coach.path("has_active_plan").asBoolean()).isFalse();
    assertThat(coach.path("price").isNull()).isTrue();
    assertThat(coach.path("compare_at_price").isNull()).isTrue();
    assertThat(coach.path("currency").isNull()).isTrue();
   });
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/coaches/"+coachId))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.coachId").value(coachId))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.has_active_plan").value(true))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.price").value(100))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.compare_at_price").value(150))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.currency").value("USD"));
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/coaches/"+coachId))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  var actor=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("1",null);
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/coaches/"+coachId+"/approve").principal(actor))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("INVALID_STATE"));
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/coaches/"+coachId+"/reject").principal(actor)
   .contentType("application/json").content("{\"reason\":\"Already approved\"}"))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("INVALID_STATE"));
 }
 @Test void publicCoachShowsOnlyThreeOrderedLabelsPerPlanAndDetailsShowTheSavedSelections() throws Exception {
  long coachId=seed(CoachStatus.APPROVED);
  long foreign=seedPlan(coachId,true,false,25,null,"USD");
  long featured=seedPlan(coachId,true,false,100000,135000L,"IRR");
  long simple=seedPlan(coachId,true,false,70000,null,"IRR");
  seedPlan(coachId,false,false,40000,null,"IRR");
  seedPlan(coachId,true,true,50000,null,"IRR");

  var jdbc=new JdbcTemplate(dataSource);
  jdbc.update("""
   UPDATE coach_plans SET title='Featured', summary='Plan summary', description='Plan description',
    display_order=5, first_delivery_days=3, is_recommended=true WHERE id=?
   """,featured);
  jdbc.update("UPDATE coach_plans SET display_order=10 WHERE id IN (?,?)",foreign,simple);
  long last=seedFeature(jdbc,"LAST",40);
  long tieA=seedFeature(jdbc,"TIE_A",20);
  long first=seedFeature(jdbc,"FIRST",10);
  long tieB=seedFeature(jdbc,"TIE_B",20);
  long count=seedField(jdbc,first,"SESSION_COUNT","INTEGER","{}");
  long level=seedField(jdbc,null,"PLAN_LEVEL","MULTI_SELECT",
   """
   {"options":[{"code":"BEGINNER","label":"Beginner"},{"code":"ADVANCED","label":"Advanced"}]}
   """);
  seedField(jdbc,first,"UNSET","INTEGER","{}");
  new TransactionTemplate(manager).executeWithoutResult(tx->{
   selections.replace(featured,List.of(last,tieB,tieA,first),List.of(
    new PlanFieldValue(count,json.readTree("3")),new PlanFieldValue(level,json.readTree("[\"BEGINNER\"]"))));
   selections.replace(foreign,List.of(last,tieB),List.of());
  });
  long workout=jdbc.queryForObject("""
   INSERT INTO plan_include_catalog (code,label,is_active,display_order,created_at,updated_at)
   VALUES ('WORKOUT','Workout',false,10,now(),now()) RETURNING id
   """,Long.class);
  jdbc.update("INSERT INTO coach_plan_includes (plan_id,include_id) VALUES (?,?)",featured,workout);

  var mvc=publicPlanMvc();
  String path="/api/v1/coaches/"+coachId;
  mvc.perform(get(path)).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.plans[*].id").value(contains((int)featured,(int)foreign,(int)simple)))
   .andExpect(jsonPath("$.data.plans[0].title").value("Featured"))
   .andExpect(jsonPath("$.data.plans[0].summary").value("Plan summary"))
   .andExpect(jsonPath("$.data.plans[0].price_rial").value(100000))
   .andExpect(jsonPath("$.data.plans[0].compare_at_price_rial").value(135000))
   .andExpect(jsonPath("$.data.plans[0].duration_days").value(30))
   .andExpect(jsonPath("$.data.plans[0].display_order").value(5))
   .andExpect(jsonPath("$.data.plans[0].is_recommended").value(true))
   .andExpect(jsonPath("$.data.plans[0].description").doesNotHaveJsonPath())
   .andExpect(jsonPath("$.data.plans[0].features[*].id").value(contains((int)first,(int)tieA,(int)tieB)))
   .andExpect(jsonPath("$.data.plans[0].features[0]").value(is(Map.of("id",(int)first,"code","FIRST","label","FIRST label"))))
   .andExpect(jsonPath("$.data.plans[1].features[*].code").value(contains("TIE_B","LAST")))
   .andExpect(jsonPath("$.data.plans[1].price").value(25))
   .andExpect(jsonPath("$.data.plans[1].currency").value("USD"))
   .andExpect(jsonPath("$.data.plans[1].price_rial").hasJsonPath())
   .andExpect(jsonPath("$.data.plans[1].price_rial").isEmpty())
   .andExpect(jsonPath("$.data.plans[2].features").isArray())
   .andExpect(jsonPath("$.data.plans[2].features").isEmpty());

  var detail=mvc.perform(get(path+"/plan/"+featured)).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.id").value(featured))
   .andExpect(jsonPath("$.data.coach_id").value(coachId))
   .andExpect(jsonPath("$.data.description").value("Plan description"))
   .andExpect(jsonPath("$.data.first_delivery_days").value(3))
   .andExpect(jsonPath("$.data.features[*].code").value(contains("FIRST","TIE_A","TIE_B","LAST")))
   .andExpect(jsonPath("$.data.features[0].fields[*].code").value(contains("SESSION_COUNT")))
   .andExpect(jsonPath("$.data.features[0].fields[0].value").value(3))
   .andExpect(jsonPath("$.data.features[1].fields").isArray())
   .andExpect(jsonPath("$.data.features[1].fields").isEmpty())
   .andExpect(jsonPath("$.data.general_fields[0].value[0]").value("BEGINNER"))
   .andExpect(jsonPath("$.data.general_fields[0].selected_options[*].label").value(contains("Beginner")))
   .andExpect(jsonPath("$.data.includes[0]").value(is(Map.of("id",(int)workout,"code","WORKOUT","label","Workout"))))
   .andReturn().getResponse().getContentAsString();
  assertThat(detail).doesNotContain("\"created_at\"","\"updated_at\"","\"createdAt\"","\"updatedAt\"","\"version\"",
   "\"is_active\"","\"is_archived\"","\"validation_rules\"");
 }
 @Test void publicPlanDetailHidesUnavailablePlansAndRequiresTheOwningPublicCoach() throws Exception {
  long coach=seed(CoachStatus.APPROVED);
  long otherCoach=seed(CoachStatus.APPROVED);
  long pending=seed(CoachStatus.PENDING);
  long suspended=seed(CoachStatus.APPROVED);
  long athlete=seed(CoachStatus.APPROVED);
  var jdbc=new JdbcTemplate(dataSource);
  jdbc.update("UPDATE users SET status='SUSPENDED' WHERE id=?",suspended);
  jdbc.update("UPDATE users SET role='ATHLETE' WHERE id=?",athlete);
  var hidden=List.of(
   Map.entry(coach,seedPlan(otherCoach,true,false,100000,null,"IRR")),
   Map.entry(coach,seedPlan(coach,false,false,100000,null,"IRR")),
   Map.entry(coach,seedPlan(coach,true,true,100000,null,"IRR")),
   Map.entry(pending,seedPlan(pending,true,false,100000,null,"IRR")),
   Map.entry(suspended,seedPlan(suspended,true,false,100000,null,"IRR")),
   Map.entry(athlete,seedPlan(athlete,true,false,100000,null,"IRR")),
   Map.entry(coach,Long.MAX_VALUE));
  var mvc=publicPlanMvc();
  for(var entry:hidden){
   mvc.perform(get("/api/v1/coaches/{coachId}/plan/{planId}",entry.getKey(),entry.getValue()))
    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
  mvc.perform(get("/api/v1/coaches/{coachId}",coach)).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.plans").isArray()).andExpect(jsonPath("$.data.plans").isEmpty());
 }
 @Test void publicListIdResolvesBothDetailsAndDecisionsReturnConflictNotNotFound() {
  var id=seed(CoachStatus.APPROVED);
  var returned=profiles.listPublicCoaches(0,100).content().stream()
   .filter(c->"Coach ".concat(id.toString()).equals(c.get("displayName"))).findFirst().orElseThrow();
  long coachId=(Long)returned.get("coachId");
  assertThat(coachId).isEqualTo(id);
  assertThat(profiles.publicCoach(coachId)).containsEntry("coachId",id).containsEntry("avatarUrl","https://example.test/avatar");
  assertThat(profiles.adminCoach(coachId)).containsEntry("coachId",id);
  assertConflict(()->profiles.decide(1L,coachId,true,null));
  assertConflict(()->profiles.decide(1L,coachId,false,"Not pending"));
 }
 @Test void pendingAdminListIdCanBeApproved() { assertPendingDecision(true); }
 @Test void pendingAdminListIdCanBeRejected() { assertPendingDecision(false); }
 private void assertPendingDecision(boolean approve) {
  var id=seed(CoachStatus.PENDING);
  var returned=profiles.listCoaches(0,100,CoachStatus.PENDING).content().stream()
   .filter(c->"Coach ".concat(id.toString()).equals(c.get("displayName"))).findFirst().orElseThrow();
  long coachId=(Long)returned.get("coachId");
  assertThat(coachId).isEqualTo(id);
  assertThat(profiles.adminCoach(coachId)).containsEntry("coachId",id);
  assertThat(profiles.decide(1L,coachId,approve,"Review reason"))
   .containsEntry("coachId",id).containsEntry("status",approve?CoachStatus.APPROVED:CoachStatus.REJECTED);
 }
 @Test void existingUserProxyMustNotChangePublicEligibilityOrReturnedId() {
  var id=seed(CoachStatus.APPROVED);
  new TransactionTemplate(manager).executeWithoutResult(tx->{
   var reference=em.getReference(UserAccount.class,id);
   assertThat(org.hibernate.Hibernate.isInitialized(reference)).isFalse();
   assertThat(profiles.publicCoach(id)).containsEntry("coachId",id).containsEntry("avatarUrl","https://example.test/avatar");
   assertThat(profiles.adminCoach(id)).containsEntry("coachId",id);
  });
 }
 @Test void existingUserProxyMustNotCorruptPublicListId() {
  var id=seed(CoachStatus.APPROVED);
  new TransactionTemplate(manager).executeWithoutResult(tx->{
   em.getReference(UserAccount.class,id);
   var returned=profiles.listPublicCoaches(0,100).content().stream()
    .filter(c->("Coach "+id).equals(c.get("displayName"))).findFirst().orElseThrow();
   assertThat(returned).containsEntry("coachId",id).containsEntry("avatarUrl","https://example.test/avatar");
   assertThat(profiles.publicCoach((Long)returned.get("coachId"))).containsEntry("coachId",id);
  });
 }
 @Test void decisionWithExistingUserProxyPersistsTheRealAccountRole() {
  var id=seed(CoachStatus.PENDING);
  new TransactionTemplate(manager).executeWithoutResult(tx->{
   em.getReference(UserAccount.class,id);
   assertThat(profiles.decide(1L,id,true,null)).containsEntry("coachId",id);
  });
  assertThat(users.findById(id).orElseThrow().getRole()).isEqualTo(Role.COACH);
 }
 @Test void unknownCoachRemainsNotFound() {
  long unknown=Long.MAX_VALUE;
  assertThatThrownBy(()->profiles.publicCoach(unknown)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(404));
  assertThatThrownBy(()->profiles.adminCoach(unknown)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(404));
  assertThatThrownBy(()->profiles.decide(1L,unknown,true,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(404));
 }
 private Long seed(CoachStatus status) {
  return new TransactionTemplate(manager).execute(tx->{
   var u=new UserAccount();u.setEmail(UUID.randomUUID()+"@example.test");u.setDisplayName("Synthetic Coach");
   u.replacePassword("synthetic-hash");u.setAvatarUrl("https://example.test/avatar");
   u.setRole(status==CoachStatus.APPROVED?Role.COACH:Role.ATHLETE);users.saveAndFlush(u);
   var c=new CoachProfile();c.setUser(u);c.setDisplayName("Coach "+u.getId());c.setBio("Synthetic coach biography");c.setSpecialties("[]");c.setStatus(status);
   coaches.saveAndFlush(c);return u.getId();
  });
 }
 private Long seedPlan(long coachId,boolean active,boolean archived,long price,Long compareAtPrice,String currency) {
  return new TransactionTemplate(manager).execute(tx->{
   var plan=new CoachPlan(coaches.getReferenceById(coachId));
   plan.setTitle("Synthetic plan");
   plan.setDurationDays(30);
   plan.setActive(active);
   plan.setArchived(archived);
   plan.setPrice(price);
   plan.setCompareAtPrice(compareAtPrice);
   plan.setCurrency(currency);
   em.persist(plan);
   return plan.getId();
  });
 }
 private long seedFeature(JdbcTemplate jdbc,String code,int order) {
  return jdbc.queryForObject("""
   INSERT INTO plan_feature_catalog (code,title,category,is_active,display_order,created_at,updated_at)
   VALUES (?,?,'SUPPORT',true,?,now(),now()) RETURNING id
   """,Long.class,code,code+" label",order);
 }
 private long seedField(JdbcTemplate jdbc,Long featureId,String code,String valueType,String rules) {
  return jdbc.queryForObject("""
   INSERT INTO plan_field_definitions (feature_id,code,label,value_type,validation_rules,is_required,is_active,
    display_order,created_at,updated_at)
   VALUES (?,?,?,?,CAST(? AS jsonb),?,true,10,now(),now()) RETURNING id
   """,Long.class,featureId,code,code,valueType,rules,featureId==null);
 }
 private MockMvc publicPlanMvc() {
  return MockMvcBuilders.standaloneSetup(new UserController(profiles),new PublicCoachPlanController(publicPlans))
   .setControllerAdvice(new Errors()).build();
 }
 private void assertConflict(Runnable action) {
  assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status).isEqualTo(409);assertThat(e.code).isEqualTo("INVALID_STATE");});
 }
}
