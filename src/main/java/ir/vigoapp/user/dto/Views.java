package ir.vigoapp.user.dto;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.entity.CoachApplication;
import ir.vigoapp.user.entity.CoachCertificate;
import ir.vigoapp.user.entity.CoachExperience;
import ir.vigoapp.user.entity.CoachProfile;
import ir.vigoapp.user.entity.UserAccount;
import org.springframework.data.domain.Page;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
public final class Views {
 private Views() {}
 public record Profile(UUID user_id,String email,String display_name,String phone,String avatar_url,LocalDate birth_date,Gender gender,Role role,AccountStatus status,Instant created_at,Instant updated_at) {}
 public record Paged<T>(int page,int size,long total_elements,int total_pages,List<T> content) {}
 public static Profile profile(UserAccount u) { return new Profile(u.getPublicId(),u.getEmail(),u.getDisplayName(),restPhone(u),u.getAvatarUrl(),u.getBirthDate(),u.getGender(),u.getRole(),u.getStatus(),u.getCreatedAt(),u.getUpdatedAt()); }
 // REST contract exposes only a verified phone; Iranian numbers use its local 09 format.
 static String restPhone(UserAccount u) { return u.isPhoneVerified()?u.getPhone():null; }
 public static Map<String,Object> coach(CoachProfile c,boolean privateView,ObjectMapper json) {
  // A lazy proxy's public fields are not the persisted entity fields, even after initialization.
  var user=org.hibernate.Hibernate.unproxy(c.getUser(),UserAccount.class);
  Map<String,Object> v=new LinkedHashMap<>();
  v.put("coach_id",user.getPublicId());v.put("display_name",c.getDisplayName());v.put("headline",c.getHeadline());v.put("bio",c.getBio());
  v.put("specialties",json.readTree(c.getSpecialties()));v.put("experience_years",c.getExperienceYears());v.put("certifications",json.readTree(c.getCertifications()));
  v.put("instagram_handle",c.getInstagramHandle());v.put("result_image_urls",json.readTree(c.getResultImageUrls()));v.put("avatar_url",user.getAvatarUrl());
  if(privateView){v.put("status",c.getStatus());v.put("rejection_reason",c.getRejectionReason());}
  v.put("min_plan_price",null);v.put("active_athletes_count",0);v.put("created_at",c.getCreatedAt());v.put("updated_at",c.getUpdatedAt());return v;
 }
 /** Minimum an applicant may read back with a status token: no PII, no review notes. */
 public static Map<String,Object> applicationStatus(CoachApplication a) {
  Map<String,Object> v=new LinkedHashMap<>();
  v.put("application_id",a.getId());v.put("status",a.getStatus());v.put("submitted_at",a.getCreatedAt());
  v.put("reviewed_at",a.getReviewedAt());return v;
 }
 /** Full review view. Applicant contact details are admin-only. */
 public static Map<String,Object> coachApplication(CoachApplication a,Instant invitationExpiresAt,
   List<CoachCertificate> certificateRows,List<CoachExperience> experienceRows,ObjectMapper json) {
  Map<String,Object> v=new LinkedHashMap<>();
  v.put("application_id",a.getId());v.put("full_name",a.getFullName());
  v.put("first_name",a.getFirstName());v.put("last_name",a.getLastName());
  v.put("email",a.getEmail());v.put("phone",a.getPhone());
  // Only the public image asset id is exposed; certificates stay private references.
  v.put("profile_image_file_id",a.getProfileImageFileId());
  v.put("certificates",certificateRows.stream().map(c->{
   Map<String,Object> row=new LinkedHashMap<>();
   row.put("certificate_id",c.getId());row.put("category",c.getCategory());row.put("title",c.getTitle());
   row.put("issuer",c.getIssuer());row.put("field_of_study",c.getFieldOfStudy());row.put("degree",c.getDegree());
   row.put("file_id",c.getFileId());return row;}).toList());
  v.put("experiences",experienceRows.stream().map(e->{
   Map<String,Object> row=new LinkedHashMap<>();
   row.put("experience_id",e.getId());row.put("title",e.getTitle());row.put("organization",e.getOrganization());
   row.put("description",e.getDescription());row.put("duration_months",e.getDurationMonths());return row;}).toList());
  v.put("headline",a.getHeadline());v.put("bio",a.getBio());v.put("specialties",json.readTree(a.getSpecialties()));
  v.put("experience_years",a.getExperienceYears());v.put("certifications",json.readTree(a.getCertifications()));
  v.put("instagram_handle",a.getInstagramHandle());v.put("locale",a.getLocale());v.put("status",a.getStatus());
  v.put("rejection_reason",a.getRejectionReason());v.put("reviewed_by",a.getReviewedBy());v.put("reviewed_at",a.getReviewedAt());
  v.put("invitation_expires_at",invitationExpiresAt);v.put("created_at",a.getCreatedAt());v.put("updated_at",a.getUpdatedAt());
  return v;
 }
 public static <A,B> Paged<B> page(Page<A> page,java.util.function.Function<A,B> f) { return new Paged<>(page.getNumber(),page.getSize(),page.getTotalElements(),page.getTotalPages(),page.getContent().stream().map(f).toList()); }
}
