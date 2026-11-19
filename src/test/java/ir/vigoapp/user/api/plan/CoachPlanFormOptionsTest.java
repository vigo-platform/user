package ir.vigoapp.user.api.plan;

import ir.vigoapp.user.dto.plan.CreatePlanFormOptions;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions.GeneralField;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions.Item;
import ir.vigoapp.user.repository.plan.PlanCatalogRepository;
import ir.vigoapp.user.service.plan.CoachPlanService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoachPlanFormOptionsTest {
    @Test
    void returnsLevelAsARequiredGeneralField() {
        var catalog = mock(PlanCatalogRepository.class);
        var includes = List.of(new Item(1, "WORKOUT", "برنامه تمرینی"));
        var fields = List.of(new GeneralField(2, "PRIMARY_GOALS", "اهداف اصلی", true),
                new GeneralField(4, "PLAN_LEVEL", "سطح برنامه", true));
        var features = List.of(new Item(3, "PROGRESS_TRACKING", "پیگیری پیشرفت"));
        when(catalog.findIncludeOptions()).thenReturn(includes);
        when(catalog.findGeneralFields()).thenReturn(fields);
        when(catalog.findFeatures()).thenReturn(features);

        var controller = new CoachPlanController(new CoachPlanService(catalog));
        var response = controller.getFormOptions();

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(new CreatePlanFormOptions(includes, fields, features));
    }
}
