package ir.vigoapp.user;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.service.*;
import ir.vigoapp.user.outbox.OutboxWriter;
import ir.vigoapp.contracts.user.v1.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CredentialServiceTest {
 UserRepository users=mock(UserRepository.class);OutboxWriter events=mock(OutboxWriter.class);
 CredentialService service=new CredentialService(users,events,Duration.ZERO);
 @Test void canonicalIdentifiersAndInvalidInputs(){
  assertThat(Identifiers.email(" Alice@EXAMPLE.COM ")).isEqualTo("alice@example.com");
  assertThat(Identifiers.phone(" +989121234567 ")).isEqualTo("+989121234567");
  for(String invalid:List.of("", "x", "a@", "a@.example.com", "a@example..com")){
   assertThatIllegalArgumentException().isThrownBy(()->Identifiers.email(invalid));
  }
  assertThatIllegalArgumentException().isThrownBy(()->Identifiers.phone("09121234567"));
 }
 @Test void createAssignsDefaultsPreservesHashAndPublishesPublicId(){
  when(users.saveAndFlush(any())).thenAnswer(i->{UserAccount u=i.getArgument(0);u.id=4L;return u;});
  String encoded="{argon2}$synthetic$encoded-hash";
  var response=service.create(CreateUserRequest.newBuilder().setEmail(" A@example.COM ").setFirstName("A").setLastName("B").setPasswordHash(encoded).build());
  assertThat(response.getUserId()).isEqualTo(4);
  assertThat(response.getRolesList()).containsExactly("ATHLETE");assertThat(response.getStatus()).isEqualTo(UserStatus.USER_STATUS_ACTIVE);
  assertThat(response.getCredentialVersion()).isEqualTo(1);assertThat(response.getCredentialsExpired()).isFalse();assertThat(response.getEmailVerified()).isTrue();assertThat(response.getPhoneVerified()).isFalse();
  assertThat(response.getTwoFactorEnabled()).isFalse();
  ArgumentCaptor<UserAccount> capture=ArgumentCaptor.forClass(UserAccount.class);verify(users).saveAndFlush(capture.capture());
  assertThat(capture.getValue().passwordHash()).isEqualTo(encoded);assertThat(capture.getValue().toString()).doesNotContain(encoded);
  assertThat(capture.getValue().publicId).isNotNull();
  verify(events).emit(eq(capture.getValue()),eq("UserRegistered"),argThat(data->capture.getValue().publicId.equals(data.get("user_id"))));
 }
 @Test void phoneOnlySignupDoesNotInventVerifiedEmailOrPhone(){
  when(users.saveAndFlush(any())).thenAnswer(i->{UserAccount u=i.getArgument(0);u.id=8L;return u;});
  var response=service.create(CreateUserRequest.newBuilder().setPhone("+989121234567").setFirstName("A").setLastName("B").setPasswordHash("synthetic").build());
  assertThat(response.hasEmail()).isFalse();assertThat(response.getEmailVerified()).isFalse();assertThat(response.getPhoneVerified()).isFalse();
 }
 @Test void expirationIsIndependentAndDisabledByDefault(){
  Instant now=Instant.now();assertThat(CredentialService.expired(now.minus(Duration.ofDays(500)),now,Duration.ZERO)).isFalse();
  assertThat(CredentialService.expired(now.minus(Duration.ofDays(90)),now,Duration.ofDays(90))).isTrue();
  assertThat(CredentialService.expired(now,now,Duration.ofDays(90))).isFalse();
 }
 @Test void accountResponsesPopulateExpirationAndNumericId(){
  var u=new UserAccount();u.id=5L;u.email="a@example.com";u.displayName="A B";u.replacePassword("unchanged");u.credentialsChangedAt=Instant.now().minus(Duration.ofDays(100));
  service=new CredentialService(users,events,Duration.ofDays(90));when(users.findById(5L)).thenReturn(Optional.of(u));when(users.findByEmail(u.email)).thenReturn(Optional.of(u));
  var login=service.loginByEmail("A@example.com");assertThat(login.getPasswordHash()).isEqualTo("unchanged");assertThat(login.getUser().getUserId()).isEqualTo(u.id);
  assertThat(service.byId(5).getCredentialsExpired()).isTrue();assertThat(service.loginById(5).getUser().getCredentialsExpired()).isTrue();
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void everyAccountLookupReturnsStoredTwoFactorSetting(boolean enabled){
  var u=new UserAccount();u.id=9L;u.email="twofactor@example.com";u.phone="+989121234567";u.replacePassword("encoded");
  u.twoFactorAuthentication=enabled;
  when(users.findById(u.id)).thenReturn(Optional.of(u));
  when(users.findByEmail(u.email)).thenReturn(Optional.of(u));
  when(users.findByPhone(u.phone)).thenReturn(Optional.of(u));
  assertThat(service.byId(u.id).getTwoFactorEnabled()).isEqualTo(enabled);
  assertThat(service.loginById(u.id).getUser().getTwoFactorEnabled()).isEqualTo(enabled);
  assertThat(service.loginByEmail(u.email).getUser().getTwoFactorEnabled()).isEqualTo(enabled);
  assertThat(service.loginByPhone(u.phone).getUser().getTwoFactorEnabled()).isEqualTo(enabled);
 }
 @Test void atomicCasReportsAbortedOrNotFound(){
  var request=ChangeUserPasswordRequest.newBuilder().setUserId(2).setExpectedCredentialVersion(4).setNewPasswordHash("encoded").build();
  when(users.existsById(2L)).thenReturn(true);
  assertThatThrownBy(()->service.change(request)).isInstanceOf(io.grpc.StatusRuntimeException.class).satisfies(e->assertThat(io.grpc.Status.fromThrowable(e).getCode()).isEqualTo(io.grpc.Status.Code.ABORTED));
  when(users.changePassword(eq(2L),eq(4L),eq("encoded"),any())).thenReturn(1);assertThat(service.change(request)).isEqualTo(5);
 }
}
