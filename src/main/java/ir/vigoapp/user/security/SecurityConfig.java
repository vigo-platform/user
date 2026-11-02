package ir.vigoapp.user.security;
import ir.vigoapp.user.api.Envelope;
import ir.vigoapp.user.domain.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
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
 @Bean JwtDecoder jwtDecoder(@Value("${app.jwt.jwks-uri}") String jwks,@Value("${app.jwt.issuer}") String issuer,@Value("${app.jwt.audience}") String audience) {
  if(jwks.isBlank()||issuer.isBlank()||audience.isBlank())throw new IllegalStateException("JWT trust configuration required");
  NimbusJwtDecoder decoder=NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256).build();
  decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),claimsValidator(audience)));
  return decoder;
 }
 public static OAuth2TokenValidator<Jwt> claimsValidator(String audience) {
  return jwt->{
   try {
    UUID id=UUID.fromString(jwt.getSubject());
    if(!id.toString().equals(jwt.getSubject()))throw new IllegalArgumentException();
    Role.valueOf(jwt.getClaimAsString("role"));
    if(!jwt.getAudience().contains(audience)||jwt.getExpiresAt()==null)throw new IllegalArgumentException();
    return OAuth2TokenValidatorResult.success();
   }catch(RuntimeException e){return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Invalid access token claims",null));}
  };
 }
 @Bean SecurityFilterChain apiSecurity(HttpSecurity http,UserRepository users,ObjectMapper json) throws Exception {
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
    .requestMatchers(GET,"/api/v1/coaches/{id}").permitAll()
    .requestMatchers(GET,"/actuator/health").permitAll()
    .requestMatchers(GET,"/actuator/health/liveness","/actuator/health/readiness").access((authentication,context)->new org.springframework.security.authorization.AuthorizationDecision(context.getRequest().getLocalPort()==9081))
    .requestMatchers(GET,"/actuator/prometheus").access((authentication,context)->new org.springframework.security.authorization.AuthorizationDecision(context.getRequest().getLocalPort()==9081))
    .anyRequest().denyAll())
   .exceptionHandling(e->e.authenticationEntryPoint((r,s,x)->write(json,s,401)).accessDeniedHandler((r,s,x)->write(json,s,403)))
   .oauth2ResourceServer(o->o.authenticationEntryPoint((r,s,x)->write(json,s,401)).jwt(j->j.jwtAuthenticationConverter(jwt->{
    var account=users.findByPublicId(UUID.fromString(jwt.getSubject())).orElseThrow(()->new org.springframework.security.oauth2.core.OAuth2AuthenticationException("invalid_token"));
    if(account.status!=AccountStatus.ACTIVE)throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException("invalid_token");
    // Signed role AND authoritative DB role required. Never read X-User-* headers.
    var authorities=account.role.name().equals(jwt.getClaimAsString("role"))?List.of(new SimpleGrantedAuthority("ROLE_"+account.role.name())):List.<SimpleGrantedAuthority>of();
    return new JwtAuthenticationToken(jwt,authorities,account.publicId.toString());
   })));
  return http.build();
 }
 private static void write(ObjectMapper json,jakarta.servlet.http.HttpServletResponse response,int code) throws java.io.IOException {
  response.setStatus(code);response.setContentType("application/json");
  if(code==401)response.setHeader("WWW-Authenticate","Bearer");
  json.writeValue(response.getOutputStream(),Envelope.error(code==401?"UNAUTHORIZED":"FORBIDDEN",code==401?"Authentication required":"Access denied",code==401?"احراز هویت لازم است":"دسترسی مجاز نیست",null));
 }
}
