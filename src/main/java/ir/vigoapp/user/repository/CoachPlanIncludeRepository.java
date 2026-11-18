package ir.vigoapp.user.repository;

import ir.vigoapp.user.entity.plan.CoachPlanInclude;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CoachPlanIncludeRepository extends JpaRepository<CoachPlanInclude, CoachPlanInclude.Key> {
    @Query("""
            select opt.code from CoachPlanInclude selected join selected.includeOption opt
            where selected.plan.id = :planId order by opt.displayOrder, opt.id
            """)
    List<String> findCodesForPlan(Long planId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CoachPlanInclude selected where selected.plan.id = :planId")
    void deleteForPlan(Long planId);
}
