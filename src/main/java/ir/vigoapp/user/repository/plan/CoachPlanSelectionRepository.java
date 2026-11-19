package ir.vigoapp.user.repository.plan;

import ir.vigoapp.user.dto.plan.PlanFieldValue;
import ir.vigoapp.user.dto.plan.PublicCoachPlan.Field;
import ir.vigoapp.user.dto.plan.PublicCoachPlan.Label;
import ir.vigoapp.user.dto.plan.PublicCoachPlan.Option;
import ir.vigoapp.user.entity.plan.catalog.PlanFieldDefinition.ValueType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class CoachPlanSelectionRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public Map<Long, List<Label>> findPreviewFeatures(long coachId, boolean publicOnly) {
        return jdbc.sql("""
                SELECT plan.id AS plan_id, feature.id, feature.code, feature.title
                FROM coach_plans plan
                JOIN LATERAL (
                    SELECT catalog.id, catalog.code, catalog.title, catalog.display_order
                    FROM coach_plan_features selected
                    JOIN plan_feature_catalog catalog ON catalog.id = selected.feature_id
                    WHERE selected.plan_id = plan.id
                    ORDER BY catalog.display_order, catalog.id
                    LIMIT 3
                ) feature ON TRUE
                WHERE plan.coach_user_id = :coachId
                    AND (NOT :publicOnly OR (plan.is_active AND NOT plan.is_archived))
                ORDER BY plan.id, feature.display_order, feature.id
                """).param("coachId", coachId).param("publicOnly", publicOnly).query(rows -> {
            Map<Long, List<Label>> features = new HashMap<>();
            while (rows.next()) {
                var label = new Label(rows.getLong("id"), rows.getString("code"), rows.getString("title"));
                features.computeIfAbsent(rows.getLong("plan_id"), key -> new ArrayList<>()).add(label);
            }
            return features;
        });
    }

    public record SelectedFeature(long id, String code, String label, String description, String category) {}

    public List<SelectedFeature> findSelectedFeatures(long planId) {
        return jdbc.sql("""
                SELECT catalog.id, catalog.code, catalog.title AS label, catalog.description, catalog.category
                FROM coach_plan_features selected
                JOIN plan_feature_catalog catalog ON catalog.id = selected.feature_id
                WHERE selected.plan_id = :planId
                ORDER BY catalog.display_order, catalog.id
                """).param("planId", planId).query(SelectedFeature.class).list();
    }

    public record SelectedField(Long featureId, Field field) {}

    public List<SelectedField> findSelectedFields(long planId) {
        return jdbc.sql("""
                SELECT field.id, field.feature_id, field.code, field.label, field.help_text,
                       field.value_type, saved.value, field.validation_rules -> 'options' AS options
                FROM coach_plan_field_values saved
                JOIN plan_field_definitions field ON field.id = saved.field_id
                WHERE saved.plan_id = :planId
                ORDER BY field.display_order, field.id
                """).param("planId", planId).query((row, index) -> {
            var value = json.readTree(row.getString("value"));
            var type = ValueType.valueOf(row.getString("value_type"));
            var field = new Field(row.getLong("id"), row.getString("code"), row.getString("label"),
                    row.getString("help_text"), type, value, readSelectedOptions(type, value, row.getString("options")));
            return new SelectedField(row.getObject("feature_id", Long.class), field);
        }).list();
    }

    private List<Option> readSelectedOptions(ValueType type, JsonNode value, String optionsJson) {
        if (type != ValueType.SINGLE_SELECT && type != ValueType.MULTI_SELECT) return List.of();

        Map<String, String> labels = new HashMap<>();
        if (optionsJson != null) {
            for (var option : json.readTree(optionsJson)) {
                var code = option.path("code").stringValue();
                var label = option.path("label").stringValue();
                if (code != null && label != null) labels.putIfAbsent(code, label);
            }
        }

        Iterable<JsonNode> selectedValues = value.isArray() ? value : List.of(value);
        var options = new ArrayList<Option>();
        for (var selected : selectedValues) {
            if (!selected.isString()) continue;
            var code = selected.stringValue();
            options.add(new Option(code, labels.getOrDefault(code, code)));
        }
        return options;
    }

    public void clear(long planId) {
        clearIncludes(planId);
        clearFieldValues(planId);
        clearFeatures(planId);
    }

    public void clearIncludes(long planId) {
        jdbc.sql("DELETE FROM coach_plan_includes WHERE plan_id = :planId").param("planId", planId).update();
    }

    private void clearFieldValues(long planId) {
        jdbc.sql("DELETE FROM coach_plan_field_values WHERE plan_id = :planId").param("planId", planId).update();
    }

    public void clearFieldValues(long planId, List<Long> fieldIds) {
        if (fieldIds.isEmpty()) return;
        jdbc.sql("DELETE FROM coach_plan_field_values WHERE plan_id = :planId AND field_id = ANY(:ids)")
                .param("planId", planId).param("ids", new SqlArrayValue("bigint", fieldIds.toArray())).update();
    }

    private void clearFeatures(long planId) {
        jdbc.sql("DELETE FROM coach_plan_features WHERE plan_id = :planId").param("planId", planId).update();
    }

    public void removeFeatures(long planId, List<Long> featureIds) {
        if (featureIds.isEmpty()) return;
        var ids = featureIds.toArray();
        jdbc.sql("""
                DELETE FROM coach_plan_field_values answer USING plan_field_definitions field
                WHERE answer.plan_id = :planId AND answer.field_id = field.id AND field.feature_id = ANY(:ids)
                """).param("planId", planId).param("ids", new SqlArrayValue("bigint", ids)).update();
        jdbc.sql("DELETE FROM coach_plan_features WHERE plan_id = :planId AND feature_id = ANY(:ids)")
                .param("planId", planId).param("ids", new SqlArrayValue("bigint", ids)).update();
    }

    public void insert(long planId, List<Long> includeIds, List<Long> featureIds, List<PlanFieldValue> fieldValues) {
        if (!includeIds.isEmpty()) {
            jdbc.sql("INSERT INTO coach_plan_includes (plan_id, include_id) SELECT :planId, unnest(:ids)")
                    .param("planId", planId).param("ids", new SqlArrayValue("bigint", includeIds.toArray())).update();
        }
        if (!featureIds.isEmpty()) {
            jdbc.sql("""
                    INSERT INTO coach_plan_features (plan_id, feature_id) SELECT :planId, unnest(:ids)
                    ON CONFLICT (plan_id, feature_id) DO NOTHING
                    """)
                    .param("planId", planId).param("ids", new SqlArrayValue("bigint", featureIds.toArray())).update();
        }
        if (!fieldValues.isEmpty()) {
            jdbc.sql("""
                    INSERT INTO coach_plan_field_values (plan_id, field_id, value)
                    SELECT :planId, answer."fieldId", answer.value
                    FROM jsonb_to_recordset(CAST(:answers AS jsonb)) AS answer("fieldId" bigint, value jsonb)
                    """).param("planId", planId).param("answers", json.writeValueAsString(fieldValues)).update();
        }
    }
}
