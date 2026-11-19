package ir.vigoapp.user.service.plan;

import ir.vigoapp.user.dto.plan.PublicCoachPlan.Feature;
import ir.vigoapp.user.dto.plan.PublicCoachPlan.Field;
import ir.vigoapp.user.dto.plan.PublicCoachPlan.Label;
import ir.vigoapp.user.repository.CoachPlanIncludeRepository;
import ir.vigoapp.user.repository.plan.CoachPlanSelectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CoachPlanContentReader {
    private final CoachPlanIncludeRepository includes;
    private final CoachPlanSelectionRepository selections;

    record Content(List<Label> includes, List<Feature> features, List<Field> generalFields) {}

    Content load(long planId) {
        var generalFields = new ArrayList<Field>();
        Map<Long, List<Field>> fieldsByFeature = new HashMap<>();
        for (var selection : selections.findSelectedFields(planId)) {
            if (selection.featureId() == null) {
                generalFields.add(selection.field());
            } else {
                fieldsByFeature.computeIfAbsent(selection.featureId(), key -> new ArrayList<>()).add(selection.field());
            }
        }

        var features = selections.findSelectedFeatures(planId).stream()
                .map(feature -> new Feature(feature.id(), feature.code(), feature.label(), feature.description(),
                        feature.category(), fieldsByFeature.getOrDefault(feature.id(), List.of())))
                .toList();
        var includedServices = includes.findOptionsForPlan(planId).stream()
                .map(option -> new Label(option.getId(), option.getCode(), option.getLabel()))
                .toList();

        return new Content(includedServices, features, generalFields);
    }
}
