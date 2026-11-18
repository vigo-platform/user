package ir.vigoapp.user.repository;
import ir.vigoapp.user.domain.plan.subscription.SubscriptionStatus;
import ir.vigoapp.user.entity.plan.subscription.PlanSubscription;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;
public interface PlanSubscriptionRepository extends JpaRepository<PlanSubscription,Long> {
 Optional<PlanSubscription> findByOrderId(Long orderId);
 List<PlanSubscription> findByAthleteIdOrderByCreatedAtDesc(Long athleteId);

 /**
  * Atomically activates a subscription exactly once. A repeated payment event, or
  * one arriving after cancellation, updates no row.
  */
 @Modifying(clearAutomatically=true,flushAutomatically=true)
 @Query("""
  update PlanSubscription s set s.status=:active,s.startsAt=:starts,s.endsAt=:ends,s.updatedAt=:now
  where s.id=:id and s.status=:pending
  """)
 int activate(@Param("id") Long id,@Param("active") SubscriptionStatus active,@Param("pending") SubscriptionStatus pending,
   @Param("starts") Instant starts,@Param("ends") Instant ends,@Param("now") Instant now);
}
