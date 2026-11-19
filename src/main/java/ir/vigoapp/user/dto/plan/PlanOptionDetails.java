package ir.vigoapp.user.dto.plan;

import com.fasterxml.jackson.annotation.JsonProperty;
import ir.vigoapp.user.entity.plan.catalog.PlanFieldDefinition.ValueType;
import tools.jackson.databind.JsonNode;

import java.util.List;

public sealed interface PlanOptionDetails {
    enum Type { FEATURE, FIELD }

    @JsonProperty("type")
    Type type();

    record Feature(long id, String code, String label, String description,
                   String category, List<Field> fields) implements PlanOptionDetails {
        @Override
        public Type type() { return Type.FEATURE; }
    }

    record Field(long id, String code, String label, String helpText, ValueType valueType,
                 boolean required, JsonNode validationRules) implements PlanOptionDetails {
        @Override
        public Type type() { return Type.FIELD; }
    }
}
