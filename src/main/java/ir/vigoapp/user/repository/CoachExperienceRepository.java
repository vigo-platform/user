package ir.vigoapp.user.repository;
import ir.vigoapp.user.entity.CoachExperience;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface CoachExperienceRepository extends JpaRepository<CoachExperience,UUID> {
 List<CoachExperience> findByApplicationIdOrderByPosition(UUID applicationId);
 long countByApplicationId(UUID applicationId);
}
