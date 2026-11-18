package ir.vigoapp.user.domain;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface CoachPlanRepository extends JpaRepository<CoachPlan,UUID> {
 List<CoachPlan> findByCoachIdOrderByCreatedAtDesc(UUID coachId);
 Page<CoachPlan> findByCoachIdAndStatus(UUID coachId,PlanStatus status,Pageable pageable);
 Page<CoachPlan> findByStatusAndCoachIdIn(PlanStatus status,Collection<UUID> coachIds,Pageable pageable);
 Page<CoachPlan> findByStatus(PlanStatus status,Pageable pageable);
 Optional<CoachPlan> findByIdAndCoachId(UUID id,UUID coachId);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from CoachPlan p where p.id=:id")
 Optional<CoachPlan> lock(@Param("id") UUID id);
}
