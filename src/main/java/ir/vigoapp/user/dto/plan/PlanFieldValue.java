package ir.vigoapp.user.dto.plan;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.JsonNode;

public record PlanFieldValue(@Positive long fieldId, @NotNull JsonNode value) {}
