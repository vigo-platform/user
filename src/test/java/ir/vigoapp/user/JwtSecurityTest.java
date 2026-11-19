package ir.vigoapp.user;
import ir.vigoapp.user.api.*;
import ir.vigoapp.user.api.plan.PublicCoachPlanController;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.dto.Requests;
import ir.vigoapp.user.dto.Views;
import ir.vigoapp.user.dto.plan.PlanFieldValue;
import ir.vigoapp.user.entity.UserAccount;
import ir.vigoapp.user.repository.UserRepository;
import ir.vigoapp.user.security.SecurityConfig;
import ir.vigoapp.user.service.ProfileService;
import ir.vigoapp.user.service.plan.OwnedCoachPlanService;
import ir.vigoapp.user.service.plan.PublicCoachPlanService;
import org.junit.jupiter.api.*;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.jwt.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(controllers={UserController.class, CoachPlanController.class, AdminCoachPlanController.class, ir.vigoapp.user.api.plan.CoachPlanController.class, PublicCoachPlanController.class},properties={"app.jwt.jwks-uri=https://auth.example.test/jwks","app.jwt.issuer=https://auth.example.test","app.jwt.audience=vigo-api"})
@Import({SecurityConfig.class,JwtSecurityTest.Keys.class})
class JwtSecurityTest {
 static final String ISSUER="https://auth.example.test";static final long ID=1L;
 static final KeyPair KEY=key(),OTHER=key();
 @Autowired MockMvc mvc;@MockitoBean ProfileService profiles;@MockitoBean UserRepository users;
 @MockitoBean ir.vigoapp.user.service.CoachPlanService plans;
 @MockitoBean ir.vigoapp.user.service.plan.CoachPlanService formOptions;
 @MockitoBean OwnedCoachPlanService ownedPlans;
 @MockitoBean PublicCoachPlanService publicPlans;

