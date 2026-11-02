package ir.vigoapp.user.domain;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface UserRepository extends JpaRepository<UserAccount,Long>, JpaSpecificationExecutor<UserAccount> {
 Optional<UserAccount> findByPublicId(UUID id);
 Optional<UserAccount> findByEmail(String email);
 Optional<UserAccount> findByPhone(String phone);
 boolean existsByEmail(String email);
 boolean existsByPhone(String phone);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select u from UserAccount u where u.publicId=:id") Optional<UserAccount> lock(@Param("id") UUID id);
 @Modifying(clearAutomatically=true,flushAutomatically=true)
 @Query("update UserAccount u set u.passwordHash=:hash,u.credentialVersion=u.credentialVersion+1,u.credentialsChangedAt=:now,u.updatedAt=:now,u.version=u.version+1 where u.id=:id and u.credentialVersion=:expected")
 int changePassword(@Param("id") long id,@Param("expected") long expected,@Param("hash") String hash,@Param("now") java.time.Instant now);
}
