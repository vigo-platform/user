package ir.vigoapp.user.dto.plan;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanValidationIssue(String field, String code, Long fieldId, Long featureId, Map<String, ?> params) {
    public static PlanValidationIssue at(String path, String code) {
        return new PlanValidationIssue(path, code, null, null, null);
    }

    public static PlanValidationIssue forFeature(String path, String code, long id) {
        return new PlanValidationIssue(path, code, null, id, null);
    }

    public static PlanValidationIssue forField(String path, String code, long id) {
        return new PlanValidationIssue(path, code, id, null, null);
    }
}
