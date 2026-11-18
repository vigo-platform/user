package ir.vigoapp.user.api;
import ir.vigoapp.user.domain.*;
import org.springframework.data.domain.Page;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
public final class Views {
 private Views() {}
 public record Profile(UUID user_id,String email,String display_name,String phone,String avatar_url,LocalDate birth_date,Gender gender,Role role,AccountStatus status,Instant created_at,Instant updated_at) {}
 public record Paged<T>(int page,int size,long total_elements,int total_pages,List<T> content) {}
 public static Profile profile(UserAccount u) { return new Profile(u.publicId,u.email,u.displayName,restPhone(u),u.avatarUrl,u.birthDate,u.gender,u.role,u.status,u.createdAt,u.updatedAt); }
 // REST contract exposes only a verified phone; Iranian numbers use its local 09 format.
 static String restPhone(UserAccount u) { return u.phoneVerified?u.phone:null; }
 public static Map<String,Object> coach(CoachProfile c,boolean privateView,ObjectMapper json) {
  // A lazy proxy's public fields are not the persisted entity fields, even after initialization.
  var user=org.hibernate.Hibernate.unproxy(c.user,UserAccount.class);
  Map<String,Object> v=new LinkedHashMap<>();
  v.put("coach_id",user.publicId);v.put("display_name",c.displayName);v.put("headline",c.headline);v.put("bio",c.bio);
  v.put("specialties",json.readTree(c.specialties));v.put("experience_years",c.experienceYears);v.put("certifications",json.readTree(c.certifications));
  v.put("instagram_handle",c.instagramHandle);v.put("result_image_urls",json.readTree(c.resultImageUrls));v.put("avatar_url",user.avatarUrl);
  if(privateView){v.put("status",c.status);v.put("rejection_reason",c.rejectionReason);}
  v.put("min_plan_price",null);v.put("active_athletes_count",0);v.put("created_at",c.createdAt);v.put("updated_at",c.updatedAt);return v;
 }
 /** Minimum an applicant may read back with a status token: no PII, no review notes. */
 public static Map<String,Object> applicationStatus(CoachApplication a) {
  Map<String,Object> v=new LinkedHashMap<>();
  v.put("application_id",a.id);v.put("status",a.status);v.put("submitted_at",a.createdAt);
  v.put("reviewed_at",a.reviewedAt);return v;
 }
 /** Full review view. Applicant contact details are admin-only. */
 public static Map<String,Object> coachApplication(CoachApplication a,Instant invitationExpiresAt,
   List<CoachCertificate> certificateRows,List<CoachExperience> experienceRows,ObjectMapper json) {
  Map<String,Object> v=new LinkedHashMap<>();
  v.put("application_id",a.id);v.put("full_name",a.fullName);
  v.put("first_name",a.firstName);v.put("last_name",a.lastName);
  v.put("email",a.email);v.put("phone",a.phone);
  // Only the public image asset id is exposed; certificates stay private references.
  v.put("profile_image_file_id",a.profileImageFileId);
  v.put("certificates",certificateRows.stream().map(c->{
   Map<String,Object> row=new LinkedHashMap<>();
   row.put("certificate_id",c.id);row.put("category",c.category);row.put("title",c.title);
   row.put("issuer",c.issuer);row.put("field_of_study",c.fieldOfStudy);row.put("degree",c.degree);
   row.put("file_id",c.fileId);return row;}).toList());
  v.put("experiences",experienceRows.stream().map(e->{
   Map<String,Object> row=new LinkedHashMap<>();
   row.put("experience_id",e.id);row.put("title",e.title);row.put("organization",e.organization);
   row.put("description",e.description);row.put("duration_months",e.durationMonths);return row;}).toList());
  v.put("headline",a.headline);v.put("bio",a.bio);v.put("specialties",json.readTree(a.specialties));
  v.put("experience_years",a.experienceYears);v.put("certifications",json.readTree(a.certifications));
  v.put("instagram_handle",a.instagramHandle);v.put("locale",a.locale);v.put("status",a.status);
  v.put("rejection_reason",a.rejectionReason);v.put("reviewed_by",a.reviewedBy);v.put("reviewed_at",a.reviewedAt);
  v.put("invitation_expires_at",invitationExpiresAt);v.put("created_at",a.createdAt);v.put("updated_at",a.updatedAt);
  return v;
 }
 public static <A,B> Paged<B> page(Page<A> page,java.util.function.Function<A,B> f) { return new Paged<>(page.getNumber(),page.getSize(),page.getTotalElements(),page.getTotalPages(),page.getContent().stream().map(f).toList()); }
}
