package ir.vigoapp.user.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** Access state created by a purchase; only a payment-success event activates it. */
@Entity @Table(name="plan_subscriptions")
public class PlanSubscription implements org.springframework.data.domain.Persistable<UUID> {
 /**
  * Assigned UUID ids make Spring Data treat a fresh entity as existing and merge it
  * into another managed instance, so later field updates silently miss the row.
  * Tracking newness explicitly makes save() persist this exact instance.
  */
 @Transient private boolean isNew=true;
 @Override public UUID getId(){return id;}
 @Override public boolean isNew(){return isNew;}
 @PostLoad @PostPersist void markNotNew(){isNew=false;}

 @Id @Column(name="subscription_id") public UUID id=UUID.randomUUID();
 @Column(name="plan_id",nullable=false) public UUID planId;
 @Column(name="coach_id",nullable=false) public UUID coachId;
 @Column(name="athlete_id",nullable=false) public UUID athleteId;
 @Column(name="order_id") public UUID orderId;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) public SubscriptionStatus status=SubscriptionStatus.PENDING_PAYMENT;
 @Column(name="starts_at") public Instant startsAt;
 @Column(name="ends_at") public Instant endsAt;
 @Column(name="created_at",nullable=false) public Instant createdAt=Instant.now();
 @Column(name="updated_at",nullable=false) public Instant updatedAt=createdAt;
}
