package ir.vigoapp.user.entity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** Status-lookup credential. Only the SHA-256 digest is persisted. */
@Getter
@Setter
@NoArgsConstructor
@Entity @Table(name="coach_application_tokens")
public class CoachApplicationToken {
 @Id @Column(name="token_hash",nullable=false,length=64) private String tokenHash;
 @Column(name="application_id",nullable=false) private UUID applicationId;
 @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
 @Column(name="expires_at",nullable=false) private Instant expiresAt;
}
