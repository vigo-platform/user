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
  Map<String,Object> v=new LinkedHashMap<>();
  v.put("coach_id",c.user.publicId);v.put("display_name",c.displayName);v.put("headline",c.headline);v.put("bio",c.bio);
  v.put("specialties",json.readTree(c.specialties));v.put("experience_years",c.experienceYears);v.put("certifications",json.readTree(c.certifications));
  v.put("instagram_handle",c.instagramHandle);v.put("result_image_urls",json.readTree(c.resultImageUrls));v.put("avatar_url",c.user.avatarUrl);
  if(privateView){v.put("status",c.status);v.put("rejection_reason",c.rejectionReason);}
  v.put("min_plan_price",null);v.put("active_athletes_count",0);v.put("created_at",c.createdAt);v.put("updated_at",c.updatedAt);return v;
 }
 public static <A,B> Paged<B> page(Page<A> page,java.util.function.Function<A,B> f) { return new Paged<>(page.getNumber(),page.getSize(),page.getTotalElements(),page.getTotalPages(),page.getContent().stream().map(f).toList()); }
}
