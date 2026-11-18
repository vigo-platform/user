package ir.vigoapp.user.dto.plan;

import lombok.Builder;

import java.util.List;

@Builder
public record CreatePlanFormOptions(List<IncludeOption> include_options) {
    public record IncludeOption(Long id, String code, String label, int display_order) {}
}
