package ir.vigoapp.user.service;

import lombok.RequiredArgsConstructor;
import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.domain.plan.subscription.SubscriptionStatus;
import ir.vigoapp.user.dto.order.CreateOrderRequest;
import ir.vigoapp.user.dto.order.CreateOrderResponse;
import ir.vigoapp.user.entity.plan.subscription.PlanSubscription;
import ir.vigoapp.user.repository.PlanSubscriptionRepository;
import ir.vigoapp.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Plan purchase baseline.
 *
 * <p>User validates the plan and records a PENDING_PAYMENT subscription, then asks
 * Order to create the order. Only a payment-success event activates access.
 * <strong>User never trusts a client price and never processes payment.</strong>
 */
@Service
@Transactional
@RequiredArgsConstructor
public class PlanPurchaseService {
    private static final Logger log = LoggerFactory.getLogger(PlanPurchaseService.class);
    public static final String CONSUMER = "user-plan-payments-v1";

    private final CoachPlanService plans;
    private final PlanSubscriptionRepository subscriptions;
    private final OrderClient orders;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    private final UserRepository users;


    /**
     * Requests an order for a plan. The client sends only the plan id; any price it
     * might send is ignored because the request body has no price field.
     */
    public Map<String, Object> purchase(UUID athleteId, Long planId) {
        var plan = plans.requirePurchasable(planId);
        var athlete = users.findByPublicId(athleteId).orElseThrow(ApiException::forbidden);
        if (plan.getCoachId().equals(athlete.getId())) throw ApiException.forbidden();

        var subscription = new PlanSubscription();
        subscription.setPlanId(plan.getId());
        subscription.setCoachId(plan.getCoachId());
        subscription.setAthleteId(athlete.getId());
        subscription.setStatus(SubscriptionStatus.PENDING_PAYMENT);
        subscriptions.saveAndFlush(subscription);

        CreateOrderResponse order;
        try {
            order = orders.createOrder(new CreateOrderRequest(
                    plan.getId(), plan.getCoachId(), athlete.getId(), plan.getDurationDays(), plan.getCurrency()));
        } catch (RuntimeException failure) {
            // No order exists, so the pending row must not linger as a false purchase.
            subscriptions.delete(subscription);
            subscriptions.flush();
            log.atWarn().addKeyValue("event", "plan_purchase_order_failed").addKeyValue("result", "FAILED")
                    .addKeyValue("plan_id", plan.getId()).addKeyValue("athlete_id", athleteId)
                    .addKeyValue("reason", failure.getClass().getSimpleName())
                    .log("Order creation failed; pending subscription rolled back.");
            throw failure;
        }

        subscription.setOrderId(order.order_id());
        subscription.setUpdatedAt(Instant.now());
        subscriptions.flush();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subscription_id", subscription.getId());
        result.put("order_id", subscription.getOrderId());
        result.put("plan_id", plan.getId());
        result.put("status", subscription.getStatus());
        result.put("amount_due", null); // Authoritative amount is Order's to report.
        result.put("payment_required", true);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> subscriptions(UUID athleteId) {
        var athlete = users.findByPublicId(athleteId).orElseThrow(ApiException::forbidden);
        return subscriptions.findByAthleteIdOrderByCreatedAtDesc(athlete.getId()).stream().map(s -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subscription_id", s.getId()); row.put("plan_id", s.getPlanId()); row.put("coach_id", s.getCoachId());
            row.put("order_id", s.getOrderId()); row.put("status", s.getStatus());
            row.put("starts_at", s.getStartsAt()); row.put("ends_at", s.getEndsAt());
            row.put("created_at", s.getCreatedAt());
            return row;
        }).toList();
    }

    /**
     * Applies a payment-success event. Idempotent on event id, and the activation
     * itself is a conditional update, so a redelivery or a duplicate event cannot
     * extend access twice. A pending or failed payment never reaches this method.
     */
    public boolean applyPaymentSuccess(UUID eventId, Long orderId, Instant paidAt) {
        int receipt = db.update("""
                insert into consumed_events(consumer,event_id,event_type,consumed_at)
                values(?,?,'OrderPaid',?) on conflict do nothing
                """, CONSUMER, eventId, java.sql.Timestamp.from(Instant.now()));
        if (receipt == 0) return false;
        var subscription = subscriptions.findByOrderId(orderId).orElse(null);
        if (subscription == null) {
            // Unknown order: leave the receipt so a replay stays a no-op, and log for reconciliation.
            log.atWarn().addKeyValue("event", "payment_success_unknown_order").addKeyValue("order_id", orderId)
                    .log("Payment success referenced an order with no local subscription.");
            return false;
        }
        int days = planDuration(subscription.getPlanId());
        Instant starts = paidAt == null ? Instant.now() : paidAt;
        int updated = subscriptions.activate(subscription.getId(), SubscriptionStatus.ACTIVE,
                SubscriptionStatus.PENDING_PAYMENT, starts, starts.plus(java.time.Duration.ofDays(Math.max(1, days))), Instant.now());
        if (updated == 0) {
            log.atInfo().addKeyValue("event", "payment_success_not_applied").addKeyValue("order_id", orderId)
                    .addKeyValue("status", subscription.getStatus()).log("Subscription was not pending; nothing changed.");
            return false;
        }
        log.atInfo().addKeyValue("event", "subscription_activated").addKeyValue("result", "SUCCESS")
                .addKeyValue("subscription_id", subscription.getId()).addKeyValue("order_id", orderId)
                .log("Plan subscription activated after payment success.");
        return true;
    }

    private int planDuration(Long planId) {
        Integer days = db.queryForObject("select duration_days from coach_plans where id=?", Integer.class, planId);
        return days == null ? 0 : days;
    }
}
