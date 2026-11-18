package ir.vigoapp.user.entity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** Status-lookup credential. Only the SHA-256 digest is persisted. */
@Entity @Table(name="coach_application_tokens")
public class CoachApplicationToken {
 @Id @Column(name="token_hash",nullable=false,length=64) public String tokenHash;
 @Column(name="application_id",nullable=false) public UUID applicationId;
 @Column(name="created_at",nullable=false) public Instant createdAt=Instant.now();
 @Column(name="expires_at",nullable=false) public Instant expiresAt;
}
