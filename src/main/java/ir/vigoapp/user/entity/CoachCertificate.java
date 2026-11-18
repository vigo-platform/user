package ir.vigoapp.user.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** One certificate row. Only fields relevant to its category are populated. */
@Entity @Table(name="coach_application_certificates")
public class CoachCertificate {
 public enum Category { UNIVERSITY, FEDERATION, SKILL }
 @Id @Column(name="certificate_id") public UUID id=UUID.randomUUID();
 @Column(name="application_id",nullable=false) public UUID applicationId;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) public Category category;
 @Column(nullable=false,length=150) public String title;
 @Column(length=150) public String issuer;
 @Column(name="field_of_study",length=150) public String fieldOfStudy;
 @Column(length=100) public String degree;
 /** Storage asset id of the scanned document; the bytes live in Storage. */
 @Column(name="file_id") public UUID fileId;
 @Column(nullable=false) public int position;
 @Column(name="created_at",nullable=false) public Instant createdAt=Instant.now();
}
