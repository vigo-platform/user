package ir.vigoapp.user.entity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** One certificate row. Only fields relevant to its category are populated. */
@Getter
@Setter
@NoArgsConstructor
@Entity @Table(name="coach_application_certificates")
public class CoachCertificate {
 public enum Category { UNIVERSITY, FEDERATION, SKILL }
 @Id @Column(name="certificate_id") private UUID id=UUID.randomUUID();
 @Column(name="application_id",nullable=false) private UUID applicationId;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Category category;
 @Column(nullable=false,length=150) private String title;
 @Column(length=150) private String issuer;
 @Column(name="field_of_study",length=150) private String fieldOfStudy;
 @Column(length=100) private String degree;
 /** Storage asset id of the scanned document; the bytes live in Storage. */
 @Column(name="file_id") private UUID fileId;
 @Column(nullable=false) private int position;
 @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
}
