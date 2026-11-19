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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static ir.vigoapp.user.dto.plan.PlanValidationIssue.*;

@Component
@RequiredArgsConstructor
public class PlanSubmissionValidator {
    private static final Set<String> SCALAR_PROPERTIES = Set.of("title", "summary", "description", "price",
            "durationDays", "firstDeliveryDays");
    private static final Set<String> CREATE_SELECTIONS = Set.of("includes", "featureIds", "fieldValues");
    private static final Set<String> PATCH_SELECTIONS = Set.of("includes", "features", "fields");
    private static final Set<String> READ_ONLY = Set.of("id", "planId", "coachId", "currency", "status", "isActive",
            "isArchived", "displayOrder", "isRecommended", "createdAt", "updatedAt", "version");
    private static final Set<String> FEATURE_PROPERTIES = Set.of("featureId", "fields");
    private static final Set<String> ANSWER_PROPERTIES = Set.of("fieldId", "value");
    private final PlanCatalogRepository catalog;

    private enum Operation { CREATE, UPDATE, PATCH }

    public record Submission(PlanWriteRequest request, List<Long> includeIds, List<Long> touchedFieldIds,
                             List<Long> removedFeatureIds) {}

    private record Answer(String path, Long featureId, JsonNode value) {}
    private record FeatureChanges(Set<Long> selected, List<Long> removed) {}

    /** Read the JSON without scalar coercion so one response can locate every independent input error. */
    public Submission validateCreate(JsonNode body, CoachPlan plan) {
        return validate(body, plan, Set.of(), Operation.CREATE);
    }

    public Submission validateUpdate(JsonNode body, CoachPlan plan, Set<String> previouslyIncluded) {
        return validate(body, plan, previouslyIncluded, Operation.UPDATE);
    }

    public Submission validatePatch(JsonNode body, CoachPlan plan, Set<String> previouslyIncluded) {
        return validate(body, plan, previouslyIncluded, Operation.PATCH);
    }

    private Submission validate(JsonNode body, CoachPlan plan, Set<String> previouslyIncluded,
                                Operation operation) {
        if (body == null || !body.isObject()) {
            throw new PlanValidationException(List.of(at("$", "INVALID_VALUE_TYPE")));
        }
        var issues = new ArrayList<PlanValidationIssue>();
        boolean partial = operation == Operation.PATCH;
        boolean allowsComparisonPrice = operation != Operation.CREATE;
        var selections = partial ? PATCH_SELECTIONS : CREATE_SELECTIONS;
        for (var name : body.propertyNames()) {
            boolean editableComparisonPrice = allowsComparisonPrice && name.equals("compareAtPrice");
            if (!SCALAR_PROPERTIES.contains(name) && !selections.contains(name) && !editableComparisonPrice) {
                issues.add(at(name, partial && READ_ONLY.contains(name) ? "READ_ONLY_FIELD" : "UNKNOWN_FIELD"));
            }
        }
        var values = new PlanValues(issues);
        String title = !partial || body.has("title")
                ? values.text(body.path("title"), "title", null, true, 3, 80) : plan.getTitle();
        String description = !partial || body.has("description")
                ? values.text(body.path("description"), "description", null, false, 0, 1500) : plan.getDescription();
        String summary = body.has("summary")
                ? values.text(body.path("summary"), "summary", null, false, 0, 180) : plan.getSummary();
        long minimumPrice = CoachPlan.DEFAULT_CURRENCY.equals(plan.getCurrency()) ? 10_000 : 0;
        boolean writesPrice = !partial || body.has("price");
        Long price = writesPrice
                ? values.integer(body.path("price"), "price", null, true, minimumPrice, Long.MAX_VALUE)
                : Long.valueOf(plan.getPrice());
        Long compareAtPrice = writesPrice ? price : plan.getCompareAtPrice();
        if (allowsComparisonPrice && body.has("compareAtPrice")) {
            compareAtPrice = values.integer(body.path("compareAtPrice"), "compareAtPrice", null, false, 0, Long.MAX_VALUE);
            if (compareAtPrice != null && price != null && compareAtPrice < price) {
                issues.add(at("compareAtPrice", "COMPARE_AT_PRICE_NOT_GREATER_THAN_PRICE"));
            }
        }
        Long duration = !partial || body.has("durationDays")
                ? values.integer(body.path("durationDays"), "durationDays", null, true, 1, 365)
                : Long.valueOf(plan.getDurationDays());
        Integer firstDelivery = plan.getFirstDeliveryDays();
        if (body.has("firstDeliveryDays")) {
            Long days = values.integer(body.path("firstDeliveryDays"), "firstDeliveryDays", null,
                    false, 0, Integer.MAX_VALUE);
            firstDelivery = days == null ? null : days.intValue();
        }

        var selectedIncludes = !partial || body.has("includes")
                ? resolveIncludes(body.path("includes"), previouslyIncluded, partial, issues) : List.<IncludeStatus>of();
        Map<Long, Answer> pending = new LinkedHashMap<>();
        String fieldsKey = partial ? "fields" : "fieldValues";
        collectAnswers(body.path(fieldsKey), fieldsKey, null, pending, partial, issues);
        String featuresKey = partial ? "features" : "featureIds";
        var features = resolveFeatures(body.path(featuresKey), featuresKey, partial, pending, issues);
        var answers = validateAnswers(pending, features.selected(), partial, values, issues);
        if (!issues.isEmpty()) throw new PlanValidationException(issues);

        var request = new PlanWriteRequest(title, summary, description, price, compareAtPrice, duration.intValue(),
                firstDelivery, selectedIncludes.stream().map(IncludeStatus::code).toList(),
                features.selected().stream().sorted().toList(), answers);
        return new Submission(request, selectedIncludes.stream().map(IncludeStatus::id).toList(),
                List.copyOf(pending.keySet()), features.removed());
    }

