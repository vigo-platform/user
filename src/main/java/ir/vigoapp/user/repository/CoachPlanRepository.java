package ir.vigoapp.user.repository;
import ir.vigoapp.user.domain.AccountStatus;
import ir.vigoapp.user.domain.CoachStatus;
import ir.vigoapp.user.domain.Role;
import ir.vigoapp.user.entity.plan.CoachPlan;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface CoachPlanRepository extends JpaRepository<CoachPlan,Long> {
 List<CoachPlan> findByCoachIdOrderByCreatedAtDesc(Long coachId);
 Page<CoachPlan> findByCoachIdAndActiveTrueAndArchivedFalse(Long coachId,Pageable pageable);
 Page<CoachPlan> findByCoachIdInAndActiveTrueAndArchivedFalse(Collection<Long> coachIds,Pageable pageable);

 interface PublicPlanSummary {
  long getId();
  String getTitle();
  String getSummary();
  long getPrice();
  Long getCompareAtPrice();
  String getCurrency();
  int getDurationDays();
  boolean isRecommended();
  int getDisplayOrder();
 }

 List<PublicPlanSummary> findByCoachIdAndActiveTrueAndArchivedFalseOrderByDisplayOrderAscIdAsc(Long coachId);

 @Query("""
  select p from CoachPlan p join p.coach c join c.user u
  where p.id=:planId and p.coachId=:coachId and p.active=true and p.archived=false
   and c.status=:coachStatus and u.status=:accountStatus and u.role=:role
  """)
 Optional<CoachPlan> findPublicPlan(@Param("coachId") long coachId,@Param("planId") long planId,
  @Param("coachStatus") CoachStatus coachStatus,@Param("accountStatus") AccountStatus accountStatus,@Param("role") Role role);

 interface ActivePlanPrice {
  Long getCoachId();
  long getPrice();
  Long getCompareAtPrice();
  String getCurrency();
 }

 @Query(value="""
  select distinct on (coach_user_id)
   coach_user_id as "coachId", price, compare_at_price as "compareAtPrice", currency
  from (
   select id, coach_user_id, price, compare_at_price, currency,
    min(id) over (partition by coach_user_id, currency) as first_plan_in_currency
   from coach_plans
   where is_active and not is_archived and coach_user_id in (:coachIds)
  ) as visible_plans
  order by coach_user_id, first_plan_in_currency, price, id
  """, nativeQuery=true)
 List<ActivePlanPrice> findCheapestActivePrices(@Param("coachIds") Collection<Long> coachIds);

 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from CoachPlan p where p.id=:id")
 Optional<CoachPlan> lock(@Param("id") Long id);
}
