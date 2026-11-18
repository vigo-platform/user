package ir.vigoapp.user.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** Single-use, time-limited approval invitation bound to the applicant email. */
@Entity @Table(name="coach_invitations")
public class CoachInvitation {
 @Id public UUID id=UUID.randomUUID();
 @Column(name="application_id",nullable=false,unique=true) public UUID applicationId;
 @Column(nullable=false,length=254) public String email;
 @Column(name="token_hash",nullable=false,unique=true,length=64) public String tokenHash;
 @Column(name="created_at",nullable=false) public Instant createdAt=Instant.now();
 @Column(name="expires_at",nullable=false) public Instant expiresAt;
 @Column(name="consumed_at") public Instant consumedAt;
 @Column(name="consumed_user_id") public Long consumedUserId;
 public boolean isActiveAt(Instant now) { return consumedAt==null&&expiresAt.isAfter(now); }
 @Override public String toString() { return "CoachInvitation[id="+id+",consumed="+(consumedAt!=null)+"]"; }
}
