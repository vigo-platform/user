package ir.vigoapp.user.entity.plan;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ir.vigoapp.user.domain.plan.PlanInclude;
import jakarta.persistence.*;
@Getter
@Setter
@NoArgsConstructor
@Entity @Table(name="coach_plan_includes")
@IdClass(CoachPlanInclude.Key.class)
public class CoachPlanInclude {
 @Id @Column(name="plan_id") private Long planId;
 @Id @Enumerated(EnumType.STRING) @Column(name="include_key",length=30) private PlanInclude include;

 /** Composite key. Named Key so it does not shadow the jakarta.persistence.Id annotation. */
 @NoArgsConstructor
 @AllArgsConstructor
 public static class Key implements java.io.Serializable {
  private Long planId;
  private PlanInclude include;
  @Override public boolean equals(Object other){return other instanceof Key key&&java.util.Objects.equals(planId,key.planId)&&include==key.include;}
  @Override public int hashCode(){return java.util.Objects.hash(planId,include);}
 }
}
