package ir.vigoapp.user.service.plan;

import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.domain.AccountStatus;
import ir.vigoapp.user.domain.CoachStatus;
import ir.vigoapp.user.domain.Role;
import ir.vigoapp.user.dto.plan.PublicCoachPlan.Detail;
import ir.vigoapp.user.dto.plan.PublicCoachPlan.Summary;
import ir.vigoapp.user.repository.CoachPlanRepository;
import ir.vigoapp.user.repository.plan.CoachPlanSelectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
@RequiredArgsConstructor
public class PublicCoachPlanService {
    private final CoachPlanRepository plans;
    private final CoachPlanSelectionRepository selections;
    private final CoachPlanContentReader contents;

    /** Used after the profile's public eligibility check, in the same transaction. */
    public List<Summary> summariesForPublicCoach(long coachId) {
        var summaries = plans.findByCoachIdAndActiveTrueAndArchivedFalseOrderByDisplayOrderAscIdAsc(coachId);
        if (summaries.isEmpty()) return List.of();

        var previews = selections.findPreviewFeatures(coachId, true);
        return summaries.stream().map(plan -> new Summary(plan.getId(), plan.getTitle(), plan.getSummary(),
                plan.getPrice(), plan.getCompareAtPrice(), plan.getCurrency(), plan.getDurationDays(),
                plan.isRecommended(), plan.getDisplayOrder(), previews.getOrDefault(plan.getId(), List.of()))).toList();
    }

    public Detail getDetail(long coachId, long planId) {
        var plan = plans.findPublicPlan(coachId, planId, CoachStatus.APPROVED, AccountStatus.ACTIVE, Role.COACH)
                .orElseThrow(ApiException::notFound);

        var content = contents.load(planId);
        return new Detail(plan.getId(), coachId, plan.getTitle(), plan.getSummary(), plan.getDescription(),
                plan.getPrice(), plan.getCompareAtPrice(), plan.getCurrency(), plan.getDurationDays(),
                plan.getFirstDeliveryDays(), plan.isRecommended(), plan.getDisplayOrder(),
                content.includes(), content.features(), content.generalFields());
    }
}
