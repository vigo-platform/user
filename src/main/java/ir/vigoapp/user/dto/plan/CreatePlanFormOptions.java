package ir.vigoapp.user.dto.plan;

import lombok.Builder;

import java.util.List;

@Builder
public record CreatePlanFormOptions(
        List<Item> includeOptions,
        List<LevelOption> levels,
        List<Item> generalFields,
        List<Item> features
) {
    public record Item(long id, String code, String label) {}

    public record LevelOption(String code, String label) {}
}