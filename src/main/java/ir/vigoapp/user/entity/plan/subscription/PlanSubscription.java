package ir.vigoapp.user.entity.plan.subscription;

import ir.vigoapp.user.domain.plan.subscription.SubscriptionStatus;
import jakarta.persistence.*;
import java.time.Instant;

/** Access state created by a purchase; only a payment-success event activates it. */
@Entity @Table(name="plan_subscriptions")
public class PlanSubscription {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Column(name="plan_id",nullable=false) public Long planId;
 @Column(name="coach_id",nullable=false) public Long coachId;
 @Column(name="athlete_id",nullable=false) public Long athleteId;
 @Column(name="order_id") public Long orderId;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) public SubscriptionStatus status=SubscriptionStatus.PENDING_PAYMENT;
 @Column(name="starts_at") public Instant startsAt;
 @Column(name="ends_at") public Instant endsAt;
 @Column(name="created_at",nullable=false) public Instant createdAt=Instant.now();
 @Column(name="updated_at",nullable=false) public Instant updatedAt=createdAt;
}
