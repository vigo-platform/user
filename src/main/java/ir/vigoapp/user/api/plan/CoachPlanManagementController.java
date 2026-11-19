package ir.vigoapp.user.api.plan;

import ir.vigoapp.user.api.Errors;
import ir.vigoapp.user.dto.Envelope;
import ir.vigoapp.user.dto.plan.ManagedCoachPlan;
import ir.vigoapp.user.dto.plan.PlanValidationIssue;
import ir.vigoapp.user.service.plan.CoachPlanManagementService;
import ir.vigoapp.user.service.plan.validation.PlanValidationException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class CoachPlanManagementController {
    private final CoachPlanManagementService service;

    @ExceptionHandler(PlanValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Envelope<List<PlanValidationIssue>> validation(PlanValidationException error) {
        return Envelope.error(error.code, error.getMessage(), error.persian, error.issues());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Envelope<List<PlanValidationIssue>> unreadable(HttpServletRequest request) {
        Errors.logPlanInputRejection(request, "INVALID_REQUEST_BODY");
        return Envelope.error("VALIDATION_FAILED", "Invalid request body", "بدنه درخواست نامعتبر است",
                List.of(PlanValidationIssue.at("$", "INVALID_REQUEST_BODY")));
    }

    private static long id(Authentication a) { return Long.parseLong(a.getName()); }

    @GetMapping
    public Envelope<List<ManagedCoachPlan>> own(Authentication a) { return Envelope.ok(service.own(id(a))); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Envelope<ManagedCoachPlan> create(Authentication a, @RequestBody JsonNode body) {
        long coachId = id(a);
        logWriteRequest("create", "POST", coachId, null);
        return Envelope.ok(service.create(coachId, body));
    }

    @PatchMapping("/{plan_id}")
    public Envelope<ManagedCoachPlan> patch(Authentication a, @PathVariable Long plan_id, @RequestBody JsonNode body) {
        long coachId = id(a);
        logWriteRequest("patch", "PATCH", coachId, plan_id);
        return Envelope.ok(service.patch(coachId, plan_id, body));
    }

    @DeleteMapping("/{plan_id}")
    public Envelope<?> archive(Authentication a, @PathVariable Long plan_id) {
        long coachId = id(a);
        logWriteRequest("archive", "DELETE", coachId, plan_id);
        service.archive(coachId, plan_id);
        return Envelope.ok(null);
    }

    @PostMapping("/{plan_id}/unarchive")
    public Envelope<?> unarchive(Authentication a, @PathVariable Long plan_id) {
        long coachId = id(a);
        logWriteRequest("unarchive", "POST", coachId, plan_id);
        service.unarchive(coachId, plan_id);
        return Envelope.ok(null);
    }

    private static void logWriteRequest(String operation, String method, long coachId, Long planId) {
        var event = log.atInfo().addKeyValue("event", "coach_plan_write_requested")
                .addKeyValue("operation", operation).addKeyValue("result", "REQUESTED")
                .addKeyValue("http_method", method).addKeyValue("actor_user_id", coachId)
                .addKeyValue("coach_id", coachId);
        if (planId != null) event.addKeyValue("plan_id", planId);
        event.log("Coach requested a plan write.");
    }
}
