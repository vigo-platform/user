package ir.vigoapp.user.repository.plan;

import ir.vigoapp.user.dto.plan.CreatePlanFormOptions.Item;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions.LevelOption;
import ir.vigoapp.user.entity.plan.CoachPlanLevel.Level;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class PlanCatalogRepository {
    private final JdbcClient jdbc;

    public List<LevelOption> findLevels() {
        return Arrays.stream(Level.values())
                .map(level -> new LevelOption(level.name(), level.getLabel()))
                .toList();
    }

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

    public List<Item> findGeneralFields() {
        return jdbc.sql("""
                SELECT id, code, label
                FROM plan_field_definitions
                WHERE is_active AND feature_id IS NULL
                ORDER BY display_order, id
                """).query(Item.class).list();
    }

    public List<Item> findFieldsByFeatureId(long featureId) {
        return jdbc.sql("""
                SELECT field.id, field.code, field.label
                FROM plan_field_definitions field
                JOIN plan_feature_catalog feature ON feature.id = field.feature_id
                WHERE feature.id = :featureId AND feature.is_active AND field.is_active
                ORDER BY field.display_order, field.id
                """)
                .param("featureId", featureId)
                .query(Item.class)
                .list();
    }
}
