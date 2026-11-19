package ir.vigoapp.user.service.plan;

import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions;
import ir.vigoapp.user.dto.plan.PlanOptionDetails.Feature;
import ir.vigoapp.user.dto.plan.PlanOptionDetails.Field;
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
                .generalFields(planCatalogRepository.findGeneralFields())
                .features(planCatalogRepository.findFeatures())
                .build();
    }

    @Transactional(readOnly = true)
    public Feature getFeatureDetails(long id) {
        return planCatalogRepository.findFeatureDetails(id).orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public Field getFieldDetails(long id) {
        return planCatalogRepository.findFieldDetails(id).orElseThrow(ApiException::notFound);
    }
}
