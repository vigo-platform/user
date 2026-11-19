package ir.vigoapp.user.service.plan.validation;

import ir.vigoapp.user.dto.plan.PlanFieldValue;
import ir.vigoapp.user.dto.plan.PlanValidationIssue;
import ir.vigoapp.user.dto.plan.PlanWriteRequest;
import ir.vigoapp.user.entity.plan.CoachPlan;
import ir.vigoapp.user.repository.plan.PlanCatalogRepository;
import ir.vigoapp.user.repository.plan.PlanCatalogRepository.IncludeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ir.vigoapp.user.dto.plan.PlanValidationIssue.*;

@Component
@RequiredArgsConstructor
public class PlanSubmissionValidator {
    private static final Set<String> PROPERTIES = Set.of("title", "summary", "description", "price", "compareAtPrice",
            "durationDays", "firstDeliveryDays", "includes", "featureIds", "fieldValues");
    private static final Set<String> ANSWER_PROPERTIES = Set.of("fieldId", "value");
    private final PlanCatalogRepository catalog;

    public record Submission(PlanWriteRequest request, List<Long> includeIds) {}

    /** Read the JSON without scalar coercion so one response can locate every independent input error. */
    public Submission validate(JsonNode body, CoachPlan plan, Set<String> previouslyIncluded) {
        if (body == null || !body.isObject()) {
            throw new PlanValidationException(List.of(at("$", "INVALID_VALUE_TYPE")));
        }
        var issues = new ArrayList<PlanValidationIssue>();
        unknownProperties(body, PROPERTIES, "", issues);
        var values = new PlanValues(issues);
        String title = values.text(body.path("title"), "title", null, true, 3, 80);
        String description = values.text(body.path("description"), "description", null, false, 0, 1500);
        String summary = body.has("summary")
                ? values.text(body.path("summary"), "summary", null, false, 0, 180) : plan.getSummary();
        long minimumPrice = CoachPlan.DEFAULT_CURRENCY.equals(plan.getCurrency()) ? 10_000 : 0;
        Long price = values.integer(body.path("price"), "price", null, true, minimumPrice, Long.MAX_VALUE);
        Long compareAtPrice = body.has("compareAtPrice")
                ? values.integer(body.path("compareAtPrice"), "compareAtPrice", null, false, 0, Long.MAX_VALUE)
                : plan.getCompareAtPrice();
        if (compareAtPrice != null && price != null && compareAtPrice <= price) {
            issues.add(at("compareAtPrice", "COMPARE_AT_PRICE_NOT_GREATER_THAN_PRICE"));
        }
        Long duration = values.integer(body.path("durationDays"), "durationDays", null, true, 1, 365);
        Integer firstDelivery = plan.getFirstDeliveryDays();
        if (body.has("firstDeliveryDays")) {
            Long days = values.integer(body.path("firstDeliveryDays"), "firstDeliveryDays", null,
                    false, 0, Integer.MAX_VALUE);
            firstDelivery = days == null ? null : days.intValue();
        }

        var selectedIncludes = resolveIncludes(body.path("includes"), previouslyIncluded, issues);
        var features = resolveFeatures(body.path("featureIds"), issues);
        var answers = resolveAnswers(body.path("fieldValues"), features, values, issues);
        if (!issues.isEmpty()) throw new PlanValidationException(issues);

        var request = new PlanWriteRequest(title, summary, description, price, compareAtPrice, duration.intValue(),
                firstDelivery, selectedIncludes.stream().map(IncludeStatus::code).toList(),
                features.stream().sorted().toList(), answers);
        return new Submission(request, selectedIncludes.stream().map(IncludeStatus::id).toList());
    }

