package ir.vigoapp.user.dto.plan;

import ir.vigoapp.user.dto.plan.PlanOptionDetails.Field;
import lombok.Builder;

import java.util.List;

@Builder
public record CreatePlanFormOptions(
        List<Item> includeOptions,
        List<Field> generalFields,
        List<Item> features
) {
    public record Item(long id, String code, String label) {}
}
