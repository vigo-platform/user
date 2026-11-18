package ir.vigoapp.user.domain;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface CoachApplicationTokenRepository extends JpaRepository<CoachApplicationToken,String> {
 Optional<CoachApplicationToken> findByTokenHashAndExpiresAtAfter(String tokenHash,java.time.Instant now);
}
