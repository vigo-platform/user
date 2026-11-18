package ir.vigoapp.user.domain;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface CoachApplicationRepository extends JpaRepository<CoachApplication,UUID> {
 Optional<CoachApplication> findByEmailIgnoreCaseAndStatus(String email,CoachApplicationStatus status);

 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select a from CoachApplication a where a.id=:id")
 Optional<CoachApplication> lock(@Param("id") UUID id);

 Page<CoachApplication> findAllByStatus(CoachApplicationStatus status,Pageable pageable);

 /**
  * Transaction-scoped advisory lock keyed by the normalised email, so concurrent
  * submissions of one address serialise before the pending row is inspected.
  */
 @Query(value="select pg_advisory_xact_lock(hashtextextended(:email, 0))",nativeQuery=true)
 void lockEmailForSubmission(@Param("email") String email);
}
