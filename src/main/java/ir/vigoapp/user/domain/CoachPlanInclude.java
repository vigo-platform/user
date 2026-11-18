package ir.vigoapp.user.domain;
import jakarta.persistence.*;
import java.util.UUID;
@Entity @Table(name="coach_plan_includes")
@IdClass(CoachPlanInclude.Key.class)
public class CoachPlanInclude {
 @Id @Column(name="plan_id") public UUID planId;
 @Id @Enumerated(EnumType.STRING) @Column(name="include_key",length=30) public PlanInclude include;

 /** Composite key. Named Key so it does not shadow the jakarta.persistence.Id annotation. */
 public static class Key implements java.io.Serializable {
  public UUID planId;
  public PlanInclude include;
  public Key() {}
  public Key(UUID planId,PlanInclude include){this.planId=planId;this.include=include;}
  @Override public boolean equals(Object other){return other instanceof Key key&&java.util.Objects.equals(planId,key.planId)&&include==key.include;}
  @Override public int hashCode(){return java.util.Objects.hash(planId,include);}
 }
}
