package ir.vigoapp.user.grpc;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.Environment;
import org.springframework.boot.health.contributor.*;
import java.net.InetSocketAddress;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
@Component("grpcHealthIndicator")
public class GrpcServerLifecycle implements SmartLifecycle, HealthIndicator {
 private static final Logger log=LoggerFactory.getLogger(GrpcServerLifecycle.class);
 private final UserGrpcEndpoint endpoint;private final Environment env;private final ObservationRegistry observations;private volatile Server server;
 public GrpcServerLifecycle(UserGrpcEndpoint endpoint,Environment env,ObservationRegistry observations){this.endpoint=endpoint;this.env=env;this.observations=observations;}
 @Override public synchronized void start(){
  if(isRunning())return;
  boolean plaintext=env.getProperty("app.grpc.dev-plaintext-enabled",Boolean.class,false);
  boolean dev=Arrays.asList(env.getActiveProfiles()).contains("dev");
  if(plaintext&&!dev){
   log.atError().addKeyValue("event","grpc_server_start_failed").addKeyValue("reason","plaintext_requires_dev_profile")
    .log("gRPC server cannot start in plaintext outside the dev profile.");
   throw new IllegalStateException("Plaintext requires explicit dev profile and enable flag");
  }
  String transport=plaintext?"plaintext":"mtls";
  String identity=env.getProperty("app.grpc.auth-service-identity","");
  String stage="server_configuration";
  try {
   var bindAddress=new InetSocketAddress(env.getProperty("app.grpc.bind-address","127.0.0.1"),env.getProperty("app.grpc.port",Integer.class,50051));
   var builder=NettyServerBuilder.forAddress(bindAddress)
    .maxInboundMessageSize(65536).maxConcurrentCallsPerConnection(100)
    .addService(ServerInterceptors.interceptForward(endpoint,new ObservationGrpcServerInterceptor(observations),
     new AuthServiceInterceptor(identity,plaintext,env.getProperty("app.grpc.dev-auth-user-agent",""))));
   boolean reflection=plaintext&&!bindAddress.isUnresolved()&&bindAddress.getAddress().isLoopbackAddress();
   if(reflection){
    builder.addService(ProtoReflectionServiceV1.newInstance());
    // Bruno's reflection client uses the v1alpha endpoint.
    builder.addService(ProtoReflectionService.newInstance());
   }
   if(!plaintext){
    stage="tls_configuration";
    String cert=required("app.grpc.certificate"),key=required("app.grpc.private-key"),trust=required("app.grpc.trust-certificate");
    if(identity.isBlank()||!identity.startsWith("spiffe://"))throw new IllegalStateException("Exact Auth SPIFFE URI SAN required");
    builder.sslContext(GrpcSslContexts.forServer(new File(cert),new File(key)).trustManager(new File(trust)).clientAuth(ClientAuth.REQUIRE).build());
   }
   stage="listen";
   server=builder.build().start();
   log.atInfo().addKeyValue("event","grpc_server_started").addKeyValue("bind_address",bindAddress.getHostString())
    .addKeyValue("port",server.getPort()).addKeyValue("transport",transport).addKeyValue("reflection_enabled",reflection)
    .log("User gRPC server is listening.");
  }catch(Exception e){
   log.atError().addKeyValue("event","grpc_server_start_failed").addKeyValue("transport",transport).addKeyValue("stage",stage)
    .addKeyValue("reason",NestedExceptionUtils.getMostSpecificCause(e).getClass().getSimpleName()).log("User gRPC server could not start.");
   if(server!=null)server.shutdownNow();server=null;if(e instanceof IllegalStateException ise&&ise.getMessage()!=null&&ise.getMessage().startsWith("Exact Auth"))throw ise;throw new IllegalStateException("Secure gRPC startup failed; check transport configuration");
  }
 }
 private String required(String name){String value=env.getProperty(name,"");if(value.isBlank())throw new IllegalStateException("TLS configuration required");return value;}
 @Override public synchronized void stop(){
  if(server==null)return;server.shutdown();
  try{if(!server.awaitTermination(15,TimeUnit.SECONDS)){server.shutdownNow();server.awaitTermination(5,TimeUnit.SECONDS);}}
  catch(InterruptedException e){server.shutdownNow();Thread.currentThread().interrupt();}
  finally{
   boolean terminated=server.isTerminated();server=null;
   if(terminated)log.atInfo().addKeyValue("event","grpc_server_stopped").log("User gRPC server stopped.");
   else log.atWarn().addKeyValue("event","grpc_server_stop_incomplete").log("gRPC server termination did not complete before shutdown continued.");
  }
 }
 @Override public void stop(Runnable callback){try{stop();}finally{callback.run();}}
 @Override public boolean isRunning(){return server!=null&&!server.isShutdown();}
 @Override public int getPhase(){return Integer.MAX_VALUE-100;}
 @Override public Health health(){Server current=server;return current!=null&&!current.isShutdown()?Health.up().build():Health.down().build();}
}
