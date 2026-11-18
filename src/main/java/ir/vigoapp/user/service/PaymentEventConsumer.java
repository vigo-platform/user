package ir.vigoapp.user.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Consumes payment-success events published by Order/Payment.
 *
 * <p><strong>Provisional contract.</strong> No Order service or event contract
 * exists in this organization yet, so the payload below is the proposed schema
 * used by User; the listener is disabled by default and must be
 * enabled only after the Order owner confirms it. Envelope fields follow the
 * existing Vigo convention:<br>
 * {@code {event_id, event_type:"OrderPaid", event_version:1, occurred_at, producer,
 * aggregate_id:"<order id>", aggregate_version, data:{order_id, athlete_id, plan_id,
 * amount, currency}}}
 *
 * <p>Only the identifiers are used. The amount is never read into User state:
 * Order owns the price snapshot, and User must not depend on a client- or
 * event-supplied price.
 */
@Component
@ConditionalOnProperty(name = "app.order.payment-events.enabled", havingValue = "true")
public class PaymentEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PlanPurchaseService purchases;

    public PaymentEventConsumer(PlanPurchaseService purchases) { this.purchases = purchases; }

    @KafkaListener(id = "userPaymentEvents", idIsGroup = false,
            topics = "${app.order.payment-topic:order.events}")
    public void consume(ConsumerRecord<String, String> record) {
        UUID eventId;
        long orderId;
        Instant paidAt;
        try {
            JsonNode root = JSON.readTree(record.value());
            if (!"OrderPaid".equals(root.path("event_type").asString(""))) return;
            eventId = UUID.fromString(root.path("event_id").asString(""));
            JsonNode data = root.get("data");
            JsonNode order = data.path("order_id");
            if (!order.isIntegralNumber() || !order.canConvertToLong() || order.asLong() <= 0) {
                throw new IllegalArgumentException("Invalid order ID");
            }
            orderId = order.asLong();
            try {
                paidAt = Instant.parse(root.path("occurred_at").asString(""));
            } catch (DateTimeParseException ignored) {
                paidAt = Instant.now();
            }
        } catch (RuntimeException malformed) {
            // A malformed event cannot be applied; log without payload contents and drop it.
            log.atWarn().addKeyValue("event", "payment_event_rejected").addKeyValue("result", "REJECTED")
                    .addKeyValue("reason", malformed.getClass().getSimpleName())
                    .log("Payment event could not be parsed and was ignored.");
            return;
        }
        try {
            purchases.applyPaymentSuccess(eventId, orderId, paidAt);
        } catch (RuntimeException failure) {
            // Rethrow so the offset is not committed and the event is redelivered.
            log.atError().addKeyValue("event", "payment_event_failed").addKeyValue("result", "FAILED")
                    .addKeyValue("order_id", orderId).addKeyValue("reason", failure.getClass().getSimpleName())
                    .log("Payment event processing failed.");
            throw new IllegalStateException("Payment event processing failed; consumer must retry");
        }
    }
}
