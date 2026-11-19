package ir.vigoapp.user.repository.plan;

import ir.vigoapp.user.dto.plan.PlanFieldValue;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

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
