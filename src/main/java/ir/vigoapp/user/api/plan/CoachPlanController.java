package ir.vigoapp.user.api.plan;

import ir.vigoapp.user.dto.Envelope;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions;
import ir.vigoapp.user.dto.plan.PlanOptionDetails.Feature;
import ir.vigoapp.user.dto.plan.PlanOptionDetails.Field;
import ir.vigoapp.user.service.plan.CoachPlanService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("coachPlanFormOptionsController")
@RequestMapping("/api/v1/coaches/me/plan")
@RequiredArgsConstructor
@Slf4j
public class CoachPlanController {
    private final CoachPlanService coachPlans;

    @PreAuthorize("hasRole('COACH')")
    @GetMapping("form-options")
    public Envelope<CreatePlanFormOptions> getFormOptions() {
        var options = coachPlans.getFormOptions();
        return Envelope.ok(options);
    }

    @PreAuthorize("hasRole('COACH')")
    @GetMapping("form-options/features/{id}")
    public Envelope<Feature> getFeatureDetails(@PathVariable @Positive long id) {
        return Envelope.ok(coachPlans.getFeatureDetails(id));
    }

    @PreAuthorize("hasRole('COACH')")
    @GetMapping("form-options/fields/{id}")
    public Envelope<Field> getFieldDetails(@PathVariable @Positive long id) {
        return Envelope.ok(coachPlans.getFieldDetails(id));
    }
}
