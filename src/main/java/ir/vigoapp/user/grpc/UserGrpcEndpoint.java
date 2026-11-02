package ir.vigoapp.user.grpc;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import ir.vigoapp.contracts.user.v1.*;
import ir.vigoapp.user.service.CredentialService;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.function.Supplier;
@Component
public class UserGrpcEndpoint extends UserServiceGrpc.UserServiceImplBase {
 private final CredentialService service;
 public UserGrpcEndpoint(CredentialService service){this.service=service;}
 @Override public void getUserLoginInfoByEmail(GetUserLoginInfoByEmailRequest r,StreamObserver<UserLoginInfoResponse> o){reply(o,()->service.loginByEmail(r.getEmail()));}
 @Override public void getUserLoginInfoByPhone(GetUserLoginInfoByPhoneRequest r,StreamObserver<UserLoginInfoResponse> o){reply(o,()->service.loginByPhone(r.getPhone()));}
 @Override public void getUserLoginInfoById(GetUserLoginInfoByIdRequest r,StreamObserver<UserLoginInfoResponse> o){reply(o,()->service.loginById(r.getUserId()));}
 @Override public void getUserById(GetUserByIdRequest r,StreamObserver<UserAuthInfo> o){reply(o,()->service.byId(r.getUserId()));}
 @Override public void checkEmailExists(CheckEmailExistsRequest r,StreamObserver<ExistsResponse> o){reply(o,()->ExistsResponse.newBuilder().setExists(service.emailExists(r.getEmail())).build());}
 @Override public void checkPhoneExists(CheckPhoneExistsRequest r,StreamObserver<ExistsResponse> o){reply(o,()->ExistsResponse.newBuilder().setExists(service.phoneExists(r.getPhone())).build());}
 @Override public void createUser(CreateUserRequest r,StreamObserver<CreateUserResponse> o){reply(o,()->CreateUserResponse.newBuilder().setUser(service.create(r)).build());}
 @Override public void changeUserPassword(ChangeUserPasswordRequest r,StreamObserver<ChangeUserPasswordResponse> o){reply(o,()->ChangeUserPasswordResponse.newBuilder().setCredentialVersion(service.change(r)).build());}
 private <T> void reply(StreamObserver<T> out,Supplier<T> work){
  try {T response=work.get();out.onNext(response);out.onCompleted();}
  catch(IllegalArgumentException e){out.onError(Status.INVALID_ARGUMENT.withDescription("Invalid request").asRuntimeException());}
  catch(DataIntegrityViolationException e){out.onError(Status.ALREADY_EXISTS.withDescription("Identifier already registered").asRuntimeException());}
  catch(StatusRuntimeException e){out.onError(e.getStatus().withDescription("Operation could not be completed").withCause(null).asRuntimeException());}
  catch(Exception e){out.onError(Status.INTERNAL.withDescription("Internal service error").asRuntimeException());}
 }
}
