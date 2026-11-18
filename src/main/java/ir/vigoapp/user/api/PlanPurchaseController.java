package ir.vigoapp.user.api;

import ir.vigoapp.user.dto.Envelope;
import ir.vigoapp.user.service.PlanPurchaseService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Customer-facing plan purchase. The body carries no price: the request path
 * identifies the plan and Order owns the amount.
 */
@RestController
@RequestMapping("/api/v1/plans")
public class PlanPurchaseController {
    private final PlanPurchaseService purchases;

    public PlanPurchaseController(PlanPurchaseService purchases) { this.purchases = purchases; }

    private static UUID id(Authentication a) { return UUID.fromString(a.getName()); }

    @PostMapping("/{plan_id}/purchase")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Envelope<?> purchase(Authentication a, @PathVariable Long plan_id) {
        return Envelope.ok(purchases.purchase(id(a), plan_id));
    }

    @GetMapping("/subscriptions/me")
    public Envelope<?> subscriptions(Authentication a) { return Envelope.ok(purchases.subscriptions(id(a))); }
}
