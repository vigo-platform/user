package ir.vigoapp.user.service.plan;

import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions.FeatureOption;
import ir.vigoapp.user.dto.plan.PlanOptionDetails.Feature;
import ir.vigoapp.user.dto.plan.PlanOptionDetails.Field;
import ir.vigoapp.user.repository.plan.PlanCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service("coachPlanFormOptionsService")
@RequiredArgsConstructor
public class CoachPlanService {
    private final PlanCatalogRepository planCatalogRepository;

    @Transactional(readOnly = true)
    public CreatePlanFormOptions getFormOptions() {
        var generalFields = new ArrayList<Field>();
        Map<Long, List<Field>> fieldsByFeature = new HashMap<>();
        for (var definition : planCatalogRepository.findActiveFieldDefinitions()) {
            if (definition.featureId() == null) {
                generalFields.add(definition.field());
            } else {
                fieldsByFeature.computeIfAbsent(definition.featureId(), ignored -> new ArrayList<>())
                        .add(definition.field());
            }
        }
        var features = planCatalogRepository.findFeatures().stream()
                .map(feature -> new FeatureOption(feature.id(), feature.code(), feature.label(),
                        List.copyOf(fieldsByFeature.getOrDefault(feature.id(), List.of()))))
                .toList();
        return CreatePlanFormOptions.builder()
                .includeOptions(planCatalogRepository.findIncludeOptions())
                .generalFields(List.copyOf(generalFields))
                .features(features)
                .build();
    }

    @Transactional(readOnly = true)
    public Feature getFeatureDetails(long id) {
        return planCatalogRepository.findFeatureDetails(id).orElseThrow(ApiException::notFound);
    }
}
