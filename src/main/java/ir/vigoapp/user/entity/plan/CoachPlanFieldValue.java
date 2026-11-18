package ir.vigoapp.user.entity.plan;

import ir.vigoapp.user.entity.plan.catalog.PlanFieldDefinition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "coach_plan_field_values")
@IdClass(CoachPlanFieldValue.Key.class)
public class CoachPlanFieldValue {
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false,
            foreignKey = @ForeignKey(name = "coach_plan_field_values_plan_fkey"))
    public CoachPlan plan;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "field_id", nullable = false,
            foreignKey = @ForeignKey(name = "coach_plan_field_values_field_fkey"))
    public PlanFieldDefinition field;

    // An integer, an option code, or a list of option codes.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    public Object value;

    public record Key(Long plan, Long field) implements Serializable {}
}
