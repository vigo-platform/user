package ir.vigoapp.user.api.plan;

import ir.vigoapp.user.dto.Envelope;
import ir.vigoapp.user.dto.plan.PublicCoachPlan.Detail;
import ir.vigoapp.user.service.plan.PublicCoachPlanService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/coaches/{coach_id}/plan")
@RequiredArgsConstructor
public class PublicCoachPlanController {
    private final PublicCoachPlanService plans;

    @GetMapping("/{plan_id}")
    public Envelope<Detail> getPlan(@PathVariable("coach_id") @Positive long coachId,
                                    @PathVariable("plan_id") @Positive long planId) {
        return Envelope.ok(plans.getDetail(coachId, planId));
    }
}
