package ir.vigoapp.user.service;

import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.domain.*;
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
public class PlanPurchaseService {
    private static final Logger log = LoggerFactory.getLogger(PlanPurchaseService.class);
    public static final String CONSUMER = "user-plan-payments-v1";

    private final CoachPlanService plans;
    private final PlanSubscriptionRepository subscriptions;
    private final OrderClient orders;
    private final org.springframework.jdbc.core.JdbcTemplate db;

    public PlanPurchaseService(CoachPlanService plans, PlanSubscriptionRepository subscriptions,
                               OrderClient orders, org.springframework.jdbc.core.JdbcTemplate db) {
        this.plans = plans; this.subscriptions = subscriptions; this.orders = orders; this.db = db;
    }

    /**
     * Requests an order for a plan. The client sends only the plan id; any price it
     * might send is ignored because the request body has no price field.
     */
    public Map<String, Object> purchase(UUID athleteId, UUID planId) {
        var plan = plans.requirePurchasable(planId);
        if (plan.coachId.equals(athleteId)) throw ApiException.forbidden();

        var subscription = new PlanSubscription();
        subscription.planId = plan.id;
        subscription.coachId = plan.coachId;
        subscription.athleteId = athleteId;
        subscription.status = SubscriptionStatus.PENDING_PAYMENT;
        subscriptions.saveAndFlush(subscription);

        OrderClient.CreateOrderResponse order;
        try {
            order = orders.createOrder(new OrderClient.CreateOrderRequest(
                    plan.id, plan.coachId, athleteId, plan.durationDays, plan.currency));
        } catch (RuntimeException failure) {
            // No order exists, so the pending row must not linger as a false purchase.
            subscriptions.delete(subscription);
            subscriptions.flush();
            log.atWarn().addKeyValue("event", "plan_purchase_order_failed").addKeyValue("result", "FAILED")
                    .addKeyValue("plan_id", plan.id).addKeyValue("athlete_id", athleteId)
                    .addKeyValue("reason", failure.getClass().getSimpleName())
                    .log("Order creation failed; pending subscription rolled back.");
            throw failure;
        }

        subscription.orderId = order.order_id();
        subscription.updatedAt = Instant.now();
        subscriptions.flush();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("subscription_id", subscription.id);
        result.put("order_id", subscription.orderId);
        result.put("plan_id", plan.id);
        result.put("status", subscription.status);
        result.put("amount_due", null); // Authoritative amount is Order's to report.
        result.put("payment_required", true);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> subscriptions(UUID athleteId) {
        return subscriptions.findByAthleteIdOrderByCreatedAtDesc(athleteId).stream().map(s -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("subscription_id", s.id); row.put("plan_id", s.planId); row.put("coach_id", s.coachId);
            row.put("order_id", s.orderId); row.put("status", s.status);
            row.put("starts_at", s.startsAt); row.put("ends_at", s.endsAt);
            row.put("created_at", s.createdAt);
            return row;
        }).toList();
    }

    /**
     * Applies a payment-success event. Idempotent on event id, and the activation
     * itself is a conditional update, so a redelivery or a duplicate event cannot
     * extend access twice. A pending or failed payment never reaches this method.
     */
    public boolean applyPaymentSuccess(UUID eventId, UUID orderId, Instant paidAt) {
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
        int days = planDuration(subscription.planId);
        Instant starts = paidAt == null ? Instant.now() : paidAt;
        int updated = subscriptions.activate(subscription.id, SubscriptionStatus.ACTIVE,
                SubscriptionStatus.PENDING_PAYMENT, starts, starts.plus(java.time.Duration.ofDays(Math.max(1, days))), Instant.now());
        if (updated == 0) {
            log.atInfo().addKeyValue("event", "payment_success_not_applied").addKeyValue("order_id", orderId)
                    .addKeyValue("status", subscription.status).log("Subscription was not pending; nothing changed.");
            return false;
        }
        log.atInfo().addKeyValue("event", "subscription_activated").addKeyValue("result", "SUCCESS")
                .addKeyValue("subscription_id", subscription.id).addKeyValue("order_id", orderId)
                .log("Plan subscription activated after payment success.");
        return true;
    }

    private int planDuration(UUID planId) {
        Integer days = db.queryForObject("select duration_days from coach_plans where plan_id=?", Integer.class, planId);
        return days == null ? 0 : days;
    }
}
