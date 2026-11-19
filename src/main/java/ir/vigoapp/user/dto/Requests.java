package ir.vigoapp.user.dto;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.dto.plan.PlanFieldValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
public final class Requests {
 private Requests() {}
 public static final String UUID_V4="^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
 public static final String MEDIA="^/api/v1/media/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
 public record Profile(@NotBlank @Size(min=2,max=60) String displayName,@Pattern(regexp=MEDIA) String avatarUrl, LocalDate birthDate,Gender gender) {}
 public record Certification(@NotBlank @Size(max=100) String title,@Size(max=100) String issuer,@Min(1950) @Max(2100) Integer year) {}
 public record Coach(@NotBlank @Size(min=2,max=60) String displayName,@Size(max=120) String headline,
  @NotBlank @Size(min=20,max=3000) String bio,@NotNull @Size(min=1,max=10) List<@NotBlank @Size(max=40) String> specialties,
  @NotNull @Min(0) @Max(60) Integer experienceYears,@Size(max=20) List<@NotNull @Valid Certification> certifications,
  @Pattern(regexp="^[A-Za-z0-9._]{1,30}$") String instagramHandle,@Size(max=12) List<@NotNull @Pattern(regexp=MEDIA) String> resultImageUrls) {}
 /**
  * Anonymous coach application. It carries only what an applicant can provide
  * without an account; neither a role nor a user identifier is accepted.
  *
  * <p>Certificates and experience are nullable only for backwards compatibility
  * with the previous payload; a new submission must still satisfy validation.
  */
 public record Application(@NotBlank @Size(max=60) String firstName,
  @NotBlank @Size(max=60) String lastName,
  @NotBlank @Email @Size(max=254) String email,
  @Pattern(regexp="^\\+[1-9][0-9]{7,14}$") String phone,
  @Size(max=120) String headline,
  @NotBlank @Size(min=20,max=3000) String bio,
  @NotNull @Size(min=1,max=10) List<@NotBlank @Size(max=40) String> specialties,
  @NotNull @Min(0) @Max(60) Integer experienceYears,
  /** Storage asset id of the public profile image. */
  @Pattern(regexp=UUID_V4) String profileImageFileId,
  @Size(max=10) List<@NotNull @Valid Certificate> certificates,
  @Size(max=10) List<@NotNull @Valid Experience> experiences,
  @Pattern(regexp="^[A-Za-z0-9._]{1,30}$") String instagramHandle,
  @Pattern(regexp="fa|en") String locale) {}
 public record Certificate(@NotNull CertificateCategory category,
  @NotBlank @Size(max=150) String title,
  @Size(max=150) String issuer,
  @Size(max=150) String fieldOfStudy,
  @Size(max=100) String degree,
  /** Storage asset id of the scanned certificate; always a private document. */
  @Pattern(regexp=UUID_V4) String fileId) {}
 public record Experience(@NotBlank @Size(max=150) String title,
  @Size(max=150) String organization,
  @Size(max=1000) String description,
  @Min(0) @Max(720) Integer durationMonths) {}
 public enum CertificateCategory { UNIVERSITY, FEDERATION, SKILL }
 /** Plan payload. Ownership comes from the token, never from this body. */
 public record Plan(@NotBlank @Size(min=3,max=80) String title,
  @Size(max=1500) String description,
  @NotNull @Min(10000) Long price,
  @NotNull @Min(1) @Max(365) Integer durationDays,
  @NotEmpty List<@NotBlank @Size(max=64) String> includes,
  List<@NotNull @Positive Long> featureIds,
  List<@NotNull @Valid PlanFieldValue> fieldValues) {
  public Plan {
   if (featureIds == null) featureIds = List.of();
   if (fieldValues == null) fieldValues = List.of();
  }
 }
 public record Status(@NotNull AccountStatus status,@Size(max=500) String reason) {}
 public record Rejection(@NotBlank @Size(min=5,max=500) String reason) {}
}
