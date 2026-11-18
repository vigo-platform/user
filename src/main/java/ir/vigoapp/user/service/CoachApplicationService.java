package ir.vigoapp.user.service;
import ir.vigoapp.user.api.*;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.dto.Requests;
import ir.vigoapp.user.dto.Views;
import ir.vigoapp.user.entity.CoachApplication;
import ir.vigoapp.user.entity.CoachApplicationToken;
import ir.vigoapp.user.entity.CoachCertificate;
import ir.vigoapp.user.entity.CoachExperience;
import ir.vigoapp.user.entity.CoachInvitation;
import ir.vigoapp.user.outbox.OutboxWriter;
import ir.vigoapp.user.repository.CoachApplicationRepository;
import ir.vigoapp.user.repository.CoachApplicationTokenRepository;
import ir.vigoapp.user.repository.CoachCertificateRepository;
import ir.vigoapp.user.repository.CoachExperienceRepository;
import ir.vigoapp.user.repository.CoachInvitationRepository;
import ir.vigoapp.user.repository.UserRepository;
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
  a.setFirstName(firstName);a.setLastName(lastName);
  a.setFullName((firstName+" "+lastName).strip());
  a.setProfileImageFileId(uuidOrNull(r.profile_image_file_id()));
  a.setEmail(email);a.setPhone(phone);a.setHeadline(blankToNull(r.headline()));
  a.setBio(r.bio().strip());
  a.setSpecialties(json.writeValueAsString(r.specialties().stream().map(String::strip).toList()));
  a.setExperienceYears(r.experience_years());
  // Legacy column kept for the existing contract; structured rows are authoritative.
  a.setCertifications(json.writeValueAsString(r.certificates()==null?List.of():r.certificates()));
  a.setInstagramHandle(blankToNull(r.instagram_handle()));
  a.setLocale(r.locale()==null?"fa":r.locale());
  a.setStatus(CoachApplicationStatus.PENDING);
  a.setAggregateVersion(1);
  try { applications.saveAndFlush(a); }
  catch(DataIntegrityViolationException unexpected) {
   // The unique index is a backstop; the advisory lock should make this unreachable.
   throw ApiException.conflict("APPLICATION_ALREADY_PENDING");
  }
  storeCertificates(a,r.certificates());
  storeExperiences(a,r.experiences());
  String lookupToken=issueLookupToken(a);
  Map<String,Object> data=new LinkedHashMap<>();
  data.put("application_id",a.getId());data.put("full_name",a.getFullName());data.put("email",a.getEmail());
  data.put("phone",a.getPhone());data.put("locale",a.getLocale());data.put("status",a.getStatus());
  data.put("submitted_at",a.getCreatedAt());
  events.emit(a.getId(),a.getAggregateVersion(),"CoachApplicationSubmitted",data);
  log.atInfo().addKeyValue("event","coach_application_submitted").addKeyValue("result","STAGED")
   .addKeyValue("application_id",a.getId()).log("Public coach application staged for review.");
  return summary(a,lookupToken);
 }
 @Transactional(readOnly=true)
 public Map<String,Object> status(String lookupToken) {
  if(!OpaqueTokens.looksValid(lookupToken))throw ApiException.notFound();
  var token=tokens.findByTokenHashAndExpiresAtAfter(OpaqueTokens.digest(lookupToken),Instant.now()).orElseThrow(ApiException::notFound);
  var a=applications.findById(token.getApplicationId()).orElseThrow(ApiException::notFound);
  return Views.applicationStatus(a);
 }
 @Transactional(readOnly=true)
 public Views.Paged<Map<String,Object>> list(int page,int size,CoachApplicationStatus status) {
  var pageable=PageRequest.of(page,size,Sort.by("createdAt").descending().and(Sort.by("id")));
  var result=status==null?applications.findAll(pageable):applications.findAllByStatus(status,pageable);
  return Views.page(result,a->view(a,invitationExpiry(a.getId())));
 }
 @Transactional(readOnly=true)
 public Map<String,Object> get(UUID id) {
  var a=applications.findById(id).orElseThrow(ApiException::notFound);
  return view(a,invitationExpiry(id));
 }
 public Map<String,Object> approve(UUID actor,UUID id) {
  var a=applications.lock(id).orElseThrow(ApiException::notFound);
  if(a.getStatus()!=CoachApplicationStatus.PENDING)throw ApiException.conflict("INVALID_STATE");
  // An invitation must be the only route to COACH. Refuse to issue one for an
  // address that already has an account; the admin can reject instead.
  if(users.existsByEmail(a.getEmail()))throw ApiException.conflict("EMAIL_ALREADY_REGISTERED");
  if(invitations.existsByApplicationId(a.getId()))throw ApiException.conflict("INVALID_STATE");
  Instant now=Instant.now();
  String token=OpaqueTokens.newToken();
  var invitation=new CoachInvitation();
  invitation.setApplicationId(a.getId());invitation.setEmail(a.getEmail());
  invitation.setTokenHash(OpaqueTokens.digest(token));
  invitation.setCreatedAt(now);invitation.setExpiresAt(now.plus(invitationTtl));
  invitations.saveAndFlush(invitation);
  a.setStatus(CoachApplicationStatus.APPROVED);a.setRejectionReason(null);
  a.setReviewedBy(actor);a.setReviewedAt(now);a.setUpdatedAt(now);a.setAggregateVersion(a.getAggregateVersion() + 1);
  Map<String,Object> data=new LinkedHashMap<>();
  data.put("application_id",a.getId());data.put("full_name",a.getFullName());data.put("email",a.getEmail());
  data.put("locale",a.getLocale());data.put("status",a.getStatus());
  data.put("invitation_token",token);data.put("invitation_expires_at",invitation.getExpiresAt());
  data.put("reviewed_by",actor);data.put("reviewed_at",now);
  events.emit(a.getId(),a.getAggregateVersion(),"CoachApplicationApproved",data);
  log.atInfo().addKeyValue("event","coach_application_approved").addKeyValue("result","STAGED")
   .addKeyValue("application_id",a.getId()).addKeyValue("actor_public_id",actor)
   .log("Coach application approved and a registration invitation issued.");
  return view(a,invitation.getExpiresAt());
 }
 public Map<String,Object> reject(UUID actor,UUID id,String reason) {
  var a=applications.lock(id).orElseThrow(ApiException::notFound);
  if(a.getStatus()!=CoachApplicationStatus.PENDING)throw ApiException.conflict("INVALID_STATE");
  Instant now=Instant.now();
  a.setStatus(CoachApplicationStatus.REJECTED);a.setRejectionReason(reason.strip());
  a.setReviewedBy(actor);a.setReviewedAt(now);a.setUpdatedAt(now);a.setAggregateVersion(a.getAggregateVersion() + 1);
  Map<String,Object> data=new LinkedHashMap<>();
  data.put("application_id",a.getId());data.put("full_name",a.getFullName());data.put("email",a.getEmail());
  data.put("locale",a.getLocale());data.put("status",a.getStatus());data.put("reason",a.getRejectionReason());
  data.put("reviewed_by",actor);data.put("reviewed_at",now);
  events.emit(a.getId(),a.getAggregateVersion(),"CoachApplicationRejected",data);
  log.atInfo().addKeyValue("event","coach_application_rejected").addKeyValue("result","STAGED")
   .addKeyValue("application_id",a.getId()).addKeyValue("actor_public_id",actor).log("Coach application rejected.");
  return view(a,null);
 }
 private void storeCertificates(CoachApplication a,List<Requests.Certificate> rows) {
  if(rows==null)return;
  for(int i=0;i<rows.size();i++) {
   var source=rows.get(i);
   var row=new CoachCertificate();
   row.setApplicationId(a.getId());
   row.setCategory(CoachCertificate.Category.valueOf(source.category().name()));
   row.setTitle(source.title().strip());
   row.setIssuer(blankToNull(source.issuer()));
   if(source.category()==Requests.CertificateCategory.UNIVERSITY) {
    row.setFieldOfStudy(blankToNull(source.field_of_study()));
    row.setDegree(blankToNull(source.degree()));
   } else if(source.field_of_study()!=null||source.degree()!=null) {
    // Reject academic fields on unrelated categories instead of silently dropping them.
    throw new IllegalArgumentException("field_of_study and degree apply only to UNIVERSITY certificates");
   }
   row.setFileId(uuidOrNull(source.file_id()));
   if(row.getFileId()==null)throw new IllegalArgumentException("A certificate document is required");
   row.setPosition(i);
   certificates.save(row);
  }
 }
 private void storeExperiences(CoachApplication a,List<Requests.Experience> rows) {
  if(rows==null)return;
  for(int i=0;i<rows.size();i++) {
   var source=rows.get(i);
   var row=new CoachExperience();
   row.setApplicationId(a.getId());
   row.setTitle(source.title().strip());
   row.setOrganization(blankToNull(source.organization()));
   row.setDescription(blankToNull(source.description()));
   row.setDurationMonths(source.duration_months());
   row.setPosition(i);
   experiences.save(row);
  }
 }
 /** Loads the structured children so detail and list responses stay consistent. */
 private Map<String,Object> view(CoachApplication a,Instant invitationExpiresAt) {
  return Views.coachApplication(a,invitationExpiresAt,
   certificates.findByApplicationIdOrderByPosition(a.getId()),
   experiences.findByApplicationIdOrderByPosition(a.getId()),json);
 }
 private static UUID uuidOrNull(String value) { return value==null||value.isBlank()?null:UUID.fromString(value); }
 private String issueLookupToken(CoachApplication a) {
  String token=OpaqueTokens.newToken();
  var row=new CoachApplicationToken();
  row.setTokenHash(OpaqueTokens.digest(token));row.setApplicationId(a.getId());
  row.setCreatedAt(Instant.now());row.setExpiresAt(row.getCreatedAt().plus(lookupTtl));
  tokens.save(row);
  return token;
 }
 private Instant invitationExpiry(UUID applicationId) {
  return invitations.findByApplicationId(applicationId).map(i->i.getExpiresAt()).orElse(null);
 }
 private static Map<String,Object> summary(CoachApplication a,String lookupToken) {
  Map<String,Object> v=new LinkedHashMap<>();
  v.put("application_id",a.getId());v.put("status",a.getStatus());v.put("lookup_token",lookupToken);v.put("submitted_at",a.getCreatedAt());
  return v;
 }
 private static String blankToNull(String value) { return value==null||value.isBlank()?null:value.strip(); }
}
