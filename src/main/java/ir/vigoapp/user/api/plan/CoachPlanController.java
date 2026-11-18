package ir.vigoapp.user.api.plan;

import ir.vigoapp.user.dto.Envelope;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions;
import ir.vigoapp.user.service.plan.CoachPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("coachPlanFormOptionsController")
@RequestMapping("/api/v1/coaches/me/plan")
@RequiredArgsConstructor
@Slf4j
public class CoachPlanController {
    private final CoachPlanService coachPlans;

    @GetMapping("form-options")
    public Envelope<CreatePlanFormOptions> getFormOptions() {
        var options = coachPlans.getFormOptions();
        log.atInfo()
                .addKeyValue("event", "coach_plan_form_options_loaded")
                .addKeyValue("result", "SUCCESS")
                .log("Loaded coach plan form options");
        return Envelope.ok(options);
    }
}
