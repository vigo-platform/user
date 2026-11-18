package ir.vigoapp.user.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** Anonymous coach application. Never owns a user account until an invitation is consumed. */
@Entity @Table(name="coach_applications")
public class CoachApplication {
 @Id public UUID id=UUID.randomUUID();
 @Column(name="full_name",nullable=false,length=60) public String fullName;
 @Column(nullable=false,length=254) public String email;
 @Column(length=16) public String phone;
 @Column(length=120) public String headline;
 @Column(nullable=false,length=3000) public String bio;
 @Column(nullable=false,columnDefinition="text") public String specialties="[]";
 @Column(name="experience_years",nullable=false) public int experienceYears;
 @Column(nullable=false,columnDefinition="text") public String certifications="[]";
 @Column(name="instagram_handle",length=30) public String instagramHandle;
 @Column(nullable=false,length=8) public String locale="fa";
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) public CoachApplicationStatus status=CoachApplicationStatus.PENDING;
 @Column(name="rejection_reason",length=500) public String rejectionReason;
 @Column(name="reviewed_by") public UUID reviewedBy;
 @Column(name="reviewed_at") public Instant reviewedAt;
 @Column(name="created_at",nullable=false) public Instant createdAt=Instant.now();
 @Column(name="updated_at",nullable=false) public Instant updatedAt=createdAt;
 @Version public long version;
 @Column(name="aggregate_version",nullable=false) public long aggregateVersion;
 @Override public String toString() { return "CoachApplication[id="+id+",status="+status+"]"; }
}
