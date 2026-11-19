package ir.vigoapp.user.api.plan;

import ir.vigoapp.user.dto.Envelope;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions;
import ir.vigoapp.user.dto.plan.OwnedCoachPlan.Detail;
import ir.vigoapp.user.dto.plan.OwnedCoachPlan.Summary;
import ir.vigoapp.user.dto.plan.PlanOptionDetails.Feature;
import ir.vigoapp.user.service.plan.CoachPlanService;
import ir.vigoapp.user.service.plan.OwnedCoachPlanService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("coachPlanFormOptionsController")
@RequestMapping("/api/v1/coaches/me/plan")
@RequiredArgsConstructor
@PreAuthorize("hasRole('COACH')")
public class CoachPlanController {
    private final CoachPlanService coachPlans;
    private final OwnedCoachPlanService ownedPlans;

    @GetMapping
    public Envelope<List<Summary>> getPlans(Authentication authentication) {
        return Envelope.ok(ownedPlans.list(Long.parseLong(authentication.getName())));
    }

    @GetMapping("/{plan_id}")
    public Envelope<Detail> getPlan(Authentication authentication, @PathVariable("plan_id") @Positive long planId) {
        return Envelope.ok(ownedPlans.getDetail(Long.parseLong(authentication.getName()), planId));
    }

    @GetMapping("form-options")
    public Envelope<CreatePlanFormOptions> getFormOptions() {
        var options = coachPlans.getFormOptions();
        return Envelope.ok(options);
    }

    @GetMapping("form-options/features/{id}")
    public Envelope<Feature> getFeatureDetails(@PathVariable @Positive long id) {
        return Envelope.ok(coachPlans.getFeatureDetails(id));
    }
}
