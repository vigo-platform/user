package ir.vigoapp.user.domain;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface CoachPlanIncludeRepository extends JpaRepository<CoachPlanInclude,CoachPlanInclude.Key> {
 @Query("select i from CoachPlanInclude i where i.planId=:planId order by i.include")
 List<CoachPlanInclude> findForPlan(UUID planId);
 @Modifying(clearAutomatically=true,flushAutomatically=true)
 @Query("delete from CoachPlanInclude i where i.planId=:planId")
 void deleteForPlan(UUID planId);
}
