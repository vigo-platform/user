package ir.vigoapp.user.service.plan;

import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.dto.plan.OwnedCoachPlan.Detail;
import ir.vigoapp.user.dto.plan.OwnedCoachPlan.Summary;
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
public class OwnedCoachPlanService {
    private final CoachPlanRepository plans;
    private final CoachPlanSelectionRepository selections;
    private final CoachPlanContentReader contents;

    public List<Summary> list(long coachId) {
        var summaries = plans.findByCoachIdOrderByDisplayOrderAscIdAsc(coachId);
        if (summaries.isEmpty()) return List.of();

        var previews = selections.findPreviewFeatures(coachId, false);
        return summaries.stream().map(plan -> new Summary(plan.getId(), plan.getTitle(), plan.getSummary(),
                plan.getPrice(), plan.getCompareAtPrice(), plan.getCurrency(), plan.getDurationDays(),
                plan.isRecommended(), plan.getDisplayOrder(), previews.getOrDefault(plan.getId(), List.of()),
                plan.isActive(), plan.isArchived())).toList();
    }

    public Detail getDetail(long coachId, long planId) {
        var plan = plans.findByCoachIdAndId(coachId, planId).orElseThrow(ApiException::notFound);
        var content = contents.load(planId);
        return new Detail(plan.getId(), coachId, plan.getTitle(), plan.getSummary(), plan.getDescription(),
                plan.getPrice(), plan.getCompareAtPrice(), plan.getCurrency(), plan.getDurationDays(),
                plan.getFirstDeliveryDays(), plan.isRecommended(), plan.getDisplayOrder(),
                content.includes(), content.features(), content.generalFields(), plan.isActive(), plan.isArchived(),
                plan.getStatus(), plan.getCreatedAt(), plan.getUpdatedAt());
    }
}
