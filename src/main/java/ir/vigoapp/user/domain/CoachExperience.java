package ir.vigoapp.user.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** One work-experience row belonging to a coach application. */
@Entity @Table(name="coach_application_experiences")
public class CoachExperience {
 @Id @Column(name="experience_id") public UUID id=UUID.randomUUID();
 @Column(name="application_id",nullable=false) public UUID applicationId;
 @Column(nullable=false,length=150) public String title;
 @Column(length=150) public String organization;
 @Column(length=1000) public String description;
 @Column(name="duration_months") public Integer durationMonths;
 @Column(nullable=false) public int position;
 @Column(name="created_at",nullable=false) public Instant createdAt=Instant.now();
}
