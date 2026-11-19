package ir.vigoapp.user.api.plan;

import ir.vigoapp.user.dto.Envelope;
import ir.vigoapp.user.dto.plan.ManagedCoachPlan;
import ir.vigoapp.user.dto.plan.PlanValidationIssue;
import ir.vigoapp.user.service.plan.CoachPlanManagementService;
import ir.vigoapp.user.service.plan.validation.PlanValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Coach-owned plan management. Every mutation derives the coach identity from the
 * token; a coach_id in the URL would let one coach address another coach's plans.
 */
@RestController
@RequestMapping("/api/v1/coaches/me/plans")
@RequiredArgsConstructor
@PreAuthorize("hasRole('COACH')")
public class CoachPlanManagementController {
    private final CoachPlanManagementService service;

    @ExceptionHandler(PlanValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Envelope<List<PlanValidationIssue>> validation(PlanValidationException error) {
        return Envelope.error(error.code, error.getMessage(), error.persian, error.issues());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Envelope<List<PlanValidationIssue>> unreadable() {
        return Envelope.error("VALIDATION_FAILED", "Invalid request body", "بدنه درخواست نامعتبر است",
                List.of(PlanValidationIssue.at("$", "INVALID_REQUEST_BODY")));
    }

    private static long id(Authentication a) { return Long.parseLong(a.getName()); }

    @GetMapping
    public Envelope<List<ManagedCoachPlan>> own(Authentication a) { return Envelope.ok(service.own(id(a))); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Envelope<ManagedCoachPlan> create(Authentication a, @RequestBody JsonNode body) {
        return Envelope.ok(service.create(id(a), body));
    }

    @PutMapping("/{plan_id}")
    public Envelope<ManagedCoachPlan> update(Authentication a, @PathVariable Long plan_id, @RequestBody JsonNode body) {
        return Envelope.ok(service.update(id(a), plan_id, body));
    }

    @DeleteMapping("/{plan_id}")
    public Envelope<?> archive(Authentication a, @PathVariable Long plan_id) {
        service.archive(id(a), plan_id);
        return Envelope.ok(null);
    }

    @PostMapping("/{plan_id}/unarchive")
    public Envelope<?> unarchive(Authentication a, @PathVariable Long plan_id) {
        service.unarchive(id(a), plan_id);
        return Envelope.ok(null);
    }
}
