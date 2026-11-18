package ir.vigoapp.user.entity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** Single-use, time-limited approval invitation bound to the applicant email. */
@Getter
@Setter
@NoArgsConstructor
@Entity @Table(name="coach_invitations")
public class CoachInvitation {
 @Id private UUID id=UUID.randomUUID();
 @Column(name="application_id",nullable=false,unique=true) private UUID applicationId;
 @Column(nullable=false,length=254) private String email;
 @Column(name="token_hash",nullable=false,unique=true,length=64) private String tokenHash;
 @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
 @Column(name="expires_at",nullable=false) private Instant expiresAt;
 @Column(name="consumed_at") private Instant consumedAt;
 @Column(name="consumed_user_id") private Long consumedUserId;
 public boolean isActiveAt(Instant now) { return consumedAt==null&&expiresAt.isAfter(now); }
 @Override public String toString() { return "CoachInvitation[id="+id+",consumed="+(consumedAt!=null)+"]"; }
}
