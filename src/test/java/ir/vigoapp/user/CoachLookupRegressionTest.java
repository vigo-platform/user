package ir.vigoapp.user;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.outbox.OutboxWriter;
import ir.vigoapp.user.service.ProfileService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

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
  @Bean ProfileService profiles(UserRepository users,CoachRepository coaches,ObjectMapper json) {
   return new ProfileService(users,coaches,mock(OutboxWriter.class),json);
  }
 }
 @Autowired ProfileService profiles;
 @Autowired UserRepository users;
 @Autowired CoachRepository coaches;
 @Autowired @org.springframework.beans.factory.annotation.Qualifier("entityManager") EntityManager em;
 @Autowired PlatformTransactionManager manager;

 @Test void httpPublicListIdResolvesDetailsAndApprovedDecisionsReturn409() throws Exception {
  var id=seed(CoachStatus.APPROVED);
  // Exercise controller routing and error translation with the real transactional service.
  // JWT authorization itself is covered separately by JwtSecurityTest.
  var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders
   .standaloneSetup(new ir.vigoapp.user.api.UserController(profiles))
   .setControllerAdvice(new ir.vigoapp.user.api.Errors()).build();
  var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/coaches"))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
  var tree=JsonMapper.builder().build().readTree(response.getResponse().getContentAsString());
  String coachId=null;
  for(var coach:tree.path("data").path("content"))
   if(("Coach "+id).equals(coach.path("display_name").asText()))coachId=coach.path("coach_id").asText();
  assertThat(coachId).isEqualTo(id.toString());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/coaches/"+coachId))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.coach_id").value(coachId));
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/coaches/"+coachId))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  var actor=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(),null);
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/coaches/"+coachId+"/approve").principal(actor))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("INVALID_STATE"));
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/coaches/"+coachId+"/reject").principal(actor)
   .contentType("application/json").content("{\"reason\":\"Already approved\"}"))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("INVALID_STATE"));
 }
 @Test void publicListIdResolvesBothDetailsAndDecisionsReturnConflictNotNotFound() {
  var id=seed(CoachStatus.APPROVED);
  var returned=profiles.listPublicCoaches(0,100).content().stream()
   .filter(c->"Coach ".concat(id.toString()).equals(c.get("display_name"))).findFirst().orElseThrow();
  UUID coachId=(UUID)returned.get("coach_id");
  assertThat(coachId).isEqualTo(id);
  assertThat(profiles.publicCoach(coachId)).containsEntry("coach_id",id).containsEntry("avatar_url","https://example.test/avatar");
  assertThat(profiles.adminCoach(coachId)).containsEntry("coach_id",id);
  assertConflict(()->profiles.decide(UUID.randomUUID(),coachId,true,null));
  assertConflict(()->profiles.decide(UUID.randomUUID(),coachId,false,"Not pending"));
 }
 @Test void pendingAdminListIdCanBeApproved() { assertPendingDecision(true); }
 @Test void pendingAdminListIdCanBeRejected() { assertPendingDecision(false); }
 private void assertPendingDecision(boolean approve) {
  var id=seed(CoachStatus.PENDING);
  var returned=profiles.listCoaches(0,100,CoachStatus.PENDING).content().stream()
   .filter(c->"Coach ".concat(id.toString()).equals(c.get("display_name"))).findFirst().orElseThrow();
  UUID coachId=(UUID)returned.get("coach_id");
  assertThat(coachId).isEqualTo(id);
  assertThat(profiles.adminCoach(coachId)).containsEntry("coach_id",id);
  assertThat(profiles.decide(UUID.randomUUID(),coachId,approve,"Review reason"))
   .containsEntry("coach_id",id).containsEntry("status",approve?CoachStatus.APPROVED:CoachStatus.REJECTED);
 }
 @Test void existingUserProxyMustNotChangePublicEligibilityOrReturnedId() {
  var id=seed(CoachStatus.APPROVED);
  long internalId=users.findByPublicId(id).orElseThrow().id;
  new TransactionTemplate(manager).executeWithoutResult(tx->{
   var reference=em.getReference(UserAccount.class,internalId);
   assertThat(org.hibernate.Hibernate.isInitialized(reference)).isFalse();
   assertThat(profiles.publicCoach(id)).containsEntry("coach_id",id).containsEntry("avatar_url","https://example.test/avatar");
   assertThat(profiles.adminCoach(id)).containsEntry("coach_id",id);
  });
 }
 @Test void existingUserProxyMustNotCorruptPublicListId() {
  var id=seed(CoachStatus.APPROVED);
  long internalId=users.findByPublicId(id).orElseThrow().id;
  new TransactionTemplate(manager).executeWithoutResult(tx->{
   em.getReference(UserAccount.class,internalId);
   var returned=profiles.listPublicCoaches(0,100).content().stream()
    .filter(c->("Coach "+id).equals(c.get("display_name"))).findFirst().orElseThrow();
   assertThat(returned).containsEntry("coach_id",id).containsEntry("avatar_url","https://example.test/avatar");
   assertThat(profiles.publicCoach((UUID)returned.get("coach_id"))).containsEntry("coach_id",id);
  });
 }
 @Test void decisionWithExistingUserProxyPersistsTheRealAccountRole() {
  var id=seed(CoachStatus.PENDING);
  long internalId=users.findByPublicId(id).orElseThrow().id;
  new TransactionTemplate(manager).executeWithoutResult(tx->{
   em.getReference(UserAccount.class,internalId);
   assertThat(profiles.decide(UUID.randomUUID(),id,true,null)).containsEntry("coach_id",id);
  });
  assertThat(users.findByPublicId(id).orElseThrow().role).isEqualTo(Role.COACH);
 }
 @Test void unknownCoachRemainsNotFound() {
  var unknown=UUID.randomUUID();
  assertThatThrownBy(()->profiles.publicCoach(unknown)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(404));
  assertThatThrownBy(()->profiles.adminCoach(unknown)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(404));
  assertThatThrownBy(()->profiles.decide(UUID.randomUUID(),unknown,true,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status).isEqualTo(404));
 }
 private UUID seed(CoachStatus status) {
  return new TransactionTemplate(manager).execute(tx->{
   var u=new UserAccount();u.email=UUID.randomUUID()+"@example.test";u.displayName="Synthetic Coach";
   u.replacePassword("synthetic-hash");u.avatarUrl="https://example.test/avatar";
   u.role=status==CoachStatus.APPROVED?Role.COACH:Role.ATHLETE;users.saveAndFlush(u);
   var c=new CoachProfile();c.user=u;c.displayName="Coach "+u.publicId;c.bio="Synthetic coach biography";c.specialties="[]";c.status=status;
   coaches.saveAndFlush(c);return u.publicId;
  });
 }
 private void assertConflict(Runnable action) {
  assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.status).isEqualTo(409);assertThat(e.code).isEqualTo("INVALID_STATE");});
 }
}
