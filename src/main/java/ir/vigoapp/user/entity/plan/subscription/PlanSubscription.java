package ir.vigoapp.user.entity.plan.subscription;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ir.vigoapp.user.domain.plan.subscription.SubscriptionStatus;
import jakarta.persistence.*;
import java.time.Instant;

/** Access state created by a purchase; only a payment-success event activates it. */
@Getter
@Setter
@NoArgsConstructor
@Entity @Table(name="plan_subscriptions")
public class PlanSubscription {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="plan_id",nullable=false) private Long planId;
 @Column(name="coach_id",nullable=false) private Long coachId;
 @Column(name="athlete_id",nullable=false) private Long athleteId;
 @Column(name="order_id") private Long orderId;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private SubscriptionStatus status=SubscriptionStatus.PENDING_PAYMENT;
 @Column(name="starts_at") private Instant startsAt;
 @Column(name="ends_at") private Instant endsAt;
 @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
 @Column(name="updated_at",nullable=false) private Instant updatedAt=createdAt;
}
