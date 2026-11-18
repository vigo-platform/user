package ir.vigoapp.user.domain;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface CoachCertificateRepository extends JpaRepository<CoachCertificate,UUID> {
 List<CoachCertificate> findByApplicationIdOrderByPosition(UUID applicationId);
 long countByApplicationId(UUID applicationId);
}
