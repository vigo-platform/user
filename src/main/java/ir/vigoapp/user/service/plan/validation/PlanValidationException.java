package ir.vigoapp.user.service.plan.validation;

import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.dto.plan.PlanValidationIssue;

import java.util.List;

public final class PlanValidationException extends ApiException {
    private final List<PlanValidationIssue> issues;

    public PlanValidationException(List<PlanValidationIssue> issues) {
        super(400, "VALIDATION_FAILED", "Plan validation failed", "اطلاعات پلن نامعتبر است");
        this.issues = List.copyOf(issues);
    }

    public List<PlanValidationIssue> issues() { return issues; }
}
