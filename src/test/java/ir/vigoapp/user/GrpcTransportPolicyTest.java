package ir.vigoapp.user;
import ir.vigoapp.user.grpc.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.stub.StreamObserver;
import javax.net.ssl.SSLSession;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class GrpcTransportPolicyTest {
 @Test void actuatorRegistersGrpcUnderReadinessName(){
  new ApplicationContextRunner()
   .withConfiguration(AutoConfigurations.of(HealthContributorRegistryAutoConfiguration.class))
   .withUserConfiguration(GrpcServerLifecycle.class)
   .withBean(UserGrpcEndpoint.class,()->new UserGrpcEndpoint(mock(ir.vigoapp.user.service.CredentialService.class)))
   .withInitializer(context->context.getEnvironment().setActiveProfiles("dev"))
   .withPropertyValues("app.grpc.dev-plaintext-enabled=true","app.grpc.port=0")
   .run(context->{
    var registry=context.getBean(HealthContributorRegistry.class);
    assertThat(registry.getContributor("grpc")).isSameAs(context.getBean(GrpcServerLifecycle.class));
   });
 }
 @Test void plaintextRequiresDevProfileAndExplicitFlag(){
  var env=new MockEnvironment().withProperty("app.grpc.dev-plaintext-enabled","true");
  var lifecycle=new GrpcServerLifecycle(new UserGrpcEndpoint(mock(ir.vigoapp.user.service.CredentialService.class)),env);
  assertThatThrownBy(lifecycle::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("dev profile");assertThat(lifecycle.isRunning()).isFalse();
 }
 @Test void defaultMissingTlsFailsClosedAndStopIsIdempotent(){
  var lifecycle=new GrpcServerLifecycle(new UserGrpcEndpoint(mock(ir.vigoapp.user.service.CredentialService.class)),new MockEnvironment());
  assertThatThrownBy(lifecycle::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("Secure gRPC startup failed");
  lifecycle.stop();lifecycle.stop();assertThat(lifecycle.isRunning()).isFalse();
 }
 @Test void developmentLifecycleStartsEphemeralLoopbackAndCleansUp(){
  var env=new MockEnvironment().withProperty("app.grpc.dev-plaintext-enabled","true").withProperty("app.grpc.port","0").withProperty("app.grpc.auth-service-identity","spiffe://vigo.local/service/auth");env.setActiveProfiles("dev");
  var lifecycle=new GrpcServerLifecycle(new UserGrpcEndpoint(mock(ir.vigoapp.user.service.CredentialService.class)),env);
  try{lifecycle.start();assertThat(lifecycle.isRunning()).isTrue();lifecycle.start();}finally{lifecycle.stop();}
  assertThat(lifecycle.isRunning()).isFalse();
 }
 @Test void developmentLoopbackListsUserServiceThroughBrunoReflectionProtocol()throws Exception{
  var env=new MockEnvironment().withProperty("app.grpc.dev-plaintext-enabled","true").withProperty("app.grpc.port","0");
  env.setActiveProfiles("dev");
  var lifecycle=new GrpcServerLifecycle(new UserGrpcEndpoint(mock(ir.vigoapp.user.service.CredentialService.class)),env);
  lifecycle.start();
  try{
   var server=(Server)ReflectionTestUtils.getField(lifecycle,"server");
   var channel=NettyChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
   try{
    var response=new CompletableFuture<ServerReflectionResponse>();
    var requests=ServerReflectionGrpc.newStub(channel).withDeadlineAfter(3,TimeUnit.SECONDS)
     .serverReflectionInfo(new StreamObserver<ServerReflectionResponse>(){
      public void onNext(ServerReflectionResponse value){response.complete(value);}
      public void onError(Throwable error){response.completeExceptionally(error);}
      public void onCompleted(){}
     });
    requests.onNext(ServerReflectionRequest.newBuilder().setListServices("").build());
    requests.onCompleted();
    assertThat(response.get(3,TimeUnit.SECONDS).getListServicesResponse().getServiceList())
     .anyMatch(service->service.getName().equals("vigo.user.v1.UserService"));
   }finally{channel.shutdownNow();}
  }finally{lifecycle.stop();}
 }
 @SuppressWarnings("unchecked") @Test void onlyExactAuthUriSanPassesServiceIdentity()throws Exception{
  ServerCall<Object,Object> call=mock(ServerCall.class);ServerCallHandler<Object,Object> next=mock(ServerCallHandler.class);SSLSession session=mock(SSLSession.class);X509Certificate cert=mock(X509Certificate.class);
  when(call.getAttributes()).thenReturn(Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_SSL_SESSION,session).build());when(session.getPeerCertificates()).thenReturn(new java.security.cert.Certificate[]{cert});
  when(cert.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6,"spiffe://vigo.local/service/other"),List.of(2,"auth")));
  var interceptor=new AuthServiceInterceptor("spiffe://vigo.local/service/auth",false);
  interceptor.interceptCall(call,new Metadata(),next);verifyNoInteractions(next);verify(call).close(argThat(s->s.getCode()==Status.Code.PERMISSION_DENIED),any());
  when(cert.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6,"spiffe://vigo.local/service/auth")));
  interceptor.interceptCall(call,new Metadata(),next);verify(next).startCall(eq(call),any());verify(cert,times(2)).checkValidity();
 }
}
