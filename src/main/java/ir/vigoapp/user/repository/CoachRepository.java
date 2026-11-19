package ir.vigoapp.user.repository;
import ir.vigoapp.user.domain.AccountStatus;
import ir.vigoapp.user.domain.CoachStatus;
import ir.vigoapp.user.domain.Role;
import ir.vigoapp.user.entity.CoachProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface CoachRepository extends JpaRepository<CoachProfile,Long>, JpaSpecificationExecutor<CoachProfile> {
 @EntityGraph(attributePaths="user") Optional<CoachProfile> findByUserId(Long id);

 @EntityGraph(attributePaths="user")
 Page<CoachProfile> findAllByStatusAndUser_StatusAndUser_Role(
  CoachStatus status,AccountStatus userStatus,Role role,Pageable pageable);

 /** Public coach identities, without loading a page for the plan marketplace filter. */
 @Query("""
  select c.userId from CoachProfile c
  where c.status=:status and c.user.status=:userStatus and c.user.role=:role
  """)
 List<Long> findApprovedCoachIds(@Param("status") CoachStatus status,
  @Param("userStatus") AccountStatus userStatus,@Param("role") Role role);
}
