package ir.vigoapp.user.api.plan;

import ir.vigoapp.user.dto.plan.CreatePlanFormOptions;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions.FeatureOption;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions.Item;
import ir.vigoapp.user.dto.plan.PlanOptionDetails.Field;
import ir.vigoapp.user.entity.plan.catalog.PlanFieldDefinition.ValueType;
import ir.vigoapp.user.repository.plan.PlanCatalogRepository;
import ir.vigoapp.user.repository.plan.PlanCatalogRepository.FieldDefinition;
import ir.vigoapp.user.service.plan.CoachPlanService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoachPlanFormOptionsTest {
    @Test
    void groupsFieldsUnderTheRightOptions() {
        var catalog = mock(PlanCatalogRepository.class);
        var json = JsonMapper.builder().build();
        var includes = List.of(new Item(1, "WORKOUT", "برنامه تمرینی"));
        var level = new Field(4, "PLAN_LEVEL", "سطح برنامه", null, ValueType.MULTI_SELECT, true,
                json.readTree("{\"options\":[{\"code\":\"BEGINNER\",\"label\":\"مبتدی\"}]}"));
        var review = new Field(5, "REVIEW_COUNT", "تعداد بازبینی", null, ValueType.INTEGER, false,
                json.readTree("{\"min\":1,\"max\":10}"));
        var features = List.of(new Item(3, "PROGRESS_TRACKING", "پیگیری پیشرفت"),
                new Item(6, "EXTRA_SUPPORT", "پشتیبانی"));
        when(catalog.findIncludeOptions()).thenReturn(includes);
        when(catalog.findActiveFieldDefinitions()).thenReturn(List.of(
                new FieldDefinition(level, null), new FieldDefinition(review, 3L)));
        when(catalog.findFeatures()).thenReturn(features);

        var controller = new CoachPlanController(new CoachPlanService(catalog));
        var response = controller.getFormOptions();

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(new CreatePlanFormOptions(includes, List.of(level), List.of(
                new FeatureOption(3, "PROGRESS_TRACKING", "پیگیری پیشرفت", List.of(review)),
                new FeatureOption(6, "EXTRA_SUPPORT", "پشتیبانی", List.of()))));
    }
}
