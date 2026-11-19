package ir.vigoapp.user.repository.plan;

import ir.vigoapp.user.dto.plan.CreatePlanFormOptions.GeneralField;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions.Item;
import ir.vigoapp.user.dto.plan.PlanOptionDetails.Feature;
import ir.vigoapp.user.dto.plan.PlanOptionDetails.Field;
import ir.vigoapp.user.entity.plan.catalog.PlanFieldDefinition.ValueType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PlanCatalogRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public List<Item> findIncludeOptions() {
        return jdbc.sql("""
                SELECT id, code, label
                FROM plan_include_catalog
                WHERE is_active
                ORDER BY display_order, id
                """).query(Item.class).list();
    }

    public List<Item> findFeatures() {
        return jdbc.sql("""
                SELECT id, code, title AS label
                FROM plan_feature_catalog
                WHERE is_active
                ORDER BY display_order, id
                """).query(Item.class).list();
    }

    public List<GeneralField> findGeneralFields() {
        return jdbc.sql("""
                SELECT id, code, label, is_required AS required
                FROM plan_field_definitions
                WHERE is_active AND feature_id IS NULL
                ORDER BY display_order, id
                """).query(GeneralField.class).list();
    }

    public record FieldDefinition(Field field, Long featureId) {}

    public List<FieldDefinition> findActiveFieldDefinitions() {
        return jdbc.sql("""
                SELECT field.id, field.feature_id, field.code, field.label, field.help_text,
                       field.value_type, field.is_required, field.validation_rules
                FROM plan_field_definitions field
                LEFT JOIN plan_feature_catalog feature ON feature.id = field.feature_id
                WHERE field.is_active AND (field.feature_id IS NULL OR feature.is_active)
                ORDER BY field.display_order, field.id
                """)
                .query((row, index) -> new FieldDefinition(readField(row), row.getObject("feature_id", Long.class)))
                .list();
    }

    public Optional<Field> findFieldDetails(long id) {
        return jdbc.sql("""
                SELECT field.id, field.code, field.label, field.help_text, field.value_type,
                       field.is_required, field.validation_rules
                FROM plan_field_definitions field
                LEFT JOIN plan_feature_catalog feature ON feature.id = field.feature_id
                WHERE field.id = :id AND field.is_active
                  AND (field.feature_id IS NULL OR feature.is_active)
                """)
                .param("id", id)
                .query((row, index) -> readField(row))
                .optional();
    }

    public Optional<Feature> findFeatureDetails(long id) {
        return jdbc.sql("""
                SELECT feature.id AS feature_id, feature.code AS feature_code, feature.title,
                       feature.description, feature.category,
                       field.id, field.code, field.label, field.help_text, field.value_type,
                       field.is_required, field.validation_rules
                FROM plan_feature_catalog feature
                LEFT JOIN plan_field_definitions field ON field.feature_id = feature.id AND field.is_active
                WHERE feature.id = :id AND feature.is_active
                ORDER BY field.display_order, field.id
                """)
                .param("id", id)
                .query(rows -> {
                    if (!rows.next()) return Optional.empty();
                    var fields = new ArrayList<Field>();
                    var feature = new Feature(rows.getLong("feature_id"), rows.getString("feature_code"),
                            rows.getString("title"), rows.getString("description"), rows.getString("category"), fields);
                    do {
                        if (rows.getObject("id") != null) fields.add(readField(rows));
                    } while (rows.next());
                    return Optional.of(feature);
                });
    }

    private Field readField(ResultSet row) throws SQLException {
        return new Field(row.getLong("id"), row.getString("code"), row.getString("label"),
                row.getString("help_text"), ValueType.valueOf(row.getString("value_type")),
                row.getBoolean("is_required"), json.readTree(row.getString("validation_rules")));
    }
}
