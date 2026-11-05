package ir.vigoapp.user;
import ir.vigoapp.user.api.*;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.service.ProfileService;
import ir.vigoapp.user.security.SecurityConfig;
import org.junit.jupiter.api.*;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(controllers=UserController.class,properties={"app.jwt.jwks-uri=https://auth.example.test/jwks","app.jwt.issuer=https://auth.example.test","app.jwt.audience=vigo-api"})
@Import({SecurityConfig.class,JwtSecurityTest.Keys.class})
class JwtSecurityTest {
 static final String ISSUER="https://auth.example.test";static final UUID ID=UUID.fromString("11111111-1111-4111-8111-111111111111");
 static final KeyPair KEY=key(),OTHER=key();
 @Autowired MockMvc mvc;@MockitoBean ProfileService profiles;@MockitoBean UserRepository users;
 static KeyPair key(){try{var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();}catch(Exception e){throw new IllegalStateException(e);}}
 @TestConfiguration static class Keys {
  @Bean @Primary JwtDecoder realCryptographicDecoder(){
   var decoder=NimbusJwtDecoder.withPublicKey((RSAPublicKey)KEY.getPublic()).validateType(false).build();
   decoder.setJwtValidator(SecurityConfig.tokenValidator(ISSUER,"vigo-api"));return decoder;
  }
 }
 @BeforeEach void setUp(){var u=new UserAccount();u.id=1L;u.publicId=ID;u.role=ir.vigoapp.user.domain.Role.ATHLETE;u.email="synthetic@example.test";u.displayName="Synthetic User";
  when(users.findById(u.id)).thenReturn(Optional.of(u));when(users.findByPublicId(ID)).thenReturn(Optional.of(u));when(profiles.get(ID)).thenReturn(Views.profile(u));}
 String token(KeyPair key,String role,String subject,String issuer,String audience,Instant expires)throws Exception{
  var claims=new JWTClaimsSet.Builder().subject(subject).issuer(issuer).audience(audience).claim("role",role).issueTime(Date.from(Instant.now().minusSeconds(30))).expirationTime(Date.from(expires)).build();
  var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims);jwt.sign(new RSASSASigner((RSAPrivateKey)key.getPrivate()));return jwt.serialize();
 }
 String good(String role)throws Exception{return token(KEY,role,ID.toString(),ISSUER,"vigo-api",Instant.now().plusSeconds(600));}
 String authToken(String subject,List<String> roles,String use)throws Exception{
  Instant now=Instant.now();
  var claims=new JWTClaimsSet.Builder().subject(subject).issuer(ISSUER).audience("vigo-api")
   .jwtID(UUID.randomUUID().toString()).issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(600)))
   .claim("token_use",use).claim("sid",UUID.randomUUID().toString()).claim("credential_version",1).claim("roles",roles).build();
  var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType("at+jwt")).build(),claims);
  jwt.sign(new RSASSASigner((RSAPrivateKey)KEY.getPrivate()));return jwt.serialize();
 }
 @Test void validRealSignedTokenAndSpoofedIdentityIgnored()throws Exception{
  mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+good("ATHLETE")).header("X-User-Id",UUID.randomUUID()).header("X-User-Role","ADMIN"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.user_id").value(ID.toString())).andExpect(jsonPath("$.data.password_hash").doesNotExist()).andExpect(header().doesNotExist("Set-Cookie"));
 }
 @Test void authAccessTokenResolvesNumericIdAndKeepsUuidInRest()throws Exception{
  String access=authToken("1",List.of("ATHLETE"),"access");
  mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+access))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.user_id").value(ID.toString()));
  mvc.perform(get("/api/v1/users").header("Authorization","Bearer "+authToken("1",List.of("ADMIN"),"access")))
   .andExpect(status().isForbidden());
  for(String invalid:List.of(authToken("01",List.of("ATHLETE"),"access"),authToken("1",List.of("ATHLETE"),"refresh"),
   authToken("1",List.of("ROOT"),"access"),authToken("2",List.of("ATHLETE"),"access")))
   mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+invalid)).andExpect(status().isUnauthorized());
 }
 @Test void headersAloneCannotAuthenticateAndMeIsNotPublic()throws Exception{
  mvc.perform(get("/api/v1/users/me").header("X-User-Id",ID).header("X-User-Role","ADMIN")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/v1/coaches/me")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/v1/coaches/"+ID)).andExpect(status().isOk());
 }
 @Test void badSignaturesClaimsAndExpiredAreRejected()throws Exception{
  var bad=List.of(token(OTHER,"ATHLETE",ID.toString(),ISSUER,"vigo-api",Instant.now().plusSeconds(300)),token(KEY,"ROOT",ID.toString(),ISSUER,"vigo-api",Instant.now().plusSeconds(300)),token(KEY,"ATHLETE","12",ISSUER,"vigo-api",Instant.now().plusSeconds(300)),token(KEY,"ATHLETE",ID.toString(),"wrong","vigo-api",Instant.now().plusSeconds(300)),token(KEY,"ATHLETE",ID.toString(),ISSUER,"other",Instant.now().plusSeconds(300)),token(KEY,"ATHLETE",ID.toString(),ISSUER,"vigo-api",Instant.now().minusSeconds(3600)));
  for(String value:bad)mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+value)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
 }
 @Test void noneAndHs256CannotConfuseRsaDecoder()throws Exception{
  var claims=new JWTClaimsSet.Builder().subject(ID.toString()).issuer(ISSUER).audience("vigo-api").claim("role","ATHLETE").expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
  var hmac=new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),claims);hmac.sign(new MACSigner(new byte[32]));
  for(String value:List.of(new PlainJWT(claims).serialize(),hmac.serialize()))mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+value)).andExpect(status().isUnauthorized());
 }
 @Test void roleIntersectionAndImmediateSuspension()throws Exception{
  mvc.perform(get("/api/v1/users").header("Authorization","Bearer "+good("ADMIN"))).andExpect(status().isForbidden());
  var u=users.findByPublicId(ID).orElseThrow();u.role=ir.vigoapp.user.domain.Role.ADMIN;
  mvc.perform(get("/api/v1/users").header("Authorization","Bearer "+good("ATHLETE"))).andExpect(status().isForbidden());
  u.status=AccountStatus.SUSPENDED;
  mvc.perform(get("/api/v1/users/me").header("Authorization","Bearer "+good("ADMIN"))).andExpect(status().isUnauthorized());
 }
 @Test void strictUnknownFieldsAndValidation()throws Exception{
  mvc.perform(put("/api/v1/users/me").header("Authorization","Bearer "+good("ATHLETE")).contentType("application/json").content("{\"display_name\":\"Valid Name\",\"role\":\"ADMIN\"}")).andExpect(status().isBadRequest());
  mvc.perform(put("/api/v1/users/me").header("Authorization","Bearer "+good("ATHLETE")).contentType("application/json").content("{\"display_name\":\"x\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data[0].field").value("display_name"));
 }
 @Test void prometheusNotExposedOnApplicationPortAndWrongMethodsDenied()throws Exception{
  mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/coaches/"+ID)).andExpect(status().isUnauthorized());
 }
}
