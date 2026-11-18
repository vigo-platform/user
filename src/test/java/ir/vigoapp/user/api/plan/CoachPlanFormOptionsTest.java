package ir.vigoapp.user.api.plan;

import ir.vigoapp.user.dto.plan.CreatePlanFormOptions;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions.Item;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions.LevelOption;
import ir.vigoapp.user.repository.plan.PlanCatalogRepository;
import ir.vigoapp.user.service.plan.CoachPlanService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoachPlanFormOptionsTest {
    @Test
    void returnsAllFourListsInTheSuccessEnvelope() {
        var catalog = mock(PlanCatalogRepository.class);
        var includes = List.of(new Item(1, "WORKOUT", "برنامه تمرینی"));
        var fields = List.of(new Item(2, "PRIMARY_GOALS", "اهداف اصلی"));
        var features = List.of(new Item(3, "PROGRESS_TRACKING", "پیگیری پیشرفت"));
        when(catalog.findIncludeOptions()).thenReturn(includes);
        when(catalog.findGeneralFields()).thenReturn(fields);
        when(catalog.findFeatures()).thenReturn(features);
        when(catalog.findLevels()).thenCallRealMethod();

        var controller = new CoachPlanController(new CoachPlanService(catalog));
        var response = controller.getFormOptions();

        assertThat(response.success()).isTrue();
        var levels = List.of(new LevelOption("BEGINNER", "مبتدی"),
                new LevelOption("INTERMEDIATE", "متوسط"), new LevelOption("ADVANCED", "پیشرفته"),
                new LevelOption("PROFESSIONAL", "حرفه‌ای"));
        assertThat(response.data()).isEqualTo(new CreatePlanFormOptions(includes, levels, fields, features));
    }
}
