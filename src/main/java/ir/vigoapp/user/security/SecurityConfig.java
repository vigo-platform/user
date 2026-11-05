package ir.vigoapp.user.security;
import ir.vigoapp.user.api.Envelope;
import ir.vigoapp.user.domain.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.springframework.http.HttpMethod.*;
@Configuration
public class SecurityConfig {
 private static final Logger log=LoggerFactory.getLogger(SecurityConfig.class);
 @Bean JwtDecoder jwtDecoder(@Value("${app.jwt.jwks-uri}") String jwks,@Value("${app.jwt.issuer}") String issuer,@Value("${app.jwt.audience}") String audience) {
  if(jwks.isBlank()||issuer.isBlank()||audience.isBlank())throw new IllegalStateException("JWT trust configuration required");
  NimbusJwtDecoder decoder=NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).validateType(false).build();
  decoder.setJwtValidator(tokenValidator(issuer,audience));
  return decoder;
 }
 public static OAuth2TokenValidator<Jwt> tokenValidator(String issuer,String audience) {
  var types=new JwtTypeValidator("JWT","at+jwt");
  types.setAllowEmpty(true);
  return JwtValidators.createDefaultWithValidators(List.of(new JwtIssuerValidator(issuer),types,claimsValidator(audience)));
 }
 private static OAuth2TokenValidator<Jwt> claimsValidator(String audience) {
  return jwt->{
   try {
    String subject=jwt.getSubject();
    if(isAuthAccessToken(jwt)) {
     long id=Long.parseLong(subject);
     if(id<=0||!Long.toString(id).equals(subject)||!"access".equals(jwt.getClaim("token_use")))throw new IllegalArgumentException();
    } else if(!UUID.fromString(subject).toString().equals(subject))throw new IllegalArgumentException();
    if(signedRoles(jwt).isEmpty())throw new IllegalArgumentException();
    if(!jwt.getAudience().contains(audience)||jwt.getExpiresAt()==null)throw new IllegalArgumentException();
    return OAuth2TokenValidatorResult.success();
   }catch(RuntimeException e){return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Invalid access token claims",null));}
  };
 }
 private static boolean isAuthAccessToken(Jwt jwt) {
  return jwt.getHeaders().get("typ") instanceof String type&&"at+jwt".equalsIgnoreCase(type);
 }
 private static List<Role> signedRoles(Jwt jwt) {
  if(isAuthAccessToken(jwt)) {
   if(!(jwt.getClaim("roles") instanceof List<?> roles))throw new IllegalArgumentException();
   return roles.stream().map(role->Role.valueOf((String)role)).toList();
  }
  return List.of(Role.valueOf(jwt.getClaimAsString("role")));
 }
 @Bean SecurityFilterChain apiSecurity(HttpSecurity http,UserRepository users,ObjectMapper json,Environment environment) throws Exception {
  http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
   .authorizeHttpRequests(a->a
    .requestMatchers(GET,"/api/v1/users/me").authenticated()
    .requestMatchers(PUT,"/api/v1/users/me").authenticated()
    .requestMatchers(POST,"/api/v1/coaches/me/profile").hasRole("ATHLETE")
    .requestMatchers(GET,"/api/v1/coaches/me").hasAnyRole("ATHLETE","COACH")
    .requestMatchers(PUT,"/api/v1/coaches/me").hasAnyRole("ATHLETE","COACH")
    .requestMatchers(GET,"/api/v1/users","/api/v1/users/{id}").hasRole("ADMIN")
    .requestMatchers(PATCH,"/api/v1/users/{id}/status").hasRole("ADMIN")
    .requestMatchers(GET,"/api/v1/admin/coaches","/api/v1/admin/coaches/{id}").hasRole("ADMIN")
    .requestMatchers(POST,"/api/v1/admin/coaches/{id}/approve","/api/v1/admin/coaches/{id}/reject").hasRole("ADMIN")
    .requestMatchers(GET,"/api/v1/coaches","/api/v1/coaches/{id}").permitAll()
    .requestMatchers(GET,"/actuator/health").permitAll()
    .requestMatchers(GET,"/actuator/health/liveness","/actuator/health/readiness","/actuator/prometheus")
    .access((authentication,context)->new AuthorizationDecision(
     context.getRequest().getLocalPort()==environment.getProperty("local.management.port",Integer.class,-1)))
    .anyRequest().denyAll())
   .exceptionHandling(e->e.authenticationEntryPoint((r,s,x)->write(json,r,s,401,x)).accessDeniedHandler((r,s,x)->write(json,r,s,403,x)))
   .oauth2ResourceServer(o->o.authenticationEntryPoint((r,s,x)->write(json,r,s,401,x)).jwt(j->j.jwtAuthenticationConverter(jwt->{
    String subject=jwt.getSubject();
    var result=isAuthAccessToken(jwt)?users.findById(Long.parseLong(subject)):users.findByPublicId(UUID.fromString(subject));
    var account=result.orElseThrow(()->new org.springframework.security.oauth2.core.OAuth2AuthenticationException("invalid_token"));
    if(account.status!=AccountStatus.ACTIVE)throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException("invalid_token");
    // Signed role AND authoritative DB role required. Never read X-User-* headers.
    var authorities=signedRoles(jwt).contains(account.role)?List.of(new SimpleGrantedAuthority("ROLE_"+account.role.name())):List.<SimpleGrantedAuthority>of();
    return new JwtAuthenticationToken(jwt,authorities,account.publicId.toString());
   })));
  return http.build();
 }
 private static void write(ObjectMapper json,HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,int code,Exception failure) throws java.io.IOException {
  var event=log.atWarn().addKeyValue("event",code==401?"authentication_required":"access_denied").addKeyValue("result","REJECTED")
   .addKeyValue("status",code).addKeyValue("reason",failure.getClass().getSimpleName()).addKeyValue("http_method",request.getMethod())
   .addKeyValue("path",request.getRequestURI()).addKeyValue("ip_address",request.getRemoteAddr());
  var caller=request.getUserPrincipal();
  if(caller!=null)event.addKeyValue("actor_public_id",caller.getName());
  event.log(code==401?"User API request requires authentication.":"User API request access denied.");
  response.setStatus(code);response.setContentType("application/json");
  if(code==401)response.setHeader("WWW-Authenticate","Bearer");
  json.writeValue(response.getOutputStream(),Envelope.error(code==401?"UNAUTHORIZED":"FORBIDDEN",code==401?"Authentication required":"Access denied",code==401?"احراز هویت لازم است":"دسترسی مجاز نیست",null));
 }
}
