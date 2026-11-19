package ir.vigoapp.user.api;

import ir.vigoapp.user.dto.Envelope;
import ir.vigoapp.user.dto.Requests;
import ir.vigoapp.user.service.CoachPlanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/plans")
@RequiredArgsConstructor
public class AdminCoachPlanController {
    private final CoachPlanService service;

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{plan_id}/status")
    public Envelope<?> setActive(@PathVariable @Positive long plan_id,
                                 @Valid @RequestBody Requests.PlanActivation body) {
        service.setActive(plan_id, body.isActive());
        return Envelope.ok(null);
    }
}
