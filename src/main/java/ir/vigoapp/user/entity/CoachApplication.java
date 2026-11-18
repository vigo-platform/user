package ir.vigoapp.user.entity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ir.vigoapp.user.domain.CoachApplicationStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** Anonymous coach application. Never owns a user account until an invitation is consumed. */
@Getter
@Setter
@NoArgsConstructor
@Entity @Table(name="coach_applications")
public class CoachApplication {
 @Id private UUID id=UUID.randomUUID();
 @Column(name="full_name",nullable=false,length=60) private String fullName;
 @Column(name="first_name",nullable=false,length=60) private String firstName;
 @Column(name="last_name",nullable=false,length=60) private String lastName;
 /** Public profile image asset; public visibility, never a private document. */
 @Column(name="profile_image_file_id") private UUID profileImageFileId;
 @Column(nullable=false,length=254) private String email;
 @Column(length=16) private String phone;
 @Column(length=120) private String headline;
 @Column(nullable=false,length=3000) private String bio;
 @Column(nullable=false,columnDefinition="text") private String specialties="[]";
 @Column(name="experience_years",nullable=false) private int experienceYears;
 @Column(nullable=false,columnDefinition="text") private String certifications="[]";
 @Column(name="instagram_handle",length=30) private String instagramHandle;
 @Column(nullable=false,length=8) private String locale="fa";
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private CoachApplicationStatus status=CoachApplicationStatus.PENDING;
 @Column(name="rejection_reason",length=500) private String rejectionReason;
 @Column(name="reviewed_by") private UUID reviewedBy;
 @Column(name="reviewed_at") private Instant reviewedAt;
 @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
 @Column(name="updated_at",nullable=false) private Instant updatedAt=createdAt;
 @Version private long version;
 @Column(name="aggregate_version",nullable=false) private long aggregateVersion;
 @Override public String toString() { return "CoachApplication[id="+id+",status="+status+"]"; }
}