    private List<IncludeStatus> resolveIncludes(JsonNode items, Set<String> previouslyIncluded,
                                                boolean nested, List<PlanValidationIssue> issues) {
        if (!array(items, "includes", true, issues)) return List.of();
        Map<String, String> positions = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            String path = "includes[" + i + "]";
            if (nested) {
                if (!item.isObject()) {
                    issues.add(at(path, "INVALID_VALUE_TYPE"));
                    continue;
                }
                unknownProperties(item, Set.of("code"), path + ".", issues);
                item = item.path("code");
                path += ".code";
            }
            if (!item.isString()) {
                issues.add(at(path, "INVALID_VALUE_TYPE"));
                continue;
            }
            String code = item.stringValue();
            if (code.isBlank() || code.codePointCount(0, code.length()) > 64 || code.indexOf(0) >= 0) {
                issues.add(at(path, "INVALID_CODE"));
            } else if (positions.putIfAbsent(code, path) != null) {
                issues.add(at(path, "DUPLICATE_CODE"));
            }
        }
        var options = catalog.findIncludeStatuses(positions.keySet());
        for (var option : options) {
            String path = positions.remove(option.code());
            if (!option.active() && !previouslyIncluded.contains(option.code())) {
                issues.add(at(path, "INACTIVE_ITEM"));
            }
        }
        positions.values().forEach(path -> issues.add(at(path, "UNKNOWN_CODE")));
        return options;
    }

    private FeatureChanges resolveFeatures(JsonNode items, String key, boolean nested, Map<Long, Answer> answers,
                                           List<PlanValidationIssue> issues) {
        if (!selectionArray(items, key, nested, issues)) return new FeatureChanges(Set.of(), List.of());
        Map<Long, String> positions = new LinkedHashMap<>();
        Set<Long> removed = new LinkedHashSet<>();
        for (int i = 0; i < items.size(); i++) {
            String path = key + "[" + i + "]";
            var item = items.get(i);
            String idPath = nested ? path + ".featureId" : path;
            if (nested) {
                if (!item.isObject()) {
                    issues.add(at(path, "INVALID_VALUE_TYPE"));
                    continue;
                }
                unknownProperties(item, FEATURE_PROPERTIES, path + ".", issues);
            }
            Long id = readId(nested ? item.path("featureId") : item, idPath, issues);
            if (id == null) continue;
            if (positions.putIfAbsent(id, idPath) != null) {
                issues.add(forFeature(idPath, "DUPLICATE_ID", id));
                continue;
            }
            if (nested) {
                var fields = item.path("fields");
                if (fields.isNull()) removed.add(id);
                else collectAnswers(fields, path + ".fields", id, answers, true, issues);
            }
        }
        Set<Long> selected = new HashSet<>();
        for (var feature : catalog.findFeatureStatuses(positions.keySet())) {
            String path = positions.remove(feature.id());
            if (removed.contains(feature.id())) continue;
            if (feature.active()) selected.add(feature.id());
            else issues.add(forFeature(path, "INACTIVE_ITEM", feature.id()));
        }
        positions.forEach((id, path) -> issues.add(forFeature(path, "UNKNOWN_ID", id)));
        return new FeatureChanges(selected, List.copyOf(removed));
    }

    private static void collectAnswers(JsonNode items, String key, Long parent, Map<Long, Answer> pending,
                                       boolean partial, List<PlanValidationIssue> issues) {
        if (!selectionArray(items, key, partial, issues)) return;
        for (int i = 0; i < items.size(); i++) {
            String path = key + "[" + i + "]";
            var item = items.get(i);
            if (!item.isObject()) {
                issues.add(at(path, "INVALID_VALUE_TYPE"));
                continue;
            }
            unknownProperties(item, ANSWER_PROPERTIES, path + ".", issues);
            Long id = readId(item.path("fieldId"), path + ".fieldId", issues);
            if (id != null && pending.putIfAbsent(id, new Answer(path, parent, item.path("value"))) != null) {
                issues.add(forField(path + ".fieldId", "DUPLICATE_ID", id));
            }
        }
    }

    private List<PlanFieldValue> validateAnswers(Map<Long, Answer> pending, Set<Long> features, boolean partial,
                                                PlanValues values, List<PlanValidationIssue> issues) {
        var answers = new ArrayList<PlanFieldValue>();
        var unknown = new LinkedHashSet<>(pending.keySet());
        for (var definition : catalog.findSubmissionFields(pending.keySet(), !partial)) {
            var field = definition.field();
            unknown.remove(field.id());
            var answer = pending.get(field.id());
            if (answer == null) {
                issues.add(forField("fieldValues", "REQUIRED_FIELD_MISSING", field.id()));
                continue;
            }
            String path = answer.path();
            if (!definition.active()) {
                issues.add(forField(path + ".fieldId", "INACTIVE_ITEM", field.id()));
            } else if (partial && !Objects.equals(definition.featureId(), answer.featureId())) {
                issues.add(forField(path + ".fieldId", "FIELD_SCOPE_MISMATCH", field.id()));
            } else if (definition.featureId() != null && !features.contains(definition.featureId())) {
                issues.add(forField(path + ".fieldId", "FEATURE_NOT_SELECTED", field.id()));
            } else {
                var value = values.answer(field, answer.value(), path + ".value");
                answers.add(new PlanFieldValue(field.id(), value));
            }
        }
        unknown.forEach(id -> issues.add(forField(pending.get(id).path() + ".fieldId", "UNKNOWN_ID", id)));
        answers.sort(Comparator.comparingLong(PlanFieldValue::fieldId));
        return answers;
    }

    private static boolean selectionArray(JsonNode items, String path, boolean partial, List<PlanValidationIssue> issues) {
        if (partial && items.isNull()) {
            issues.add(at(path, "INVALID_VALUE_TYPE"));
            return false;
        }
        return array(items, path, false, issues);
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
