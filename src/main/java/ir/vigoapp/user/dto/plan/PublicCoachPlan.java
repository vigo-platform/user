package ir.vigoapp.user.dto.plan;

import com.fasterxml.jackson.annotation.JsonProperty;
import ir.vigoapp.user.entity.plan.catalog.PlanFieldDefinition.ValueType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

import java.util.List;

public final class PublicCoachPlan {
    private PublicCoachPlan() {}

    public record Label(long id, String code, String label) {}

    @JsonNaming(SnakeCaseStrategy.class)
    public record Summary(long id, String title, String summary, long price, Long compareAtPrice,
                          String currency, int durationDays, boolean isRecommended, int displayOrder,
                          List<Label> features) {
        @JsonProperty("price_rial")
        public Long priceRial() { return rialAmount(currency, price); }

        @JsonProperty("compare_at_price_rial")
        public Long compareAtPriceRial() { return rialAmount(currency, compareAtPrice); }
    }

    @JsonNaming(SnakeCaseStrategy.class)
    public record Detail(long id, long coachId, String title, String summary, String description,
                         long price, Long compareAtPrice, String currency, int durationDays,
                         Integer firstDeliveryDays, boolean isRecommended, int displayOrder,
                         List<Label> includes, List<Feature> features, List<Field> generalFields) {
        @JsonProperty("price_rial")
        public Long priceRial() { return rialAmount(currency, price); }

        @JsonProperty("compare_at_price_rial")
        public Long compareAtPriceRial() { return rialAmount(currency, compareAtPrice); }
    }

    public record Feature(long id, String code, String label, String description, String category, List<Field> fields) {}

    @JsonNaming(SnakeCaseStrategy.class)
    public record Field(long id, String code, String label, String helpText, ValueType valueType,
                        JsonNode value, List<Option> selectedOptions) {}

    public record Option(String code, String label) {}

    private static Long rialAmount(String currency, Long amount) {
        return "IRR".equals(currency) ? amount : null;
    }
}