    private List<IncludeStatus> resolveIncludes(JsonNode items, Set<String> previouslyIncluded,
                                                List<PlanValidationIssue> issues) {
        if (!array(items, "includes", true, issues)) return List.of();
        Map<String, Integer> positions = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            String path = "includes[" + i + "]";
            if (!item.isString()) {
                issues.add(at(path, "INVALID_VALUE_TYPE"));
                continue;
            }
            String code = item.stringValue();
            if (code.isBlank() || code.codePointCount(0, code.length()) > 64 || code.indexOf(0) >= 0) {
                issues.add(at(path, "INVALID_CODE"));
            } else if (positions.putIfAbsent(code, i) != null) {
                issues.add(at(path, "DUPLICATE_CODE"));
            }
        }
        var options = catalog.findIncludeStatuses(positions.keySet());
        for (var option : options) {
            int index = positions.remove(option.code());
            if (!option.active() && !previouslyIncluded.contains(option.code())) {
                issues.add(at("includes[" + index + "]", "INACTIVE_ITEM"));
            }
        }
        positions.values().forEach(i -> issues.add(at("includes[" + i + "]", "UNKNOWN_CODE")));
        return options;
    }

    private Set<Long> resolveFeatures(JsonNode items, List<PlanValidationIssue> issues) {
        if (!array(items, "featureIds", false, issues)) return Set.of();
        Map<Long, Integer> positions = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            String path = "featureIds[" + i + "]";
            Long id = readId(items.get(i), path, issues);
            if (id != null && positions.putIfAbsent(id, i) != null) issues.add(forFeature(path, "DUPLICATE_ID", id));
        }
        Set<Long> selected = new HashSet<>();
        for (var feature : catalog.findFeatureStatuses(positions.keySet())) {
            int index = positions.remove(feature.id());
            if (feature.active()) selected.add(feature.id());
            else issues.add(forFeature("featureIds[" + index + "]", "INACTIVE_ITEM", feature.id()));
        }
        positions.forEach((id, i) -> issues.add(forFeature("featureIds[" + i + "]", "UNKNOWN_ID", id)));
        return selected;
    }

    private List<PlanFieldValue> resolveAnswers(JsonNode items, Set<Long> features, PlanValues values,
                                                List<PlanValidationIssue> issues) {
        Map<Long, Integer> positions = new LinkedHashMap<>();
        if (array(items, "fieldValues", false, issues)) {
            for (int i = 0; i < items.size(); i++) {
                String path = "fieldValues[" + i + "]";
                var item = items.get(i);
                if (!item.isObject()) {
                    issues.add(at(path, "INVALID_VALUE_TYPE"));
                    continue;
                }
                unknownProperties(item, ANSWER_PROPERTIES, path + ".", issues);
                Long id = readId(item.path("fieldId"), path + ".fieldId", issues);
                if (id != null && positions.putIfAbsent(id, i) != null) {
                    issues.add(forField(path + ".fieldId", "DUPLICATE_ID", id));
                }
            }
        }

        var answers = new ArrayList<PlanFieldValue>();
        for (var definition : catalog.findSubmissionFields(positions.keySet())) {
            var field = definition.field();
            Integer index = positions.remove(field.id());
            if (index == null) {
                // The query also returns every active required general field omitted by the caller.
                issues.add(forField("fieldValues", "REQUIRED_FIELD_MISSING", field.id()));
                continue;
            }
            String path = "fieldValues[" + index + "]";
            if (!definition.active()) {
                issues.add(forField(path + ".fieldId", "INACTIVE_ITEM", field.id()));
            } else if (definition.featureId() != null && !features.contains(definition.featureId())) {
                issues.add(forField(path + ".fieldId", "FEATURE_NOT_SELECTED", field.id()));
            } else {
                var value = values.answer(field, items.get(index).path("value"), path + ".value");
                answers.add(new PlanFieldValue(field.id(), value));
            }
        }
        positions.forEach((id, i) -> issues.add(forField("fieldValues[" + i + "].fieldId", "UNKNOWN_ID", id)));
        answers.sort(Comparator.comparingLong(PlanFieldValue::fieldId));
        return answers;
    }

    private static void unknownProperties(JsonNode object, Set<String> allowed, String prefix,
                                          List<PlanValidationIssue> issues) {
        for (String name : object.propertyNames()) {
            if (!allowed.contains(name)) issues.add(at(prefix + name, "UNKNOWN_FIELD"));
        }
    }

    private static boolean array(JsonNode items, String path, boolean required, List<PlanValidationIssue> issues) {
        if (items.isMissingNode() || items.isNull()) {
            if (required) issues.add(at(path, "REQUIRED_FIELD_MISSING"));
            return false;
        }
        if (!items.isArray()) {
            issues.add(at(path, "INVALID_VALUE_TYPE"));
            return false;
        }
        if (required && items.isEmpty()) issues.add(at(path, "EMPTY_SELECTION"));
        return true;
    }

    private static Long readId(JsonNode node, String path, List<PlanValidationIssue> issues) {
        if (node.isIntegralNumber() && node.canConvertToLong() && node.longValue() > 0) return node.longValue();
        issues.add(at(path, "INVALID_ID"));
        return null;
    }
}
