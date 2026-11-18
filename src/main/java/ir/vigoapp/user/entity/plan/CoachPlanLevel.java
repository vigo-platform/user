package ir.vigoapp.user.entity.plan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;

@Entity
@Table(name = "coach_plan_levels")
@IdClass(CoachPlanLevel.Key.class)
public class CoachPlanLevel {
    public enum Level { BEGINNER, INTERMEDIATE, ADVANCED, PROFESSIONAL }

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false,
            foreignKey = @ForeignKey(name = "coach_plan_levels_plan_fkey"))
    public CoachPlan plan;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Level level;

    public record Key(Long plan, Level level) implements Serializable {}
}
