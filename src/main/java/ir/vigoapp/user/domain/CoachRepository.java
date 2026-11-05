package ir.vigoapp.user.domain;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface CoachRepository extends JpaRepository<CoachProfile,Long>, JpaSpecificationExecutor<CoachProfile> {
 @EntityGraph(attributePaths="user") Optional<CoachProfile> findByUser_PublicId(UUID id);

 @EntityGraph(attributePaths="user")
 Page<CoachProfile> findAllByStatusAndUser_StatusAndUser_Role(
  CoachStatus status,AccountStatus userStatus,Role role,Pageable pageable);
}
