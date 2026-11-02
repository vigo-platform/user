package ir.vigoapp.user.domain;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="coach_profiles")
public class CoachProfile {
 @Id @Column(name="user_id") public Long userId;
 @OneToOne(fetch=FetchType.LAZY) @MapsId @JoinColumn(name="user_id") public UserAccount user;
 @Column(name="display_name",nullable=false,length=60) public String displayName;
 @Column(length=120) public String headline;
 @Column(nullable=false,length=3000) public String bio;
 @Column(nullable=false,columnDefinition="text") public String specialties;
 @Column(name="experience_years",nullable=false) public int experienceYears;
 @Column(nullable=false,columnDefinition="text") public String certifications="[]";
 @Column(name="instagram_handle",length=30) public String instagramHandle;
 @Column(name="result_image_urls",nullable=false,columnDefinition="text") public String resultImageUrls="[]";
 @Enumerated(EnumType.STRING) @Column(nullable=false) public CoachStatus status=CoachStatus.PENDING;
 @Column(name="rejection_reason",length=500) public String rejectionReason;
 @Column(name="created_at",nullable=false) public Instant createdAt=Instant.now();
 @Column(name="updated_at",nullable=false) public Instant updatedAt=createdAt;
 @Version public long version;
}
