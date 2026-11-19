package ir.vigoapp.user.entity.plan;

import ir.vigoapp.user.entity.plan.catalog.PlanIncludeOption;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "coach_plan_includes")
@IdClass(CoachPlanInclude.Key.class)
public class CoachPlanInclude {
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false,
            foreignKey = @ForeignKey(name = "coach_plan_includes_plan_id_fkey"))
    private CoachPlan plan;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "include_id", nullable = false,
            foreignKey = @ForeignKey(name = "coach_plan_includes_option_fkey"))
    private PlanIncludeOption includeOption;

    public record Key(Long plan, Long includeOption) implements Serializable {}
}
