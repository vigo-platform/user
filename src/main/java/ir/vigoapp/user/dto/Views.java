package ir.vigoapp.user.dto;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.entity.CoachApplication;
import ir.vigoapp.user.entity.CoachCertificate;
import ir.vigoapp.user.entity.CoachExperience;
import ir.vigoapp.user.entity.CoachProfile;
import ir.vigoapp.user.entity.UserAccount;
import org.springframework.data.domain.Page;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
public final class Views {
 private Views() {}
 public record Profile(long userId,String email,String displayName,String phone,String avatarUrl,LocalDate birthDate,Gender gender,Role role,AccountStatus status,Instant createdAt,Instant updatedAt) {}
 public record Paged<T>(int page,int size,long totalElements,int totalPages,List<T> content) {}
 public static Profile profile(UserAccount u) { return new Profile(u.getId(),u.getEmail(),u.getDisplayName(),restPhone(u),u.getAvatarUrl(),u.getBirthDate(),u.getGender(),u.getRole(),u.getStatus(),u.getCreatedAt(),u.getUpdatedAt()); }
 // REST contract exposes only a verified phone; Iranian numbers use its local 09 format.
 static String restPhone(UserAccount u) { return u.isPhoneVerified()?u.getPhone():null; }
 public static Map<String,Object> coach(CoachProfile c,boolean privateView,ObjectMapper json) {
  // A lazy proxy's public fields are not the persisted entity fields, even after initialization.
  var user=org.hibernate.Hibernate.unproxy(c.getUser(),UserAccount.class);
  Map<String,Object> v=new LinkedHashMap<>();
  v.put("coachId",user.getId());v.put("displayName",c.getDisplayName());v.put("headline",c.getHeadline());v.put("bio",c.getBio());
  v.put("specialties",json.readTree(c.getSpecialties()));v.put("experienceYears",c.getExperienceYears());v.put("certifications",readCertifications(c.getCertifications(),json));
  v.put("instagramHandle",c.getInstagramHandle());v.put("resultImageUrls",json.readTree(c.getResultImageUrls()));v.put("avatarUrl",user.getAvatarUrl());
  if(privateView){v.put("status",c.getStatus());v.put("rejectionReason",c.getRejectionReason());}
  v.put("minPlanPrice",null);v.put("activeAthletesCount",0);v.put("createdAt",c.getCreatedAt());v.put("updatedAt",c.getUpdatedAt());return v;
 }
 /** Minimum an applicant may read back with a status token: no PII, no review notes. */
 public static Map<String,Object> applicationStatus(CoachApplication a) {
  Map<String,Object> v=new LinkedHashMap<>();
  v.put("applicationId",a.getId());v.put("status",a.getStatus());v.put("submittedAt",a.getCreatedAt());
  v.put("reviewedAt",a.getReviewedAt());return v;
 }
 /** Full review view. Applicant contact details are admin-only. */
 public static Map<String,Object> coachApplication(CoachApplication a,Instant invitationExpiresAt,
   List<CoachCertificate> certificateRows,List<CoachExperience> experienceRows,ObjectMapper json) {
  Map<String,Object> v=new LinkedHashMap<>();
  v.put("applicationId",a.getId());v.put("fullName",a.getFullName());
  v.put("firstName",a.getFirstName());v.put("lastName",a.getLastName());
  v.put("email",a.getEmail());v.put("phone",a.getPhone());
  // Only the public image asset id is exposed; certificates stay private references.
  v.put("profileImageFileId",a.getProfileImageFileId());
  v.put("certificates",certificateRows.stream().map(c->{
   Map<String,Object> row=new LinkedHashMap<>();
   row.put("certificateId",c.getId());row.put("category",c.getCategory());row.put("title",c.getTitle());
   row.put("issuer",c.getIssuer());row.put("fieldOfStudy",c.getFieldOfStudy());row.put("degree",c.getDegree());
   row.put("fileId",c.getFileId());return row;}).toList());
  v.put("experiences",experienceRows.stream().map(e->{
   Map<String,Object> row=new LinkedHashMap<>();
   row.put("experienceId",e.getId());row.put("title",e.getTitle());row.put("organization",e.getOrganization());
   row.put("description",e.getDescription());row.put("durationMonths",e.getDurationMonths());return row;}).toList());
  v.put("headline",a.getHeadline());v.put("bio",a.getBio());v.put("specialties",json.readTree(a.getSpecialties()));
  v.put("experienceYears",a.getExperienceYears());v.put("certifications",readCertifications(a.getCertifications(),json));
  v.put("instagramHandle",a.getInstagramHandle());v.put("locale",a.getLocale());v.put("status",a.getStatus());
  v.put("rejectionReason",a.getRejectionReason());v.put("reviewedBy",a.getReviewedBy());v.put("reviewedAt",a.getReviewedAt());
  v.put("invitationExpiresAt",invitationExpiresAt);v.put("createdAt",a.getCreatedAt());v.put("updatedAt",a.getUpdatedAt());
  return v;
 }
 private static JsonNode readCertifications(String stored,ObjectMapper json) {
  var certifications=json.readTree(stored);
  if(certifications.isArray()) {
   for(var entry:certifications) {
    if(entry instanceof ObjectNode certificate) {
     renameLegacyProperty(certificate,"field_of_study","fieldOfStudy");
     renameLegacyProperty(certificate,"file_id","fileId");
    }
   }
  }
  return certifications;
 }
 private static void renameLegacyProperty(ObjectNode node,String oldName,String newName) {
  var value=node.remove(oldName);
  if(value!=null&&!node.has(newName))node.set(newName,value);
 }
 public static <A,B> Paged<B> page(Page<A> page,java.util.function.Function<A,B> f) { return new Paged<>(page.getNumber(),page.getSize(),page.getTotalElements(),page.getTotalPages(),page.getContent().stream().map(f).toList()); }
}
