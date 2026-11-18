package ir.vigoapp.user.entity.plan;

import ir.vigoapp.user.entity.plan.catalog.PlanFeatureDefinition;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;

@Entity
@Table(name = "coach_plan_features")
@IdClass(CoachPlanFeature.Key.class)
public class CoachPlanFeature {
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false,
            foreignKey = @ForeignKey(name = "coach_plan_features_plan_fkey"))
    public CoachPlan plan;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "feature_id", nullable = false,
            foreignKey = @ForeignKey(name = "coach_plan_features_feature_fkey"))
    public PlanFeatureDefinition feature;

    public record Key(Long plan, Long feature) implements Serializable {}
}
