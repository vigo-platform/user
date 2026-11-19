package ir.vigoapp.user.dto.plan;

import com.fasterxml.jackson.annotation.JsonProperty;
import ir.vigoapp.user.domain.plan.PlanStatus;
import ir.vigoapp.user.dto.plan.PublicCoachPlan.Label;
import ir.vigoapp.user.dto.plan.PublicCoachPlan.Option;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public record ManagedCoachPlan(long planId, long coachId, String title, String summary, String description,
                               long price, Long compareAtPrice, String currency, int durationDays,
                               Integer firstDeliveryDays, List<String> includes, List<Label> features,
                               List<FieldValue> fieldValues, PlanStatus status, boolean isActive,
                               boolean isArchived, Instant createdAt, Instant updatedAt) {
    @JsonProperty("featureIds")
    public List<Long> featureIds() {
        return features.stream().map(Label::id).toList();
    }

    public record FieldValue(long fieldId, Long featureId, String code, String label, JsonNode value,
                             List<Option> selectedOptions) {}
}
