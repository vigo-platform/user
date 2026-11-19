package ir.vigoapp.user.service.plan.validation;

import ir.vigoapp.user.dto.plan.PlanOptionDetails.Field;
import ir.vigoapp.user.dto.plan.PlanValidationIssue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.StringNode;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One submission's errors, shared by fixed plan properties and catalog fields. */
final class PlanValues {
    private final List<PlanValidationIssue> issues;

    PlanValues(List<PlanValidationIssue> issues) {
        this.issues = issues;
    }

    String text(JsonNode node, String path, Long fieldId, boolean required, int min, int max) {
        if (absent(node, path, fieldId, required)) return null;
        if (!node.isString()) {
            error(path, fieldId, "INVALID_VALUE_TYPE", null);
            return null;
        }
        String value = node.stringValue().strip();
        if (value.isEmpty()) {
            if (required) error(path, fieldId, "REQUIRED_FIELD_MISSING", null);
            return null;
        }
        int length = value.codePointCount(0, value.length());
        if (value.indexOf(0) >= 0) error(path, fieldId, "INVALID_TEXT", null);
        else if (length < min) error(path, fieldId, "TEXT_TOO_SHORT", Map.of("minLength", min));
        else if (length > max) error(path, fieldId, "TEXT_TOO_LONG", Map.of("maxLength", max));
        return value;
    }

    Long integer(JsonNode node, String path, Long fieldId, boolean required, long min, long max) {
        if (absent(node, path, fieldId, required)) return null;
        if (!node.isIntegralNumber()) {
            error(path, fieldId, "INVALID_VALUE_TYPE", null);
            return null;
        }
        BigInteger value = node.bigIntegerValue();
        if (value.compareTo(BigInteger.valueOf(min)) < 0) {
            error(path, fieldId, "INTEGER_TOO_SMALL", Map.of("min", min));
            return null;
        }
        if (value.compareTo(BigInteger.valueOf(max)) > 0) {
            error(path, fieldId, "INTEGER_TOO_LARGE", Map.of("max", max));
            return null;
        }
        return value.longValueExact();
    }

    JsonNode answer(Field field, JsonNode value, String path) {
        var rules = field.validationRules();
        if (absent(value, path, field.id(), true)) return null;
        switch (field.valueType()) {
            case INTEGER -> integer(value, path, field.id(), true, rules.path("min").asInt(Integer.MIN_VALUE),
                    rules.path("max").asInt(Integer.MAX_VALUE));
            case TEXT -> {
                String normalized = text(value, path, field.id(), true, rules.path("minLength").asInt(1),
                        rules.path("maxLength").asInt(Integer.MAX_VALUE));
                return normalized == null ? null : StringNode.valueOf(normalized);
            }
            case SINGLE_SELECT -> checkOption(value, path, field.id(), optionCodes(rules));
            case MULTI_SELECT -> {
                if (!value.isArray()) {
                    error(path, field.id(), "INVALID_VALUE_TYPE", null);
                    break;
                }
                if (value.isEmpty()) error(path, field.id(), "EMPTY_SELECTION", null);
                var allowed = optionCodes(rules);
                var seen = new HashSet<String>();
                for (int i = 0; i < value.size(); i++) {
                    var option = value.get(i);
                    String itemPath = path + "[" + i + "]";
                    if (checkOption(option, itemPath, field.id(), allowed) && !seen.add(option.stringValue())) {
                        error(itemPath, field.id(), "DUPLICATE_OPTION", null);
                    }
                }
            }
        }
        return value;
    }

    private static Set<String> optionCodes(JsonNode rules) {
        Set<String> allowed = new HashSet<>();
        for (var option : rules.path("options")) allowed.add(option.path("code").stringValue());
        return allowed;
    }

    private boolean checkOption(JsonNode value, String path, long fieldId, Set<String> allowed) {
        if (!value.isString()) error(path, fieldId, "INVALID_VALUE_TYPE", null);
        else if (!allowed.contains(value.stringValue())) error(path, fieldId, "OPTION_NOT_ALLOWED", null);
        else return true;
        return false;
    }

    private boolean absent(JsonNode value, String path, Long fieldId, boolean required) {
        if (!value.isMissingNode() && !value.isNull()) return false;
        if (required) error(path, fieldId, "REQUIRED_FIELD_MISSING", null);
        return true;
    }

    private void error(String path, Long fieldId, String code, Map<String, ?> params) {
        issues.add(new PlanValidationIssue(path, code, fieldId, null, params));
    }
}
