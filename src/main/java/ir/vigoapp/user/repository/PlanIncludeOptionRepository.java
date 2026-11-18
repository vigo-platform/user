package ir.vigoapp.user.repository;

import ir.vigoapp.user.entity.plan.catalog.PlanIncludeOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PlanIncludeOptionRepository extends JpaRepository<PlanIncludeOption, Long> {
    List<PlanIncludeOption> findByActiveTrueOrderByDisplayOrderAscIdAsc();

    List<PlanIncludeOption> findByCodeIn(Collection<String> codes);
}
