package ir.vigoapp.user.grpc;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import org.springframework.stereotype.Component;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.boot.health.contributor.*;
import java.net.InetSocketAddress;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
@Component("grpcHealthIndicator")
public class GrpcServerLifecycle implements SmartLifecycle, HealthIndicator {
 private final UserGrpcEndpoint endpoint;private final Environment env;private volatile Server server;
 public GrpcServerLifecycle(UserGrpcEndpoint endpoint,Environment env){this.endpoint=endpoint;this.env=env;}
 @Override public synchronized void start(){
  if(isRunning())return;
  boolean plaintext=env.getProperty("app.grpc.dev-plaintext-enabled",Boolean.class,false);
  boolean dev=Arrays.asList(env.getActiveProfiles()).contains("dev");
  if(plaintext&&!dev)throw new IllegalStateException("Plaintext requires explicit dev profile and enable flag");
  String identity=env.getProperty("app.grpc.auth-service-identity","");
  try {
   var bindAddress=new InetSocketAddress(env.getProperty("app.grpc.bind-address","127.0.0.1"),env.getProperty("app.grpc.port",Integer.class,50051));
   var builder=NettyServerBuilder.forAddress(bindAddress)
    .maxInboundMessageSize(65536).maxConcurrentCallsPerConnection(100)
    .addService(ServerInterceptors.intercept(endpoint,new AuthServiceInterceptor(identity,plaintext)));
   if(plaintext&&!bindAddress.isUnresolved()&&bindAddress.getAddress().isLoopbackAddress()){
    builder.addService(ProtoReflectionServiceV1.newInstance());
    // Bruno's reflection client uses the v1alpha endpoint.
    builder.addService(ProtoReflectionService.newInstance());
   }
   if(!plaintext){
    String cert=required("app.grpc.certificate"),key=required("app.grpc.private-key"),trust=required("app.grpc.trust-certificate");
    if(identity.isBlank()||!identity.startsWith("spiffe://"))throw new IllegalStateException("Exact Auth SPIFFE URI SAN required");
    builder.sslContext(GrpcSslContexts.forServer(new File(cert),new File(key)).trustManager(new File(trust)).clientAuth(ClientAuth.REQUIRE).build());
   }
   server=builder.build().start();
  }catch(Exception e){if(server!=null)server.shutdownNow();server=null;if(e instanceof IllegalStateException ise&&ise.getMessage()!=null&&ise.getMessage().startsWith("Exact Auth"))throw ise;throw new IllegalStateException("Secure gRPC startup failed; check transport configuration");}
 }
 private String required(String name){String value=env.getProperty(name,"");if(value.isBlank())throw new IllegalStateException("TLS configuration required");return value;}
 @Override public synchronized void stop(){
  if(server==null)return;server.shutdown();
  try{if(!server.awaitTermination(15,TimeUnit.SECONDS)){server.shutdownNow();server.awaitTermination(5,TimeUnit.SECONDS);}}
  catch(InterruptedException e){server.shutdownNow();Thread.currentThread().interrupt();}
  finally{server=null;}
 }
 @Override public void stop(Runnable callback){try{stop();}finally{callback.run();}}
 @Override public boolean isRunning(){return server!=null&&!server.isShutdown();}
 @Override public int getPhase(){return Integer.MAX_VALUE-100;}
 @Override public Health health(){Server current=server;return current!=null&&!current.isShutdown()?Health.up().build():Health.down().build();}
}
