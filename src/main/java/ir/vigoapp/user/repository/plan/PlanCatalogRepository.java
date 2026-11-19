package ir.vigoapp.user.repository.plan;

import ir.vigoapp.user.dto.plan.CreatePlanFormOptions.Item;
import ir.vigoapp.user.dto.plan.PlanOptionDetails.Feature;
import ir.vigoapp.user.dto.plan.PlanOptionDetails.Field;
import ir.vigoapp.user.entity.plan.catalog.PlanFieldDefinition.ValueType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
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

    public List<Field> findGeneralFields() {
        return jdbc.sql("""
                SELECT id, code, label, help_text, value_type, is_required, validation_rules
                FROM plan_field_definitions
                WHERE is_active AND feature_id IS NULL
                ORDER BY display_order, id
                """).query((row, index) -> readField(row)).list();
    }

    public record IncludeStatus(long id, String code, boolean active) {}

    public List<IncludeStatus> findIncludeStatuses(Collection<String> codes) {
        if (codes.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT id, code, is_active AS active FROM plan_include_catalog
                WHERE code = ANY(:codes) ORDER BY display_order, id
                """).param("codes", new SqlArrayValue("varchar", codes.toArray())).query(IncludeStatus.class).list();
    }

    public record FeatureStatus(long id, boolean active) {}

    public List<FeatureStatus> findFeatureStatuses(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT id, is_active AS active FROM plan_feature_catalog WHERE id = ANY(:ids)")
                .param("ids", new SqlArrayValue("bigint", ids.toArray())).query(FeatureStatus.class).list();
    }

    public record FieldDefinition(Field field, Long featureId, boolean active) {}

    public List<FieldDefinition> findSubmissionFields(Collection<Long> ids, boolean includeRequired) {
        if (ids.isEmpty() && !includeRequired) return List.of();
        return jdbc.sql("""
                SELECT field.id, field.feature_id, field.code, field.label, field.help_text,
                       field.value_type, field.is_required, field.validation_rules,
                       (field.is_active AND (field.feature_id IS NULL OR feature.is_active)) AS active
                FROM plan_field_definitions field
                LEFT JOIN plan_feature_catalog feature ON feature.id = field.feature_id
                WHERE (:includeRequired AND field.feature_id IS NULL AND field.is_active AND field.is_required)
                   OR field.id = ANY(:ids)
                ORDER BY field.display_order, field.id
                """).param("includeRequired", includeRequired).param("ids", new SqlArrayValue("bigint", ids.toArray()))
                .query((row, index) -> new FieldDefinition(readField(row), row.getObject("feature_id", Long.class),
                        row.getBoolean("active")))
                .list();
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
