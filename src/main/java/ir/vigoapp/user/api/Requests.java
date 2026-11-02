package ir.vigoapp.user.api;
import ir.vigoapp.user.domain.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
public final class Requests {
 private Requests() {}
 public static final String MEDIA="^/api/v1/media/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
 public record Profile(@NotBlank @Size(min=2,max=60) String display_name,@Pattern(regexp=MEDIA) String avatar_url, LocalDate birth_date,Gender gender) {}
 public record Certification(@NotBlank @Size(max=100) String title,@Size(max=100) String issuer,@Min(1950) @Max(2100) Integer year) {}
 public record Coach(@NotBlank @Size(min=2,max=60) String display_name,@Size(max=120) String headline,
  @NotBlank @Size(min=20,max=3000) String bio,@NotNull @Size(min=1,max=10) List<@NotBlank @Size(max=40) String> specialties,
  @NotNull @Min(0) @Max(60) Integer experience_years,@Size(max=20) List<@NotNull @Valid Certification> certifications,
  @Pattern(regexp="^[A-Za-z0-9._]{1,30}$") String instagram_handle,@Size(max=12) List<@NotNull @Pattern(regexp=MEDIA) String> result_image_urls) {}
 public record Status(@NotNull AccountStatus status,@Size(max=500) String reason) {}
 public record Rejection(@NotBlank @Size(min=5,max=500) String reason) {}
}
