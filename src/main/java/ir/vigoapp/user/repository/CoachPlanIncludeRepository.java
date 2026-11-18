package ir.vigoapp.user.repository;
import ir.vigoapp.user.entity.plan.CoachPlanInclude;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface CoachPlanIncludeRepository extends JpaRepository<CoachPlanInclude,CoachPlanInclude.Key> {
 @Query("select i from CoachPlanInclude i where i.planId=:planId order by i.include")
 List<CoachPlanInclude> findForPlan(Long planId);
 @Modifying(clearAutomatically=true,flushAutomatically=true)
 @Query("delete from CoachPlanInclude i where i.planId=:planId")
 void deleteForPlan(Long planId);
}
