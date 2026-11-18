package ir.vigoapp.user.api;

import ir.vigoapp.user.dto.Envelope;
import ir.vigoapp.user.service.CoachPlanService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

/** Anonymous plan catalogue. Only active plans of approved coaches are visible. */
@RestController
@RequestMapping("/api/v1/plans")
public class PublicPlanController {
    private final CoachPlanService service;

    public PublicPlanController(CoachPlanService service) { this.service = service; }

    @GetMapping
    public Envelope<?> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                            @RequestParam(required = false) Long coach_id) {
        return Envelope.ok(service.marketplace(page, size, coach_id));
    }

    @GetMapping("/{plan_id}")
    public Envelope<?> detail(@PathVariable Long plan_id) { return Envelope.ok(service.detail(plan_id)); }
}
