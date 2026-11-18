package ir.vigoapp.user.service.plan;

import ir.vigoapp.user.dto.plan.CreatePlanFormOptions;
import ir.vigoapp.user.repository.PlanIncludeOptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("coachPlanFormOptionsService")
@RequiredArgsConstructor
public class CoachPlanService {
    private final PlanIncludeOptionRepository includeOptions;

    @Transactional(readOnly = true)
    public CreatePlanFormOptions getFormOptions() {
        var options = includeOptions.findByActiveTrueOrderByDisplayOrderAscIdAsc().stream()
                .map(option -> new CreatePlanFormOptions.IncludeOption(
                        option.getId(), option.getCode(), option.getLabel(), option.getDisplayOrder()))
                .toList();
        return new CreatePlanFormOptions(options);
    }
}
