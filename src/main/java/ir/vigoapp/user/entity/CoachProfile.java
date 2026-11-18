package ir.vigoapp.user.entity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ir.vigoapp.user.domain.CoachStatus;
import jakarta.persistence.*;
import java.time.Instant;
@Getter
@Setter
@NoArgsConstructor
@Entity @Table(name="coach_profiles")
public class CoachProfile {
 @Id @Column(name="user_id") private Long userId;
 @OneToOne(fetch=FetchType.LAZY) @MapsId @JoinColumn(name="user_id") private UserAccount user;
 @Column(name="display_name",nullable=false,length=60) private String displayName;
 @Column(length=120) private String headline;
 @Column(nullable=false,length=3000) private String bio;
 @Column(nullable=false,columnDefinition="text") private String specialties;
 @Column(name="experience_years",nullable=false) private int experienceYears;
 @Column(nullable=false,columnDefinition="text") private String certifications="[]";
 @Column(name="instagram_handle",length=30) private String instagramHandle;
 @Column(name="result_image_urls",nullable=false,columnDefinition="text") private String resultImageUrls="[]";
 @Enumerated(EnumType.STRING) @Column(nullable=false) private CoachStatus status=CoachStatus.PENDING;
 @Column(name="rejection_reason",length=500) private String rejectionReason;
 @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
 @Column(name="updated_at",nullable=false) private Instant updatedAt=createdAt;
 @Version private long version;
}
