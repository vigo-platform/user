package ir.vigoapp.user.grpc;
import io.grpc.*;
import javax.net.ssl.SSLSession;
import java.security.cert.X509Certificate;
import java.util.*;
/** The CA establishes trust; an exact URI SAN establishes Auth service identity. */
public final class AuthServiceInterceptor implements ServerInterceptor {
 private final String allowedUri;private final boolean developmentPlaintext;
 public AuthServiceInterceptor(String allowedUri,boolean developmentPlaintext){this.allowedUri=allowedUri;this.developmentPlaintext=developmentPlaintext;}
 @Override public <ReqT,RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT,RespT> call,Metadata headers,ServerCallHandler<ReqT,RespT> next){
  if(developmentPlaintext)return next.startCall(call,headers);
  try {
   SSLSession session=call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
   if(session==null)throw new SecurityException();
   var certificate=(X509Certificate)session.getPeerCertificates()[0];certificate.checkValidity();
   var sans=certificate.getSubjectAlternativeNames();
   boolean auth=sans!=null&&sans.stream().anyMatch(s->Integer.valueOf(6).equals(s.get(0))&&allowedUri.equals(s.get(1)));
   if(!auth)throw new SecurityException();
   // All eight current RPCs are Auth-only, including advisory existence/account lookups.
   return next.startCall(call,headers);
  }catch(Exception e){call.close(Status.PERMISSION_DENIED.withDescription("Auth service identity required"),new Metadata());return new ServerCall.Listener<>(){};}
 }
}
