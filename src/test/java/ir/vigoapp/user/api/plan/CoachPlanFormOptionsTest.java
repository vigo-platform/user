package ir.vigoapp.user.api.plan;

import ir.vigoapp.user.dto.plan.CreatePlanFormOptions;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions.Item;
import ir.vigoapp.user.dto.plan.PlanOptionDetails.Field;
import ir.vigoapp.user.entity.plan.catalog.PlanFieldDefinition.ValueType;
import ir.vigoapp.user.repository.plan.PlanCatalogRepository;
import ir.vigoapp.user.service.plan.CoachPlanService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoachPlanFormOptionsTest {
    @Test
    void returnsFullGeneralFieldsAndFeatureSummaries() {
        var catalog = mock(PlanCatalogRepository.class);
        var json = JsonMapper.builder().build();
        var includes = List.of(new Item(1, "WORKOUT", "برنامه تمرینی"));
        var level = new Field(4, "PLAN_LEVEL", "سطح برنامه", null, ValueType.MULTI_SELECT, true,
                json.readTree("{\"options\":[{\"code\":\"BEGINNER\",\"label\":\"مبتدی\"}]}"));
        var features = List.of(new Item(3, "PROGRESS_TRACKING", "پیگیری پیشرفت"),
                new Item(6, "EXTRA_SUPPORT", "پشتیبانی"));
        when(catalog.findIncludeOptions()).thenReturn(includes);
        when(catalog.findGeneralFields()).thenReturn(List.of(level));
        when(catalog.findFeatures()).thenReturn(features);

        var controller = new CoachPlanController(new CoachPlanService(catalog));
        var response = controller.getFormOptions();

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(new CreatePlanFormOptions(includes, List.of(level), features));
    }
}
