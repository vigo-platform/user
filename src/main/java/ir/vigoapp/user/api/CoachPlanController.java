package ir.vigoapp.user.api;

import ir.vigoapp.user.dto.Envelope;
import ir.vigoapp.user.dto.Requests;
import ir.vigoapp.user.service.CoachPlanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Coach-owned plan management. Every mutation derives the coach identity from the
 * token; a coach_id in the URL would let one coach address another coach's plans.
 */
@RestController
@RequestMapping("/api/v1/coaches/me/plans")
public class CoachPlanController {
    private final CoachPlanService service;

    public CoachPlanController(CoachPlanService service) { this.service = service; }

    private static UUID id(Authentication a) { return UUID.fromString(a.getName()); }

    @GetMapping
    public Envelope<?> own(Authentication a) { return Envelope.ok(service.own(id(a))); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Envelope<?> create(Authentication a, @Valid @RequestBody Requests.Plan body) {
        return Envelope.ok(service.create(id(a), body));
    }

    @PutMapping("/{plan_id}")
    public Envelope<?> update(Authentication a, @PathVariable Long plan_id, @Valid @RequestBody Requests.Plan body) {
        return Envelope.ok(service.update(id(a), plan_id, body));
    }

    @DeleteMapping("/{plan_id}")
    public Envelope<?> archive(Authentication a, @PathVariable Long plan_id) {
        service.archive(id(a), plan_id);
        return Envelope.ok(null);
    }

    @PostMapping("/{plan_id}/activate")
    public Envelope<?> activate(Authentication a, @PathVariable Long plan_id) {
        service.activate(id(a), plan_id);
        return Envelope.ok(null);
    }
}
