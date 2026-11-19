package ir.vigoapp.user;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.entity.UserAccount;
import ir.vigoapp.user.outbox.OutboxWriter;
import ir.vigoapp.user.repository.CoachApplicationRepository;
import ir.vigoapp.user.repository.CoachInvitationRepository;
import ir.vigoapp.user.repository.CoachRepository;
import ir.vigoapp.user.repository.UserRepository;
import ir.vigoapp.user.service.*;
import ir.vigoapp.contracts.user.v1.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class CredentialServiceTest {
 UserRepository users=mock(UserRepository.class);OutboxWriter events=mock(OutboxWriter.class);
 CoachInvitationRepository invitations=mock(CoachInvitationRepository.class);CoachApplicationRepository applications=mock(CoachApplicationRepository.class);CoachRepository coaches=mock(CoachRepository.class);
 CredentialService service=new CredentialService(users,events,Duration.ZERO,invitations,applications,coaches);
 @Test void canonicalIdentifiersAndInvalidInputs(){
  assertThat(Identifiers.email(" Alice@EXAMPLE.COM ")).isEqualTo("alice@example.com");
  assertThat(Identifiers.phone(" +989121234567 ")).isEqualTo("+989121234567");
  for(String invalid:List.of("", "x", "a@", "a@.example.com", "a@example..com")){
   assertThatIllegalArgumentException().isThrownBy(()->Identifiers.email(invalid));
  }
  assertThatIllegalArgumentException().isThrownBy(()->Identifiers.phone("09121234567"));
 }
 @Test void createAssignsDefaultsPreservesHashAndPublishesNumericId(){
  when(users.saveAndFlush(any())).thenAnswer(i->{UserAccount u=i.getArgument(0);u.setId(4L);return u;});
  String encoded="{argon2}$synthetic$encoded-hash";
  var response=service.create(CreateUserRequest.newBuilder().setEmail(" A@example.COM ").setFirstName("A").setLastName("B").setPasswordHash(encoded).build());
  assertThat(response.getUserId()).isEqualTo(4);
  assertThat(response.getRolesList()).containsExactly("ATHLETE");assertThat(response.getStatus()).isEqualTo(UserStatus.USER_STATUS_ACTIVE);
  assertThat(response.getCredentialVersion()).isEqualTo(1);assertThat(response.getCredentialsExpired()).isFalse();assertThat(response.getEmailVerified()).isTrue();assertThat(response.getPhoneVerified()).isFalse();
  assertThat(response.getTwoFactorEnabled()).isFalse();
  ArgumentCaptor<UserAccount> capture=ArgumentCaptor.forClass(UserAccount.class);verify(users).saveAndFlush(capture.capture());
  assertThat(capture.getValue().passwordHash()).isEqualTo(encoded);assertThat(capture.getValue().toString()).doesNotContain(encoded);
  assertThat(capture.getValue().getId()).isEqualTo(4L);
  verify(events).emit(eq(capture.getValue()),eq("UserRegistered"),argThat(data->capture.getValue().getId().equals(data.get("user_id"))));
 }
 @Test void phoneOnlySignupDoesNotInventVerifiedEmailOrPhone(){
  when(users.saveAndFlush(any())).thenAnswer(i->{UserAccount u=i.getArgument(0);u.setId(8L);return u;});
  var response=service.create(CreateUserRequest.newBuilder().setPhone("+989121234567").setFirstName("A").setLastName("B").setPasswordHash("synthetic").build());
  assertThat(response.hasEmail()).isFalse();assertThat(response.getEmailVerified()).isFalse();assertThat(response.getPhoneVerified()).isFalse();
 }
 @Test void expirationIsIndependentAndDisabledByDefault(){
  Instant now=Instant.now();assertThat(CredentialService.expired(now.minus(Duration.ofDays(500)),now,Duration.ZERO)).isFalse();
  assertThat(CredentialService.expired(now.minus(Duration.ofDays(90)),now,Duration.ofDays(90))).isTrue();
  assertThat(CredentialService.expired(now,now,Duration.ofDays(90))).isFalse();
 }
 @Test void accountResponsesPopulateExpirationAndNumericId(){
  var u=new UserAccount();u.setId(5L);u.setEmail("a@example.com");u.setDisplayName("A B");u.replacePassword("unchanged");u.setCredentialsChangedAt(Instant.now().minus(Duration.ofDays(100)));
  service=new CredentialService(users,events,Duration.ofDays(90),invitations,applications,coaches);when(users.findById(5L)).thenReturn(Optional.of(u));when(users.findByEmail(u.getEmail())).thenReturn(Optional.of(u));
  var login=service.loginByEmail("A@example.com");assertThat(login.getPasswordHash()).isEqualTo("unchanged");assertThat(login.getUser().getUserId()).isEqualTo(u.getId());
  assertThat(service.byId(5).getCredentialsExpired()).isTrue();assertThat(service.loginById(5).getUser().getCredentialsExpired()).isTrue();
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
 void everyAccountLookupReturnsStoredTwoFactorSetting(boolean enabled){
  var u=new UserAccount();u.setId(9L);u.setEmail("twofactor@example.com");u.setPhone("+989121234567");u.replacePassword("encoded");
  u.setTwoFactorAuthentication(enabled);
  when(users.findById(u.getId())).thenReturn(Optional.of(u));
  when(users.findByEmail(u.getEmail())).thenReturn(Optional.of(u));
  when(users.findByPhone(u.getPhone())).thenReturn(Optional.of(u));
  assertThat(service.byId(u.getId()).getTwoFactorEnabled()).isEqualTo(enabled);
  assertThat(service.loginById(u.getId()).getUser().getTwoFactorEnabled()).isEqualTo(enabled);
  assertThat(service.loginByEmail(u.getEmail()).getUser().getTwoFactorEnabled()).isEqualTo(enabled);
  assertThat(service.loginByPhone(u.getPhone()).getUser().getTwoFactorEnabled()).isEqualTo(enabled);
 }
 @Test void atomicCasReportsAbortedOrNotFound(){
  var request=ChangeUserPasswordRequest.newBuilder().setUserId(2).setExpectedCredentialVersion(4).setNewPasswordHash("encoded").build();
  when(users.existsById(2L)).thenReturn(true);
  assertThatThrownBy(()->service.change(request)).isInstanceOf(io.grpc.StatusRuntimeException.class).satisfies(e->assertThat(io.grpc.Status.fromThrowable(e).getCode()).isEqualTo(io.grpc.Status.Code.ABORTED));
  when(users.changePassword(eq(2L),eq(4L),eq("encoded"),any())).thenReturn(1);assertThat(service.change(request)).isEqualTo(5);
 }
}
