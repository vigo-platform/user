package ir.vigoapp.user.domain;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface CoachRepository extends JpaRepository<CoachProfile,Long>, JpaSpecificationExecutor<CoachProfile> {
 @EntityGraph(attributePaths="user") Optional<CoachProfile> findByUser_PublicId(UUID id);

 @EntityGraph(attributePaths="user")
 Page<CoachProfile> findAllByStatusAndUser_StatusAndUser_Role(
  CoachStatus status,AccountStatus userStatus,Role role,Pageable pageable);

 /** Public coach identities, without loading a page for the plan marketplace filter. */
 @Query("""
  select c.user.publicId from CoachProfile c
  where c.status=:status and c.user.status=:userStatus and c.user.role=:role
  """)
 List<UUID> findApprovedCoachIds(@Param("status") CoachStatus status,
  @Param("userStatus") AccountStatus userStatus,@Param("role") Role role);
}