 @Test void coachPlanReadsRequireCoachRole() throws Exception {
  var paths = List.of("/api/v1/coaches/me/plans", "/api/v1/coaches/me/plan", "/api/v1/coaches/me/plan/2",
    "/api/v1/coaches/me/plan/form-options", "/api/v1/coaches/me/plan/form-options/features/1");
  String athleteToken = good("ATHLETE");
  for (String path : paths) {
   mvc.perform(get(path)).andExpect(status().isUnauthorized());
   mvc.perform(get(path).header("Authorization", "Bearer " + athleteToken))
     .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }
  verifyNoInteractions(plans, formOptions, ownedPlans, publicPlans);

  users.findById(ID).orElseThrow().setRole(Role.COACH);
  String coachToken = good("COACH");
  for (String path : paths) {
   mvc.perform(get(path).header("Authorization", "Bearer " + coachToken).header("X-User-Id", "42"))
    .andExpect(status().isOk());
  }
  for (String planId : List.of("0", "-1", "invalid")) {
   mvc.perform(get("/api/v1/coaches/me/plan/{plan_id}", planId).header("Authorization", "Bearer " + coachToken))
    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }
  verify(ownedPlans).list(ID);
  verify(ownedPlans).getDetail(ID, 2L);
  verify(formOptions).getFormOptions();
  verify(formOptions).getFeatureDetails(1L);
  verifyNoMoreInteractions(ownedPlans, formOptions);
  verifyNoInteractions(publicPlans);
 }
 @Test void planWritesAcceptLevelInFieldValuesWithNoFeatures() throws Exception {
  users.findById(ID).orElseThrow().setRole(Role.COACH);
  String coachToken = good("COACH");
  String body = """
   {"title":"Workout plan","price":100000,"durationDays":30,"includes":["WORKOUT"],
    "fieldValues":[{"fieldId":12,"value":["BEGINNER"]}]}
   """;
  var level = new PlanFieldValue(12, JsonMapper.builder().build().readTree("[\"BEGINNER\"]"));
  var request = new Requests.Plan("Workout plan", null, 100000L, 30, List.of("WORKOUT"), List.of(), List.of(level), null);

  mvc.perform(post("/api/v1/coaches/me/plans").header("Authorization", "Bearer " + coachToken)
    .contentType("application/json").content(body)).andExpect(status().isCreated());
  mvc.perform(put("/api/v1/coaches/me/plans/1").header("Authorization", "Bearer " + coachToken)
    .contentType("application/json").content(body)).andExpect(status().isOk());
  verify(plans).create(ID, request);
  verify(plans).update(ID, 1L, request);
 }
 @Test void adminsSetActivationAndOnlyCoachesCanArchiveOrRestore() throws Exception {
  String adminPath = "/api/v1/admin/plans/1/status";
  String coachPath = "/api/v1/coaches/me/plans/1";
  mvc.perform(patch(adminPath)).andExpect(status().isUnauthorized());

  var account = users.findById(ID).orElseThrow();
  account.setRole(Role.COACH);
  String coach = "Bearer " + good("COACH");
  mvc.perform(patch(adminPath).header("Authorization", coach).contentType("application/json")
    .content("{\"isActive\":true}")).andExpect(status().isForbidden());
  verifyNoInteractions(plans);
  mvc.perform(delete(coachPath).header("Authorization", coach)).andExpect(status().isOk());
  mvc.perform(post(coachPath + "/unarchive").header("Authorization", coach)).andExpect(status().isOk());
  mvc.perform(post(coachPath + "/activate").header("Authorization", coach)).andExpect(status().isOk());
  verify(plans).archive(ID, 1L);
  verify(plans, times(2)).unarchive(ID, 1L);

  account.setRole(Role.ADMIN);
  String admin = "Bearer " + good("ADMIN");
  mvc.perform(delete(coachPath).header("Authorization", admin)).andExpect(status().isForbidden());
  mvc.perform(post(coachPath + "/unarchive").header("Authorization", admin)).andExpect(status().isForbidden());
  mvc.perform(patch(adminPath).header("Authorization", admin).contentType("application/json")
    .content("{}")).andExpect(status().isBadRequest());
  mvc.perform(patch(adminPath).header("Authorization", admin).contentType("application/json")
    .content("{\"isActive\":true}")).andExpect(status().isOk());
  mvc.perform(patch(adminPath).header("Authorization", admin).contentType("application/json")
    .content("{\"isActive\":false}")).andExpect(status().isOk());
  verify(plans).setActive(1L, true);
  verify(plans).setActive(1L, false);
  verifyNoMoreInteractions(plans);
 }
 static KeyPair key(){try{var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();}catch(Exception e){throw new IllegalStateException(e);}}
 @TestConfiguration static class Keys {
  @Bean @Primary JwtDecoder realCryptographicDecoder(){
   var decoder=NimbusJwtDecoder.withPublicKey((RSAPublicKey)KEY.getPublic()).validateType(false).build();
   decoder.setJwtValidator(SecurityConfig.tokenValidator(ISSUER,"vigo-api"));return decoder;
  }
 }
 @BeforeEach void setUp(){var u=new UserAccount();u.setId(ID);u.setRole(Role.ATHLETE);u.setEmail("synthetic@example.test");u.setDisplayName("Synthetic User");
  when(users.findById(ID)).thenReturn(Optional.of(u));when(profiles.get(ID)).thenReturn(Views.profile(u));}
 String good(String role)throws Exception{return authToken(Long.toString(ID),List.of(role),"access");}
 String authToken(String subject,List<String> roles,String use)throws Exception{
  return token(KEY,roles,subject,ISSUER,"vigo-api",Instant.now().plusSeconds(600),use);
 }
 String token(KeyPair key,List<String> roles,String subject,String issuer,String audience,Instant expires,String use)throws Exception{
  Instant now=Instant.now();
  var claims=new JWTClaimsSet.Builder().subject(subject).issuer(issuer).audience(audience)
   .jwtID(UUID.randomUUID().toString()).issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(expires))
   .claim("token_use",use).claim("sid",UUID.randomUUID().toString()).claim("credential_version",1).claim("roles",roles).build();
  var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType("at+jwt")).build(),claims);
  jwt.sign(new RSASSASigner((RSAPrivateKey)key.getPrivate()));return jwt.serialize();
 }
 @Test void validRealSignedTokenAndSpoofedIdentityIgnored()throws Exception{
  mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+good("ATHLETE")).header("X-User-Id",UUID.randomUUID()).header("X-User-Role","ADMIN"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(ID))
   .andExpect(jsonPath("$.data.user_id").doesNotHaveJsonPath())
   .andExpect(jsonPath("$.messageFa").isNotEmpty()).andExpect(jsonPath("$.message_fa").doesNotHaveJsonPath())
   .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
   .andExpect(jsonPath("$.data.password_hash").doesNotExist()).andExpect(header().doesNotExist("Set-Cookie"));
 }
 @Test void authAccessTokenUsesTheSameNumericIdInRest()throws Exception{
  String access=authToken("1",List.of("ATHLETE"),"access");
  mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+access))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(ID));
  mvc.perform(get("/api/v1/users").header("Authorization","Bearer "+authToken("1",List.of("ADMIN"),"access")))
   .andExpect(status().isForbidden());
  for(String invalid:List.of(authToken("01",List.of("ATHLETE"),"access"),authToken("1",List.of("ATHLETE"),"refresh"),
   authToken("1",List.of("ROOT"),"access"),authToken("2",List.of("ATHLETE"),"access"),
   authToken(UUID.randomUUID().toString(),List.of("ATHLETE"),"access")))
   mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+invalid)).andExpect(status().isUnauthorized());
 }
 @Test void headersAloneCannotAuthenticateAndMeIsNotPublic()throws Exception{
  mvc.perform(get("/api/v1/users/me").header("X-User-Id",ID).header("X-User-Role","ADMIN"))
   .andExpect(status().isUnauthorized())
   .andExpect(jsonPath("$.messageFa").isNotEmpty()).andExpect(jsonPath("$.message_fa").doesNotHaveJsonPath());
  mvc.perform(get("/api/v1/coaches/me")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/v1/coaches/"+ID)).andExpect(status().isOk());
  mvc.perform(get("/api/v1/coaches/"+UUID.randomUUID())).andExpect(status().isBadRequest());
 }
 @Test void coachListAcceptsAnonymousRequestsAndPaginates()throws Exception{
  when(profiles.listPublicCoaches(0,20)).thenReturn(new Views.Paged<>(0,20,0,0,List.of()));
  when(profiles.listPublicCoaches(1,5)).thenReturn(new Views.Paged<>(1,5,6,2,List.of(Map.of("coachId",ID))));

  mvc.perform(get("/api/v1/coaches"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(0))
   .andExpect(jsonPath("$.data.size").value(20)).andExpect(jsonPath("$.data.content").isEmpty());
  mvc.perform(get("/api/v1/coaches").param("page","1").param("size","5"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1))
   .andExpect(jsonPath("$.data.size").value(5)).andExpect(jsonPath("$.data.totalElements").value(6))
   .andExpect(jsonPath("$.data.totalPages").value(2)).andExpect(jsonPath("$.data.content[0].coachId").value(ID));
  mvc.perform(post("/api/v1/coaches")).andExpect(status().isUnauthorized());
 }
 @Test void publicPlanDetailAllowsOnlyGetAndRequiresPositiveNumericIds()throws Exception{
  String path="/api/v1/coaches/"+ID+"/plan/2";
  mvc.perform(get(path)).andExpect(status().isOk());
  mvc.perform(post(path)).andExpect(status().isUnauthorized());
  for(String invalid:List.of("0/plan/2","abc/plan/2",ID+"/plan/0",ID+"/plan/abc")){
   mvc.perform(get("/api/v1/coaches/"+invalid)).andExpect(status().isBadRequest())
    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }
  verify(publicPlans).getDetail(ID,2L);
  verifyNoMoreInteractions(publicPlans);
 }
 @Test void coachListRejectsInvalidPagination()throws Exception{
  for(var invalid:Map.of("page",List.of("-1","abc"),"size",List.of("0","101","abc")).entrySet()){
   for(String value:invalid.getValue()){
    mvc.perform(get("/api/v1/coaches").param(invalid.getKey(),value))
     .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
   }
  }
  verifyNoInteractions(profiles);
 }
 @Test void badSignaturesClaimsAndExpiredAreRejected()throws Exception{
  var roles=List.of("ATHLETE");
  String subject=Long.toString(ID);
  Instant future=Instant.now().plusSeconds(300);
  var bad=List.of(token(OTHER,roles,subject,ISSUER,"vigo-api",future,"access"),
   token(KEY,roles,subject,"wrong","vigo-api",future,"access"),
   token(KEY,roles,subject,ISSUER,"other",future,"access"),
   token(KEY,roles,subject,ISSUER,"vigo-api",Instant.now().minusSeconds(3600),"access"));
  for(String value:bad)mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+value)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
 }
 @Test void noneAndHs256CannotConfuseRsaDecoder()throws Exception{
  var claims=new JWTClaimsSet.Builder().subject(Long.toString(ID)).issuer(ISSUER).audience("vigo-api")
   .claim("roles",List.of("ATHLETE")).claim("token_use","access").expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
  var hmac=new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),claims);hmac.sign(new MACSigner(new byte[32]));
  for(String value:List.of(new PlainJWT(claims).serialize(),hmac.serialize()))mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+value)).andExpect(status().isUnauthorized());
 }
 @Test void roleIntersectionAndImmediateSuspension()throws Exception{
  mvc.perform(get("/api/v1/users").header("Authorization","Bearer "+good("ADMIN"))).andExpect(status().isForbidden());
  var u=users.findById(ID).orElseThrow();u.setRole(Role.ADMIN);
  mvc.perform(get("/api/v1/users").header("Authorization","Bearer "+good("ATHLETE"))).andExpect(status().isForbidden());
  u.setStatus(AccountStatus.SUSPENDED);
  mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+good("ADMIN"))).andExpect(status().isUnauthorized());
 }
 @Test void strictUnknownFieldsAndValidation()throws Exception{
  mvc.perform(put("/api/v1/users/me").header("Authorization","Bearer "+good("ATHLETE")).contentType("application/json").content("{\"displayName\":\"Valid Name\",\"role\":\"ADMIN\"}")).andExpect(status().isBadRequest());
  mvc.perform(put("/api/v1/users/me").header("Authorization","Bearer "+good("ATHLETE")).contentType("application/json").content("{\"displayName\":\"x\"}"))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data[0].field").value("displayName"))
   .andExpect(jsonPath("$.data[0].messageFa").isNotEmpty()).andExpect(jsonPath("$.data[0].message_fa").doesNotHaveJsonPath());
 }
 @Test void prometheusNotExposedOnApplicationPortAndWrongMethodsDenied()throws Exception{
  mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/coaches/"+ID)).andExpect(status().isUnauthorized());
 }
}
