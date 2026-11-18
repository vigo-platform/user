package ir.vigoapp.user.entity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** One work-experience row belonging to a coach application. */
@Getter
@Setter
@NoArgsConstructor
@Entity @Table(name="coach_application_experiences")
public class CoachExperience {
 @Id @Column(name="experience_id") private UUID id=UUID.randomUUID();
 @Column(name="application_id",nullable=false) private UUID applicationId;
 @Column(nullable=false,length=150) private String title;
 @Column(length=150) private String organization;
 @Column(length=1000) private String description;
 @Column(name="duration_months") private Integer durationMonths;
 @Column(nullable=false) private int position;
 @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
}
