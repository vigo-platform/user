package ir.vigoapp.user.grpc;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import ir.vigoapp.contracts.user.v1.*;
import ir.vigoapp.user.service.CredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataIntegrityViolationException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
@Component
public class UserGrpcEndpoint extends UserServiceGrpc.UserServiceImplBase {
 private static final Logger log=LoggerFactory.getLogger(UserGrpcEndpoint.class);
 private final CredentialService service;
 public UserGrpcEndpoint(CredentialService service){this.service=service;}
 @Override public void getUserLoginInfoByEmail(GetUserLoginInfoByEmailRequest r,StreamObserver<UserLoginInfoResponse> o){reply(o,()->service.loginByEmail(r.getEmail()),0,0);}
 @Override public void getUserLoginInfoByPhone(GetUserLoginInfoByPhoneRequest r,StreamObserver<UserLoginInfoResponse> o){reply(o,()->service.loginByPhone(r.getPhone()),0,0);}
 @Override public void getUserLoginInfoById(GetUserLoginInfoByIdRequest r,StreamObserver<UserLoginInfoResponse> o){reply(o,()->service.loginById(r.getUserId()),r.getUserId(),0);}
 @Override public void getUserById(GetUserByIdRequest r,StreamObserver<UserAuthInfo> o){reply(o,()->service.byId(r.getUserId()),r.getUserId(),0);}
 @Override public void checkEmailExists(CheckEmailExistsRequest r,StreamObserver<ExistsResponse> o){reply(o,()->ExistsResponse.newBuilder().setExists(service.emailExists(r.getEmail())).build(),0,0);}
 @Override public void checkPhoneExists(CheckPhoneExistsRequest r,StreamObserver<ExistsResponse> o){reply(o,()->ExistsResponse.newBuilder().setExists(service.phoneExists(r.getPhone())).build(),0,0);}
 @Override public void createUser(CreateUserRequest r,StreamObserver<CreateUserResponse> o){reply(o,()->CreateUserResponse.newBuilder().setUser(service.create(r)).build(),0,0);}
 @Override public void changeUserPassword(ChangeUserPasswordRequest r,StreamObserver<ChangeUserPasswordResponse> o){reply(o,()->ChangeUserPasswordResponse.newBuilder().setCredentialVersion(service.change(r)).build(),r.getUserId(),r.getExpectedCredentialVersion());}
 private <T> void reply(StreamObserver<T> out,Supplier<T> work,long userId,long expectedVersion){
  long startedAt=System.nanoTime();
  Status status=Status.OK;String failureType=null;T response=null;
  try {response=work.get();out.onNext(response);out.onCompleted();}
  catch(IllegalArgumentException e){status=Status.INVALID_ARGUMENT.withDescription("Invalid request");}
  catch(DataIntegrityViolationException e){status=Status.ALREADY_EXISTS.withDescription("Identifier already registered");}
  catch(StatusRuntimeException e){status=e.getStatus().withDescription("Operation could not be completed").withCause(null);}
  catch(Exception e){status=Status.INTERNAL.withDescription("Internal service error");failureType=e.getClass().getSimpleName();}
  var call=AuthServiceInterceptor.CALL.get();
  if(call!=null){
   var code=call.isCancelled()?Status.Code.CANCELLED:status.getCode();
   var level=switch(code){
    case OK -> response instanceof ExistsResponse?Level.DEBUG:Level.INFO;
    case CANCELLED,NOT_FOUND,ALREADY_EXISTS -> Level.DEBUG;
    case INTERNAL,UNKNOWN,DATA_LOSS,UNAVAILABLE -> Level.ERROR;
    default -> Level.WARN;
   };
   String identity=AuthServiceInterceptor.CALLER_IDENTITY.get();
   boolean mtls="mtls".equals(identity);
   boolean authCaller=mtls||"dev_inferred".equals(identity);
   var event=log.atLevel(level).addKeyValue("event","grpc_request_completed")
    .addKeyValue("result",code==Status.Code.OK?"SUCCESS":code==Status.Code.CANCELLED?"CANCELLED":"FAILURE")
    .addKeyValue("grpc_method",call.getMethodDescriptor().getFullMethodName()).addKeyValue("grpc_status",code)
    .addKeyValue("caller_service",authCaller?"auth":"unverified").addKeyValue("caller_identity",identity)
    .addKeyValue("transport",mtls?"mtls":"plaintext")
    .addKeyValue("duration_ms",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-startedAt));
   if(call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR) instanceof InetSocketAddress peer)event.addKeyValue("ip_address",peer.getHostString());
   switch(response){
    case UserAuthInfo r -> userId=r.getUserId();
    case UserLoginInfoResponse r -> userId=r.getUser().getUserId();
    case CreateUserResponse r -> userId=r.getUser().getUserId();
    case ChangeUserPasswordResponse r -> event.addKeyValue("credential_version",r.getCredentialVersion());
    case ExistsResponse r -> event.addKeyValue("exists",r.getExists());
    case null, default -> { }
   }
   if(userId>0)event.addKeyValue("user_id",userId);
   if(expectedVersion>0)event.addKeyValue("expected_credential_version",expectedVersion);
   if(failureType!=null)event.addKeyValue("reason",failureType);
   event.log("User gRPC request finished.");
  }
  if(!status.isOk())out.onError(status.asRuntimeException());
 }
}
