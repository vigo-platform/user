package ir.vigoapp.user.domain;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;
public interface PlanSubscriptionRepository extends JpaRepository<PlanSubscription,UUID> {
 Optional<PlanSubscription> findByOrderId(UUID orderId);
 List<PlanSubscription> findByAthleteIdOrderByCreatedAtDesc(UUID athleteId);

 /**
  * Atomically activates a subscription exactly once. A repeated payment event, or
  * one arriving after cancellation, updates no row.
  */
 @Modifying(clearAutomatically=true,flushAutomatically=true)
 @Query("""
  update PlanSubscription s set s.status=:active,s.startsAt=:starts,s.endsAt=:ends,s.updatedAt=:now
  where s.id=:id and s.status=:pending
  """)
 int activate(@Param("id") UUID id,@Param("active") SubscriptionStatus active,@Param("pending") SubscriptionStatus pending,
   @Param("starts") Instant starts,@Param("ends") Instant ends,@Param("now") Instant now);
}
