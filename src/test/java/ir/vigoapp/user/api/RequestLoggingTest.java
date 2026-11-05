package ir.vigoapp.user.api;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import ir.vigoapp.contracts.user.v1.GetUserLoginInfoByEmailRequest;
import ir.vigoapp.contracts.user.v1.UserAuthInfo;
import ir.vigoapp.contracts.user.v1.UserLoginInfoResponse;
import ir.vigoapp.contracts.user.v1.UserServiceGrpc;
import ir.vigoapp.user.UserApplication;
import ir.vigoapp.user.domain.UserRepository;
import ir.vigoapp.user.grpc.GrpcServerLifecycle;
import ir.vigoapp.user.outbox.OutboxWriter;
import ir.vigoapp.user.service.CredentialService;
import ir.vigoapp.user.service.ProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = UserApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=dev", "app.grpc.dev-plaintext-enabled=true", "app.grpc.port=0",
        "app.grpc.dev-auth-user-agent=grpc-java-netty/1.85.1",
        "management.server.port=0", "app.jwt.jwks-uri=https://auth.example.test/jwks",
        "app.jwt.issuer=https://auth.example.test", "app.jwt.audience=vigo-api", "spring.output.ansi.enabled=never",
        "app.outbox.enabled=false", "management.endpoint.health.group.readiness.include=readinessState,grpc",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
})
@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingTest {
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String PARENT_SPAN_ID = "0123456789abcdef";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01";
    private static final Pattern CORRELATION = Pattern.compile("\\[([0-9a-f]{32})-([0-9a-f]{16})\\]");
    private static final UUID COACH = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @LocalServerPort int port;
    @LocalManagementPort int managementPort;
    @Autowired GrpcServerLifecycle grpc;
    @MockitoBean UserRepository users;
    @MockitoBean ProfileService profiles;
    @MockitoBean CredentialService credentials;
    @MockitoBean OutboxWriter outbox;

    @Test void httpKeepsSuccessfulReadsQuietAndTracesSecurityRejections(CapturedOutput output) throws Exception {
        when(profiles.publicCoach(COACH)).thenReturn(Map.of("coach_id", COACH));
        try (var client = HttpClient.newHttpClient()) {
            assertThat(get(client, port, "/api/v1/coaches/" + COACH + "?query=secret-query", TRACEPARENT)).isEqualTo(200);
            assertThat(get(client, port, "/api/v1/users/me", TRACEPARENT)).isEqualTo(401);
            assertThat(get(client, managementPort, "/actuator/health", null)).isEqualTo(200);
        }

        assertThat(output.getOut()).doesNotContain("event=\"user_request_completed\"", "path=\"/api/v1/coaches/", "secret-query");
        assertThat(traceOf(lineFor(output, "authentication_required"))).isEqualTo(TRACE_ID);
    }

    @Test void grpcLogsOneResultPerCallWithTraceAndSafeFields(CapturedOutput output) throws Exception {
        var login = UserLoginInfoResponse.newBuilder().setUser(UserAuthInfo.newBuilder().setUserId(7))
                .setPasswordHash("secret-encoded-hash").build();
        when(credentials.loginByEmail("secret@example.test")).thenReturn(login);
        when(credentials.loginByEmail("broken@example.test")).thenThrow(new IllegalStateException("secret-db-value"));
        var server = (Server) ReflectionTestUtils.getField(grpc, "server");
        var channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        var otherChannel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().userAgent("other-dev-client").build();
        try {
            var headers = new Metadata();
            headers.put(Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER), TRACEPARENT);
            var client = UserServiceGrpc.newBlockingStub(channel);
            var traced = client.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
            assertThat(traced.withDeadlineAfter(5, TimeUnit.SECONDS).getUserLoginInfoByEmail(loginRequest("secret@example.test")))
                    .isEqualTo(login);
            var claimedIdentity = new Metadata();
            claimedIdentity.put(Metadata.Key.of("caller-service", Metadata.ASCII_STRING_MARSHALLER), "auth");
            var otherClient = UserServiceGrpc.newBlockingStub(otherChannel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(claimedIdentity));
            assertThatThrownBy(() -> otherClient.withDeadlineAfter(5, TimeUnit.SECONDS).getUserLoginInfoByEmail(loginRequest("broken@example.test")))
                    .satisfies(error -> assertThat(Status.fromThrowable(error).getCode()).isEqualTo(Status.Code.INTERNAL));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            otherChannel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }

        var lines = output.getOut().lines().filter(line -> line.contains("event=\"grpc_request_completed\"")).toList();
        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).contains("grpc_method=\"vigo.user.v1.UserService/GetUserLoginInfoByEmail\"",
                "grpc_status=\"OK\"", "user_id=\"7\"", "caller_service=\"auth\"", "caller_identity=\"dev_inferred\"",
                "transport=\"plaintext\"", "duration_ms=");
        assertThat(traceOf(lines.getFirst())).isEqualTo(TRACE_ID);
        assertThat(lines.getLast()).contains("grpc_status=\"INTERNAL\"", "reason=\"IllegalStateException\"", "caller_service=\"unverified\"");
        assertThat(traceOf(lines.getLast())).isNotEqualTo(TRACE_ID);
        assertThat(output.getAll()).doesNotContain("secret@example.test", "broken@example.test", "secret-encoded-hash", "secret-db-value");
    }

    @Test void probesAndMetricsAreAvailableOnTheBoundManagementPort() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            assertThat(get(client, managementPort, "/actuator/health/liveness", null)).isEqualTo(200);
            assertThat(get(client, managementPort, "/actuator/health/readiness", null)).isEqualTo(200);
            assertThat(get(client, managementPort, "/actuator/prometheus", null)).isEqualTo(200);
            assertThat(get(client, port, "/actuator/prometheus", null)).isEqualTo(401);
        }
    }

    private static int get(HttpClient client, int port, String path, String traceparent) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5));
        if (traceparent != null) request.header("traceparent", traceparent);
        return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static GetUserLoginInfoByEmailRequest loginRequest(String email) {
        return GetUserLoginInfoByEmailRequest.newBuilder().setEmail(email).build();
    }

    private static String lineFor(CapturedOutput output, String event) {
        var lines = output.getOut().lines().filter(line -> line.contains("event=\"" + event + "\"")).toList();
        assertThat(lines).hasSize(1);
        return lines.getFirst();
    }

    private static String traceOf(String line) {
        var match = CORRELATION.matcher(line);
        assertThat(match.find()).as("trace and span IDs in %s", line).isTrue();
        assertThat(match.group(2)).isNotEqualTo(PARENT_SPAN_ID);
        return match.group(1);
    }
}
