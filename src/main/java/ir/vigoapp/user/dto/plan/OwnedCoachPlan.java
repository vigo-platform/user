package ir.vigoapp.user.dto.plan;

import com.fasterxml.jackson.annotation.JsonProperty;
import ir.vigoapp.user.domain.plan.PlanStatus;
import ir.vigoapp.user.dto.plan.PublicCoachPlan.Feature;
import ir.vigoapp.user.dto.plan.PublicCoachPlan.Field;
import ir.vigoapp.user.dto.plan.PublicCoachPlan.Label;
import tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;

import static ir.vigoapp.user.dto.plan.PublicCoachPlan.rialAmount;

public final class OwnedCoachPlan {
    private OwnedCoachPlan() {}

    @JsonNaming(SnakeCaseStrategy.class)
    public record Summary(long id, String title, String summary, long price, Long compareAtPrice,
                          String currency, int durationDays, boolean isRecommended, int displayOrder,
                          List<Label> features, boolean isActive, boolean isArchived) {
        @JsonProperty("price_rial")
        public Long priceRial() { return rialAmount(currency, price); }

        @JsonProperty("compare_at_price_rial")
        public Long compareAtPriceRial() { return rialAmount(currency, compareAtPrice); }
    }

    @JsonNaming(SnakeCaseStrategy.class)
    public record Detail(long id, long coachId, String title, String summary, String description,
                         long price, Long compareAtPrice, String currency, int durationDays,
                         Integer firstDeliveryDays, boolean isRecommended, int displayOrder,
                         List<Label> includes, List<Feature> features, List<Field> generalFields,
                         boolean isActive, boolean isArchived, PlanStatus status, Instant createdAt, Instant updatedAt) {
        @JsonProperty("price_rial")
        public Long priceRial() { return rialAmount(currency, price); }

        @JsonProperty("compare_at_price_rial")
        public Long compareAtPriceRial() { return rialAmount(currency, compareAtPrice); }
    }
}
