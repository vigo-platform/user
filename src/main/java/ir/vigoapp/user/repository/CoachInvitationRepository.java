package ir.vigoapp.user.repository;
import ir.vigoapp.user.entity.CoachInvitation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;
public interface CoachInvitationRepository extends JpaRepository<CoachInvitation,UUID> {
 /**
  * Locks the unconsumed invitation for an email so two concurrent registrations
  * for the same address cannot both consume it.
  */
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("""
   select i from CoachInvitation i
   where lower(i.email)=lower(:email) and i.consumedAt is null and i.expiresAt>:now
   """)
 Optional<CoachInvitation> lockActiveByEmail(@Param("email") String email,@Param("now") Instant now);

 Optional<CoachInvitation> findByApplicationId(UUID applicationId);

 boolean existsByApplicationId(UUID applicationId);
}
