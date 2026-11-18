package ir.vigoapp.user.api.plan;

import ir.vigoapp.user.dto.Envelope;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions;
import ir.vigoapp.user.service.plan.CoachPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("coachPlanFormOptionsController")
@RequestMapping("/api/v1/coaches/me/plan")
@RequiredArgsConstructor
public class CoachPlanController {
    private final CoachPlanService coachPlans;

    @GetMapping("form-options")
    public Envelope<CreatePlanFormOptions> getFormOptions() {
        return Envelope.ok(coachPlans.getFormOptions());
    }
}
