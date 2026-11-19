package ir.vigoapp.user.repository;

import ir.vigoapp.user.entity.plan.CoachPlanInclude;
import ir.vigoapp.user.entity.plan.catalog.PlanIncludeOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface CoachPlanIncludeRepository extends JpaRepository<CoachPlanInclude, CoachPlanInclude.Key> {
    @Query("select opt from PlanIncludeOption opt where opt.code in :codes")
    List<PlanIncludeOption> findOptionsByCodeIn(Collection<String> codes);

    @Query("""
            select opt.code from CoachPlanInclude selected join selected.includeOption opt
            where selected.plan.id = :planId order by opt.displayOrder, opt.id
            """)
    List<String> findCodesForPlan(Long planId);

    @Query("""
            select opt from CoachPlanInclude selected join selected.includeOption opt
            where selected.plan.id = :planId order by opt.displayOrder, opt.id
            """)
    List<PlanIncludeOption> findOptionsForPlan(Long planId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CoachPlanInclude selected where selected.plan.id = :planId")
    void deleteForPlan(Long planId);
}
