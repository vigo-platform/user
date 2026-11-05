package ir.vigoapp.user.grpc;
import io.grpc.*;
import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;
import java.security.cert.X509Certificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/** The CA establishes trust; an exact URI SAN establishes Auth service identity. */
public final class AuthServiceInterceptor implements ServerInterceptor {
 private static final Logger log=LoggerFactory.getLogger(AuthServiceInterceptor.class);
 private static final Metadata.Key<String> USER_AGENT=Metadata.Key.of("user-agent",Metadata.ASCII_STRING_MARSHALLER);
 static final Context.Key<ServerCall<?,?>> CALL=Context.key("user-grpc-call");
 static final Context.Key<String> CALLER_IDENTITY=Context.key("user-grpc-caller-identity");
 private final String allowedUri;private final boolean developmentPlaintext;private final String devAuthUserAgent;
 public AuthServiceInterceptor(String allowedUri,boolean developmentPlaintext,String devAuthUserAgent){this.allowedUri=allowedUri;this.developmentPlaintext=developmentPlaintext;this.devAuthUserAgent=devAuthUserAgent;}
 @Override public <ReqT,RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT,RespT> call,Metadata headers,ServerCallHandler<ReqT,RespT> next){
  if(developmentPlaintext){
   // Loopback client signatures are a development log hint only.
   boolean authHint=!devAuthUserAgent.isBlank()&&devAuthUserAgent.equals(headers.get(USER_AGENT))
    &&call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR) instanceof InetSocketAddress peer
    &&peer.getAddress()!=null&&peer.getAddress().isLoopbackAddress();
   return Contexts.interceptCall(Context.current().withValues(CALL,call,CALLER_IDENTITY,authHint?"dev_inferred":"unverified"),call,headers,next);
  }
  try {
   SSLSession session=call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
   if(session==null)throw new SecurityException();
   var certificate=(X509Certificate)session.getPeerCertificates()[0];certificate.checkValidity();
   var sans=certificate.getSubjectAlternativeNames();
   boolean auth=sans!=null&&sans.stream().anyMatch(s->Integer.valueOf(6).equals(s.get(0))&&allowedUri.equals(s.get(1)));
   if(!auth)throw new SecurityException();
   // All eight current RPCs are Auth-only, including advisory existence/account lookups.
   return Contexts.interceptCall(Context.current().withValues(CALL,call,CALLER_IDENTITY,"mtls"),call,headers,next);
  }catch(Exception e){
   var event=log.atWarn().addKeyValue("event","grpc_access_denied").addKeyValue("result","REJECTED")
    .addKeyValue("grpc_method",call.getMethodDescriptor().getFullMethodName()).addKeyValue("grpc_status",Status.Code.PERMISSION_DENIED)
    .addKeyValue("caller_service","unverified").addKeyValue("transport","mtls").addKeyValue("reason","auth_service_identity_required");
   if(call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR) instanceof InetSocketAddress peer)event.addKeyValue("ip_address",peer.getHostString());
   event.log("User gRPC request rejected because Auth service identity could not be verified.");
   call.close(Status.PERMISSION_DENIED.withDescription("Auth service identity required"),new Metadata());return new ServerCall.Listener<>(){};
  }
 }
}
