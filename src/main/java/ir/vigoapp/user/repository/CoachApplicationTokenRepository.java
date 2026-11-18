package ir.vigoapp.user.repository;
import ir.vigoapp.user.entity.CoachApplicationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface CoachApplicationTokenRepository extends JpaRepository<CoachApplicationToken,String> {
 Optional<CoachApplicationToken> findByTokenHashAndExpiresAtAfter(String tokenHash,java.time.Instant now);
}
