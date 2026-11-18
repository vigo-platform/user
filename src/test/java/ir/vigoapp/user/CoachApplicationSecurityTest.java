package ir.vigoapp.user;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import ir.vigoapp.user.api.AdminCoachApplicationController;
import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.api.CoachApplicationController;
import ir.vigoapp.user.domain.Role;
import ir.vigoapp.user.domain.UserAccount;
import ir.vigoapp.user.domain.UserRepository;
import ir.vigoapp.user.security.PublicSubmissionRateLimiter;
import ir.vigoapp.user.security.SecurityConfig;
import ir.vigoapp.user.service.CoachApplicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Anonymous submission stays open; every review operation stays ADMIN-only. */
@WebMvcTest(controllers = {CoachApplicationController.class, AdminCoachApplicationController.class},
        properties = {"app.jwt.jwks-uri=https://auth.example.test/jwks",
                "app.jwt.issuer=https://auth.example.test", "app.jwt.audience=vigo-api"})
@Import({SecurityConfig.class, CoachApplicationSecurityTest.Keys.class})
class CoachApplicationSecurityTest {
    static final String ISSUER = "https://auth.example.test";
    static final UUID ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final KeyPair KEY = key();
    static final String BODY = "{\"first_name\":\"Sara\",\"last_name\":\"Coach\",\"email\":\"sara@example.test\","
            + "\"bio\":\"Fifteen years coaching strength athletes.\",\"specialties\":[\"Strength\"],"
            + "\"experience_years\":12}";

    @Autowired MockMvc mvc;
    @MockitoBean CoachApplicationService applications;
    @MockitoBean PublicSubmissionRateLimiter limiter;
    @MockitoBean UserRepository users;

    static KeyPair key() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class Keys {
        @Bean @Primary
        JwtDecoder realCryptographicDecoder() {
            var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY.getPublic()).validateType(false).build();
            decoder.setJwtValidator(SecurityConfig.tokenValidator(ISSUER, "vigo-api"));
            return decoder;
        }
    }

    @BeforeEach
    void setUp() {
        var account = new UserAccount();
        account.id = 1L;
        account.publicId = ID;
        account.role = Role.ADMIN;
        when(users.findById(1L)).thenReturn(Optional.of(account));
        when(users.findByPublicId(ID)).thenReturn(Optional.of(account));
        when(applications.submit(any())).thenReturn(Map.of("application_id", UUID.randomUUID(),
                "status", "PENDING", "lookup_token", "token", "submitted_at", Instant.now()));
        when(applications.status("token")).thenReturn(Map.of("application_id", UUID.randomUUID(), "status", "PENDING"));
        when(applications.list(0, 20, null)).thenReturn(new ir.vigoapp.user.api.Views.Paged<>(0, 20, 0, 0, java.util.List.of()));
    }

    String token(String role, long id) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().subject(Long.toString(id)).issuer(ISSUER).audience("vigo-api")
                .jwtID(UUID.randomUUID().toString()).issueTime(Date.from(now)).notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600))).claim("token_use", "access")
                .claim("sid", UUID.randomUUID().toString()).claim("credential_version", 1)
                .claim("roles", java.util.List.of(role)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(new com.nimbusds.jose.JOSEObjectType("at+jwt")).build(), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEY.getPrivate()));
        return jwt.serialize();
    }

    @Test
    void anonymousSubmissionIsAccepted() throws Exception {
        mvc.perform(post("/api/v1/coaches/applications").contentType("application/json").content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void anonymousStatusLookupIsAccepted() throws Exception {
        mvc.perform(get("/api/v1/coaches/applications/token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void invalidSubmissionIsRejectedBeforeTheService() throws Exception {
        mvc.perform(post("/api/v1/coaches/applications").contentType("application/json")
                        .content("{\"first_name\":\"S\",\"email\":\"not-an-email\",\"bio\":\"short\","
                                + "\"specialties\":[],\"experience_years\":99}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rateLimitedSubmissionReturns429() throws Exception {
        doThrow(new ApiException(429, "RATE_LIMITED", "Too many applications submitted; try again later",
                "تعداد درخواست‌ها بیش از حد مجاز است؛ بعداً دوباره تلاش کنید")).when(limiter).requireAllowed(any());
        mvc.perform(post("/api/v1/coaches/applications").contentType("application/json").content(BODY))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void adminEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/admin/coaches/applications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/coaches/applications/" + UUID.randomUUID() + "/approve"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminCannotReviewApplications() throws Exception {
        String athlete = token("ATHLETE", 1L);
        mvc.perform(get("/api/v1/admin/coaches/applications").header("Authorization", "Bearer " + athlete))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/coaches/applications/" + UUID.randomUUID() + "/approve")
                        .header("Authorization", "Bearer " + athlete))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/coaches/applications/" + UUID.randomUUID() + "/reject")
                        .header("Authorization", "Bearer " + athlete)
                        .contentType("application/json").content("{\"reason\":\"Insufficient evidence\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanListApplications() throws Exception {
        mvc.perform(get("/api/v1/admin/coaches/applications").header("Authorization", "Bearer " + token("ADMIN", 1L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(0));
    }
}
