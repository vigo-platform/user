package ir.vigoapp.user.repository.plan;

import ir.vigoapp.user.dto.plan.PlanFieldValue;
import ir.vigoapp.user.dto.plan.PublicCoachPlan.Field;
import ir.vigoapp.user.dto.plan.PublicCoachPlan.Label;
import ir.vigoapp.user.dto.plan.PublicCoachPlan.Option;
import ir.vigoapp.user.entity.plan.catalog.PlanFieldDefinition.ValueType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
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

    public List<Long> findFeatureIds(long planId) {
        return jdbc.sql("""
                SELECT feature_id FROM coach_plan_features WHERE plan_id = :planId ORDER BY feature_id
                """).param("planId", planId).query(Long.class).list();
    }

    public List<PlanFieldValue> findFieldValues(long planId) {
        return jdbc.sql("""
                SELECT field_id, value FROM coach_plan_field_values WHERE plan_id = :planId ORDER BY field_id
                """).param("planId", planId)
                .query((row, index) -> new PlanFieldValue(row.getLong("field_id"), json.readTree(row.getString("value"))))
                .list();
    }

    public Map<Long, List<Label>> findPreviewFeatures(long coachId) {
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
                WHERE plan.coach_user_id = :coachId AND plan.is_active AND NOT plan.is_archived
                ORDER BY plan.id, feature.display_order, feature.id
                """).param("coachId", coachId).query(rows -> {
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
            var field = new Field(row.getLong("id"), row.getString("code"), row.getString("label"),
                    row.getString("help_text"), ValueType.valueOf(row.getString("value_type")), value,
                    readSelectedOptions(value, row.getString("options")));
            return new SelectedField(row.getObject("feature_id", Long.class), field);
        }).list();
    }

    private List<Option> readSelectedOptions(JsonNode value, String optionsJson) {
        if (!value.isString() && !value.isArray()) return List.of();

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

    public void replace(long planId, List<Long> featureIds, List<PlanFieldValue> fieldValues) {
        jdbc.sql("DELETE FROM coach_plan_field_values WHERE plan_id = :planId").param("planId", planId).update();
        jdbc.sql("DELETE FROM coach_plan_features WHERE plan_id = :planId").param("planId", planId).update();

        for (long featureId : featureIds) {
            jdbc.sql("INSERT INTO coach_plan_features (plan_id, feature_id) VALUES (:planId, :featureId)")
                    .param("planId", planId).param("featureId", featureId).update();
        }
        for (var field : fieldValues) {
            jdbc.sql("""
                    INSERT INTO coach_plan_field_values (plan_id, field_id, value)
                    VALUES (:planId, :fieldId, CAST(:value AS jsonb))
                    """)
                    .param("planId", planId).param("fieldId", field.fieldId())
                    .param("value", json.writeValueAsString(field.value())).update();
        }
    }
}
