package ir.vigoapp.user;
import ir.vigoapp.user.grpc.*;
import ir.vigoapp.user.service.CredentialService;
import ir.vigoapp.contracts.user.v1.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import org.junit.jupiter.api.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class GrpcEndpointTest {
 CredentialService service=mock(CredentialService.class);Server server;ManagedChannel channel;UserServiceGrpc.UserServiceBlockingStub client;
 UserAuthInfo user=UserAuthInfo.newBuilder().setUserId(7).addRoles("ATHLETE").setCredentialVersion(1).setStatus(UserStatus.USER_STATUS_ACTIVE).setCredentialsExpired(false).build();
 @BeforeEach void start()throws Exception{
  String name=InProcessServerBuilder.generateName();server=InProcessServerBuilder.forName(name).directExecutor().addService(new UserGrpcEndpoint(service)).build().start();
  channel=InProcessChannelBuilder.forName(name).directExecutor().build();client=UserServiceGrpc.newBlockingStub(channel);
 }
 @AfterEach void stop()throws Exception{channel.shutdownNow().awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);server.shutdownNow().awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);}
 @Test void everyRpcRoundTripsSyntheticPayloads(){
  var login=UserLoginInfoResponse.newBuilder().setUser(user).setPasswordHash("synthetic-encoded").build();
  when(service.loginByEmail("a@example.test")).thenReturn(login);when(service.loginByPhone("+989121234567")).thenReturn(login);when(service.loginById(7)).thenReturn(login);when(service.byId(7)).thenReturn(user);
  when(service.emailExists("a@example.test")).thenReturn(true);when(service.phoneExists("+989121234567")).thenReturn(false);when(service.create(any())).thenReturn(user);when(service.change(any())).thenReturn(2L);
  assertThat(client.getUserLoginInfoByEmail(GetUserLoginInfoByEmailRequest.newBuilder().setEmail("a@example.test").build())).isEqualTo(login);
  assertThat(client.getUserLoginInfoByPhone(GetUserLoginInfoByPhoneRequest.newBuilder().setPhone("+989121234567").build())).isEqualTo(login);
  assertThat(client.getUserLoginInfoById(GetUserLoginInfoByIdRequest.newBuilder().setUserId(7).build())).isEqualTo(login);
  assertThat(client.getUserById(GetUserByIdRequest.newBuilder().setUserId(7).build())).isEqualTo(user);
  assertThat(client.checkEmailExists(CheckEmailExistsRequest.newBuilder().setEmail("a@example.test").build()).getExists()).isTrue();
  assertThat(client.checkPhoneExists(CheckPhoneExistsRequest.newBuilder().setPhone("+989121234567").build()).getExists()).isFalse();
  assertThat(client.createUser(CreateUserRequest.newBuilder().setEmail("a@example.test").setFirstName("A").setLastName("B").setPasswordHash("synthetic-encoded").build()).getUser()).isEqualTo(user);
  assertThat(client.changeUserPassword(ChangeUserPasswordRequest.newBuilder().setUserId(7).setExpectedCredentialVersion(1).setNewPasswordHash("new-synthetic-encoded").build()).getCredentialVersion()).isEqualTo(2);
 }
 @Test void errorsHaveSafeDescriptionsAndCorrectCodes(){
  when(service.loginById(0)).thenThrow(new IllegalArgumentException("SECRET HASH SHOULD NOT LEAK"));
  assertThatThrownBy(()->client.getUserLoginInfoById(GetUserLoginInfoByIdRequest.getDefaultInstance())).satisfies(e->{assertThat(Status.fromThrowable(e).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);assertThat(e.getMessage()).doesNotContain("SECRET");});
  when(service.change(any())).thenThrow(Status.ABORTED.asRuntimeException());assertThatThrownBy(()->client.changeUserPassword(ChangeUserPasswordRequest.getDefaultInstance())).satisfies(e->assertThat(Status.fromThrowable(e).getCode()).isEqualTo(Status.Code.ABORTED));
  when(service.create(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("secret SQL"));assertThatThrownBy(()->client.createUser(CreateUserRequest.getDefaultInstance())).satisfies(e->assertThat(Status.fromThrowable(e).getCode()).isEqualTo(Status.Code.ALREADY_EXISTS));
 }
 @Test void transportWithoutServiceIdentityCannotCallEvenNonCredentialRpc()throws Exception{
  String name=InProcessServerBuilder.generateName();var secured=InProcessServerBuilder.forName(name).directExecutor().addService(ServerInterceptors.intercept(new UserGrpcEndpoint(service),new AuthServiceInterceptor("spiffe://vigo.local/service/auth",false,""))).build().start();
  var connection=InProcessChannelBuilder.forName(name).directExecutor().build();
  try{assertThatThrownBy(()->UserServiceGrpc.newBlockingStub(connection).checkEmailExists(CheckEmailExistsRequest.getDefaultInstance())).satisfies(e->assertThat(Status.fromThrowable(e).getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));}
  finally{connection.shutdownNow().awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);secured.shutdownNow().awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);}
 }
}
