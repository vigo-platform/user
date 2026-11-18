package ir.vigoapp.user.service;
import ir.vigoapp.user.api.*;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
/**
 * Anonymous coach application workflow.
 *
 * <p>A submission creates an application record only. Approval creates a
 * single-use, time-limited invitation bound to the applicant email; it still
 * creates no account and grants no role. The COACH role is assigned later, when
 * the applicant registers and Auth proves control of that email.
 */
@Service @Transactional
public class CoachApplicationService {
 private static final Logger log=LoggerFactory.getLogger(CoachApplicationService.class);
 private final CoachApplicationRepository applications;
 private final CoachApplicationTokenRepository tokens;
 private final CoachInvitationRepository invitations;
 private final CoachCertificateRepository certificates;
 private final CoachExperienceRepository experiences;
 private final UserRepository users;
 private final OutboxWriter events;
 private final ObjectMapper json;
 private final Duration invitationTtl;
 private final Duration lookupTtl;
 public CoachApplicationService(CoachApplicationRepository applications,CoachApplicationTokenRepository tokens,
   CoachInvitationRepository invitations,CoachCertificateRepository certificates,CoachExperienceRepository experiences,
   UserRepository users,OutboxWriter events,ObjectMapper json,
   @Value("${app.coach-applications.invitation-ttl:P30D}") Duration invitationTtl,
   @Value("${app.coach-applications.lookup-ttl:P90D}") Duration lookupTtl) {
  if(invitationTtl.isNegative()||invitationTtl.isZero())throw new IllegalArgumentException("Invitation TTL must be positive");
  if(lookupTtl.isNegative()||lookupTtl.isZero())throw new IllegalArgumentException("Lookup TTL must be positive");
  this.applications=applications;this.tokens=tokens;this.invitations=invitations;this.certificates=certificates;
  this.experiences=experiences;this.users=users;this.events=events;this.json=json;
  this.invitationTtl=invitationTtl;this.lookupTtl=lookupTtl;
 }
 /**
  * Submits an application without authenticating the caller.
  *
  * <p>The response is identical whether the email is new or already pending, so a
  * caller cannot use this endpoint to discover whether an address has applied.
  * The transaction-scoped advisory lock serialises concurrent submissions of the
  * same email, which keeps that property true under a race as well.
  */
 public Map<String,Object> submit(Requests.Application r) {
  String email=Identifiers.email(r.email());
  String phone=r.phone()==null?null:Identifiers.phone(r.phone());
  applications.lockEmailForSubmission(email);
  var pending=applications.findByEmailIgnoreCaseAndStatus(email,CoachApplicationStatus.PENDING);
  if(pending.isPresent())return summary(pending.get(),issueLookupToken(pending.get()));
  String firstName=Identifiers.name(r.first_name());
  String lastName=Identifiers.name(r.last_name());
  var a=new CoachApplication();
  a.firstName=firstName;a.lastName=lastName;
  a.fullName=(firstName+" "+lastName).strip();
  a.profileImageFileId=uuidOrNull(r.profile_image_file_id());
  a.email=email;a.phone=phone;a.headline=blankToNull(r.headline());
  a.bio=r.bio().strip();
  a.specialties=json.writeValueAsString(r.specialties().stream().map(String::strip).toList());
  a.experienceYears=r.experience_years();
  // Legacy column kept for the existing contract; structured rows are authoritative.
  a.certifications=json.writeValueAsString(r.certificates()==null?List.of():r.certificates());
  a.instagramHandle=blankToNull(r.instagram_handle());
  a.locale=r.locale()==null?"fa":r.locale();
  a.status=CoachApplicationStatus.PENDING;
  a.aggregateVersion=1;
  try { applications.saveAndFlush(a); }
  catch(DataIntegrityViolationException unexpected) {
   // The unique index is a backstop; the advisory lock should make this unreachable.
   throw ApiException.conflict("APPLICATION_ALREADY_PENDING");
  }
  storeCertificates(a,r.certificates());
  storeExperiences(a,r.experiences());
  String lookupToken=issueLookupToken(a);
  Map<String,Object> data=new LinkedHashMap<>();
  data.put("application_id",a.id);data.put("full_name",a.fullName);data.put("email",a.email);
  data.put("phone",a.phone);data.put("locale",a.locale);data.put("status",a.status);
  data.put("submitted_at",a.createdAt);
  events.emit(a.id,a.aggregateVersion,"CoachApplicationSubmitted",data);
  log.atInfo().addKeyValue("event","coach_application_submitted").addKeyValue("result","STAGED")
   .addKeyValue("application_id",a.id).log("Public coach application staged for review.");
  return summary(a,lookupToken);
 }
 @Transactional(readOnly=true)
 public Map<String,Object> status(String lookupToken) {
  if(!OpaqueTokens.looksValid(lookupToken))throw ApiException.notFound();
  var token=tokens.findByTokenHashAndExpiresAtAfter(OpaqueTokens.digest(lookupToken),Instant.now()).orElseThrow(ApiException::notFound);
  var a=applications.findById(token.applicationId).orElseThrow(ApiException::notFound);
  return Views.applicationStatus(a);
 }
 @Transactional(readOnly=true)
 public Views.Paged<Map<String,Object>> list(int page,int size,CoachApplicationStatus status) {
  var pageable=PageRequest.of(page,size,Sort.by("createdAt").descending().and(Sort.by("id")));
  var result=status==null?applications.findAll(pageable):applications.findAllByStatus(status,pageable);
  return Views.page(result,a->view(a,invitationExpiry(a.id)));
 }
 @Transactional(readOnly=true)
 public Map<String,Object> get(UUID id) {
  var a=applications.findById(id).orElseThrow(ApiException::notFound);
  return view(a,invitationExpiry(id));
 }
 public Map<String,Object> approve(UUID actor,UUID id) {
  var a=applications.lock(id).orElseThrow(ApiException::notFound);
  if(a.status!=CoachApplicationStatus.PENDING)throw ApiException.conflict("INVALID_STATE");
  // An invitation must be the only route to COACH. Refuse to issue one for an
  // address that already has an account; the admin can reject instead.
  if(users.existsByEmail(a.email))throw ApiException.conflict("EMAIL_ALREADY_REGISTERED");
  if(invitations.existsByApplicationId(a.id))throw ApiException.conflict("INVALID_STATE");
  Instant now=Instant.now();
  String token=OpaqueTokens.newToken();
  var invitation=new CoachInvitation();
  invitation.applicationId=a.id;invitation.email=a.email;
  invitation.tokenHash=OpaqueTokens.digest(token);
  invitation.createdAt=now;invitation.expiresAt=now.plus(invitationTtl);
  invitations.saveAndFlush(invitation);
  a.status=CoachApplicationStatus.APPROVED;a.rejectionReason=null;
  a.reviewedBy=actor;a.reviewedAt=now;a.updatedAt=now;a.aggregateVersion++;
  Map<String,Object> data=new LinkedHashMap<>();
  data.put("application_id",a.id);data.put("full_name",a.fullName);data.put("email",a.email);
  data.put("locale",a.locale);data.put("status",a.status);
  data.put("invitation_token",token);data.put("invitation_expires_at",invitation.expiresAt);
  data.put("reviewed_by",actor);data.put("reviewed_at",now);
  events.emit(a.id,a.aggregateVersion,"CoachApplicationApproved",data);
  log.atInfo().addKeyValue("event","coach_application_approved").addKeyValue("result","STAGED")
   .addKeyValue("application_id",a.id).addKeyValue("actor_public_id",actor)
   .log("Coach application approved and a registration invitation issued.");
  return view(a,invitation.expiresAt);
 }
 public Map<String,Object> reject(UUID actor,UUID id,String reason) {
  var a=applications.lock(id).orElseThrow(ApiException::notFound);
  if(a.status!=CoachApplicationStatus.PENDING)throw ApiException.conflict("INVALID_STATE");
  Instant now=Instant.now();
  a.status=CoachApplicationStatus.REJECTED;a.rejectionReason=reason.strip();
  a.reviewedBy=actor;a.reviewedAt=now;a.updatedAt=now;a.aggregateVersion++;
  Map<String,Object> data=new LinkedHashMap<>();
  data.put("application_id",a.id);data.put("full_name",a.fullName);data.put("email",a.email);
  data.put("locale",a.locale);data.put("status",a.status);data.put("reason",a.rejectionReason);
  data.put("reviewed_by",actor);data.put("reviewed_at",now);
  events.emit(a.id,a.aggregateVersion,"CoachApplicationRejected",data);
  log.atInfo().addKeyValue("event","coach_application_rejected").addKeyValue("result","STAGED")
   .addKeyValue("application_id",a.id).addKeyValue("actor_public_id",actor).log("Coach application rejected.");
  return view(a,null);
 }
 private void storeCertificates(CoachApplication a,List<Requests.Certificate> rows) {
  if(rows==null)return;
  for(int i=0;i<rows.size();i++) {
   var source=rows.get(i);
   var row=new CoachCertificate();
   row.applicationId=a.id;
   row.category=CoachCertificate.Category.valueOf(source.category().name());
   row.title=source.title().strip();
   row.issuer=blankToNull(source.issuer());
   if(source.category()==Requests.CertificateCategory.UNIVERSITY) {
    row.fieldOfStudy=blankToNull(source.field_of_study());
    row.degree=blankToNull(source.degree());
   } else if(source.field_of_study()!=null||source.degree()!=null) {
    // Reject academic fields on unrelated categories instead of silently dropping them.
    throw new IllegalArgumentException("field_of_study and degree apply only to UNIVERSITY certificates");
   }
   row.fileId=uuidOrNull(source.file_id());
   if(row.fileId==null)throw new IllegalArgumentException("A certificate document is required");
   row.position=i;
   certificates.save(row);
  }
 }
 private void storeExperiences(CoachApplication a,List<Requests.Experience> rows) {
  if(rows==null)return;
  for(int i=0;i<rows.size();i++) {
   var source=rows.get(i);
   var row=new CoachExperience();
   row.applicationId=a.id;
   row.title=source.title().strip();
   row.organization=blankToNull(source.organization());
   row.description=blankToNull(source.description());
   row.durationMonths=source.duration_months();
   row.position=i;
   experiences.save(row);
  }
 }
 /** Loads the structured children so detail and list responses stay consistent. */
 private Map<String,Object> view(CoachApplication a,Instant invitationExpiresAt) {
  return Views.coachApplication(a,invitationExpiresAt,
   certificates.findByApplicationIdOrderByPosition(a.id),
   experiences.findByApplicationIdOrderByPosition(a.id),json);
 }
 private static UUID uuidOrNull(String value) { return value==null||value.isBlank()?null:UUID.fromString(value); }
 private String issueLookupToken(CoachApplication a) {
  String token=OpaqueTokens.newToken();
  var row=new CoachApplicationToken();
  row.tokenHash=OpaqueTokens.digest(token);row.applicationId=a.id;
  row.createdAt=Instant.now();row.expiresAt=row.createdAt.plus(lookupTtl);
  tokens.save(row);
  return token;
 }
 private Instant invitationExpiry(UUID applicationId) {
  return invitations.findByApplicationId(applicationId).map(i->i.expiresAt).orElse(null);
 }
 private static Map<String,Object> summary(CoachApplication a,String lookupToken) {
  Map<String,Object> v=new LinkedHashMap<>();
  v.put("application_id",a.id);v.put("status",a.status);v.put("lookup_token",lookupToken);v.put("submitted_at",a.createdAt);
  return v;
 }
 private static String blankToNull(String value) { return value==null||value.isBlank()?null:value.strip(); }
}
