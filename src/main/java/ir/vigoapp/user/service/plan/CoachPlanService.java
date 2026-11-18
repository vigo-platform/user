package ir.vigoapp.user.service.plan;

import ir.vigoapp.user.dto.plan.CreatePlanFormOptions;
import ir.vigoapp.user.repository.plan.PlanCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("coachPlanFormOptionsService")
@RequiredArgsConstructor
public class CoachPlanService {
    private final PlanCatalogRepository planCatalogRepository;

    @Transactional(readOnly = true)
    public CreatePlanFormOptions getFormOptions() {
        return CreatePlanFormOptions.builder()
                .includeOptions(planCatalogRepository.findIncludeOptions())
                .levels(planCatalogRepository.findLevels())
                .generalFields(planCatalogRepository.findGeneralFields())
                .features(planCatalogRepository.findFeatures())
                .build();
    }
}
