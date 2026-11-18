package ir.vigoapp.user.service;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.outbox.OutboxWriter;
import ir.vigoapp.contracts.user.v1.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import java.time.*;
import java.util.*;
import io.grpc.Status;
@Service @Transactional
public class CredentialService {
 private final UserRepository users; private final OutboxWriter events; private final Duration maxAge;
 private final CoachInvitationRepository invitations; private final CoachApplicationRepository applications; private final CoachRepository coaches;
 public CredentialService(UserRepository users,OutboxWriter events,@Value("${app.credentials-max-age:PT0S}") Duration maxAge,
   CoachInvitationRepository invitations,CoachApplicationRepository applications,CoachRepository coaches){
  if(maxAge.isNegative())throw new IllegalArgumentException("Credential max age cannot be negative");this.users=users;this.events=events;this.maxAge=maxAge;
  this.invitations=invitations;this.applications=applications;this.coaches=coaches;
 }
 public UserAuthInfo byId(long id){Identifiers.id(id);return info(users.findById(id).orElseThrow(()->Status.NOT_FOUND.asRuntimeException()));}
 public UserLoginInfoResponse loginById(long id){Identifiers.id(id);return login(users.findById(id));}
 public UserLoginInfoResponse loginByEmail(String email){return login(users.findByEmail(Identifiers.email(email)));}
 public UserLoginInfoResponse loginByPhone(String phone){return login(users.findByPhone(Identifiers.phone(phone)));}
 public boolean emailExists(String email){return users.existsByEmail(Identifiers.email(email));}
 public boolean phoneExists(String phone){return users.existsByPhone(Identifiers.phone(phone));}
 public UserAuthInfo create(CreateUserRequest r){
  String email=r.hasEmail()?Identifiers.email(r.getEmail()):null;
  String phone=r.hasPhone()?Identifiers.phone(r.getPhone()):null;
  if(email==null&&phone==null)throw new IllegalArgumentException("Identifier required");
  Identifiers.hash(r.getPasswordHash());
  var u=new UserAccount();u.email=email;u.phone=phone;u.displayName=Identifiers.name(r.getFirstName())+" "+Identifiers.name(r.getLastName());u.replacePassword(r.getPasswordHash());
  // Approved D5: restricted Auth CreateUser attests email OTP was verified before signup.
  // Phone verification is not attested by this contract and remains false.
  u.emailVerified=email!=null;
  u.phoneVerified=false;
  // Role, status and credential version are server-assigned defaults. A pending
  // coach invitation matched to this verified email is the only way this call
  // can create a COACH instead; the invitation is consumed exactly once.
  var invitation=email==null?Optional.<CoachInvitation>empty()
    :invitations.lockActiveByEmail(email,Instant.now());
  invitation.ifPresent(i->u.role=Role.COACH);
  users.saveAndFlush(u);
  invitation.ifPresent(i->{
   i.consumedAt=Instant.now();i.consumedUserId=u.id;
   var application=applications.findById(i.applicationId).orElse(null);
   if(application!=null) {
    var c=new CoachProfile();c.user=u;c.displayName=application.fullName;c.headline=application.headline;
    c.bio=application.bio;c.specialties=application.specialties;c.experienceYears=application.experienceYears;
    c.certifications=application.certifications;c.instagramHandle=application.instagramHandle;
    c.resultImageUrls="[]";c.status=CoachStatus.APPROVED;coaches.saveAndFlush(c);
   }
  });
  Map<String,Object> data=new LinkedHashMap<>();data.put("user_id",u.publicId);data.put("email",email);data.put("phone",phone);data.put("display_name",u.displayName);data.put("role",u.role);data.put("status",u.status);
  events.emit(u,"UserRegistered",data);return info(u);
 }
 public long change(ChangeUserPasswordRequest r){
  Identifiers.id(r.getUserId());Identifiers.hash(r.getNewPasswordHash());
  if(r.getExpectedCredentialVersion()<=0||r.getExpectedCredentialVersion()==Long.MAX_VALUE)throw new IllegalArgumentException("Invalid credential version");
  int changed=users.changePassword(r.getUserId(),r.getExpectedCredentialVersion(),r.getNewPasswordHash(),Instant.now());
  if(changed==0){if(!users.existsById(r.getUserId()))throw Status.NOT_FOUND.asRuntimeException();throw Status.ABORTED.asRuntimeException();}
  return r.getExpectedCredentialVersion()+1;
 }
 private UserLoginInfoResponse login(Optional<UserAccount> result){var u=result.orElseThrow(()->Status.NOT_FOUND.asRuntimeException());return UserLoginInfoResponse.newBuilder().setUser(info(u)).setPasswordHash(u.passwordHash()).build();}
 public UserAuthInfo info(UserAccount u){
  var b=UserAuthInfo.newBuilder().setUserId(u.id).addRoles(u.role.name())
   .setStatus(u.status==AccountStatus.ACTIVE?UserStatus.USER_STATUS_ACTIVE:UserStatus.USER_STATUS_SUSPENDED)
   .setEmailVerified(u.email!=null&&u.emailVerified).setPhoneVerified(u.phone!=null&&u.phoneVerified)
   .setTwoFactorEnabled(u.twoFactorAuthentication)
   .setCredentialVersion(u.credentialVersion).setCredentialsExpired(expired(u.credentialsChangedAt,Instant.now(),maxAge));
  if(u.email!=null)b.setEmail(u.email);if(u.phone!=null)b.setPhone(u.phone);return b.build();
 }
 public static boolean expired(Instant changed,Instant now,Duration age){return !age.isZero()&&!now.isBefore(changed.plus(age));}
}
