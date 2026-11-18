package ir.vigoapp.user.repository;
import ir.vigoapp.user.domain.plan.PlanStatus;
import ir.vigoapp.user.entity.plan.CoachPlan;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface CoachPlanRepository extends JpaRepository<CoachPlan,Long> {
 List<CoachPlan> findByCoachIdOrderByCreatedAtDesc(Long coachId);
 Page<CoachPlan> findByCoachIdAndStatus(Long coachId,PlanStatus status,Pageable pageable);
 Page<CoachPlan> findByStatusAndCoachIdIn(PlanStatus status,Collection<Long> coachIds,Pageable pageable);
 Page<CoachPlan> findByStatus(PlanStatus status,Pageable pageable);
 Optional<CoachPlan> findByIdAndCoachId(Long id, Long coachId);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from CoachPlan p where p.id=:id")
 Optional<CoachPlan> lock(@Param("id") Long id);
}
