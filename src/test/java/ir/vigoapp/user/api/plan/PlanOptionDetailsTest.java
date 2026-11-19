package ir.vigoapp.user.api.plan;

import ir.vigoapp.user.dto.plan.CreatePlanFormOptions.GeneralField;
import ir.vigoapp.user.dto.plan.PlanOptionDetails.Feature;
import ir.vigoapp.user.dto.plan.PlanOptionDetails.Field;
import ir.vigoapp.user.entity.plan.catalog.PlanFieldDefinition.ValueType;
import ir.vigoapp.user.repository.UserRepository;
import ir.vigoapp.user.repository.plan.PlanCatalogRepository;
import ir.vigoapp.user.security.SecurityConfig;
import ir.vigoapp.user.service.plan.CoachPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CoachPlanController.class, properties = {
        "app.jwt.jwks-uri=https://auth.example.test/jwks", "app.jwt.issuer=https://auth.example.test",
        "app.jwt.audience=vigo-api"})
@Import({SecurityConfig.class, CoachPlanService.class})
class PlanOptionDetailsTest {
    private static final String FORM_URL = "/api/v1/coaches/me/plan/form-options";
    private static final String FEATURE_URL = FORM_URL + "/features/{id}";
    private static final String FIELD_URL = FORM_URL + "/fields/{id}";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean PlanCatalogRepository catalog;
    @MockitoBean UserRepository users;

    @Test
    void exposesLevelThroughTheGeneralFieldEndpoints() throws Exception {
        var level = new Field(12, "PLAN_LEVEL", "سطح برنامه", null, ValueType.MULTI_SELECT, true,
                json.readTree("""
                        {"options":[{"code":"BEGINNER","label":"مبتدی"}]}
                        """));
        when(catalog.findGeneralFields()).thenReturn(List.of(
                new GeneralField(level.id(), level.code(), level.label(), level.required())));
        when(catalog.findFieldDetails(level.id())).thenReturn(Optional.of(level));
        var coach = user("coach").roles("COACH");

        mvc.perform(get(FORM_URL).with(coach))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.levels").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.general_fields[0].code").value("PLAN_LEVEL"))
                .andExpect(jsonPath("$.data.general_fields[0].required").value(true));
        mvc.perform(get(FIELD_URL, level.id()).with(coach))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.required").value(true))
                .andExpect(jsonPath("$.data.value_type").value("MULTI_SELECT"))
                .andExpect(jsonPath("$.data.validation_rules.options[0].code").value("BEGINNER"));
    }

    @Test
    void resolvesOverlappingIdsAndReturnsAllFeatureFields() throws Exception {
        var field = new Field(1, "REVIEW_INTERVAL_DAYS", "فاصله بازبینی", "بر حسب روز",
                ValueType.INTEGER, false, json.readTree("{\"min\":1,\"max\":90}"));
        var secondField = new Field(2, "REVIEW_COUNT", "تعداد بازبینی", null,
                ValueType.INTEGER, false, json.readTree("{\"min\":1,\"max\":10}"));
        var feature = new Feature(1, "PERIODIC_PLAN_REVIEW", "بازبینی", "بازبینی دوره‌ای",
                "FOLLOW_UP", List.of(field, secondField));
        when(catalog.findFeatureDetails(1)).thenReturn(Optional.of(feature));
        when(catalog.findFieldDetails(1)).thenReturn(Optional.of(field));

        mvc.perform(get(FEATURE_URL, 1).with(user("coach").roles("COACH")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.type").value("FEATURE"))
                .andExpect(jsonPath("$.data.code").value(feature.code()))
                .andExpect(jsonPath("$.data.fields.length()").value(2))
                .andExpect(jsonPath("$.data.fields[0].code").value(field.code()))
                .andExpect(jsonPath("$.data.fields[0].required").value(false))
                .andExpect(jsonPath("$.data.fields[0].validation_rules.max").value(90))
                .andExpect(jsonPath("$.data.fields[1].code").value(secondField.code()))
                .andExpect(jsonPath("$.data.fields[1].validation_rules.max").value(10));
        mvc.perform(get(FIELD_URL, 1).with(user("coach").roles("COACH")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.type").value("FIELD"))
                .andExpect(jsonPath("$.data.code").value(field.code()))
                .andExpect(jsonPath("$.data.fields").doesNotExist())
                .andExpect(jsonPath("$.data.value_type").value("INTEGER"))
                .andExpect(jsonPath("$.data.required").value(false))
                .andExpect(jsonPath("$.data.validation_rules.min").value(1));
    }

    @Test
    void returnsEmptyFieldsForFeatureWithoutFields() throws Exception {
        var feature = new Feature(1, "EXTRA_SUPPORT", "پشتیبانی", null, "FOLLOW_UP", List.of());
        when(catalog.findFeatureDetails(1)).thenReturn(Optional.of(feature));

        mvc.perform(get(FEATURE_URL, 1).with(user("coach").roles("COACH")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.fields.length()").value(0));
    }

    @Test
    void rejectsUnknownItemsAndMalformedInputs() throws Exception {
        var coach = user("coach").roles("COACH");
        for (String url : List.of(FEATURE_URL, FIELD_URL)) {
            mvc.perform(get(url, 99).with(coach)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            mvc.perform(get(url, "invalid").with(coach)).andExpect(status().isBadRequest());
            mvc.perform(get(url, 0).with(coach)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void requiresCoachRole() throws Exception {
        for (String url : List.of(FEATURE_URL, FIELD_URL)) {
            mvc.perform(get(url, 1)).andExpect(status().isUnauthorized());
            mvc.perform(get(url, 1).with(user("athlete").roles("ATHLETE")))
                    .andExpect(status().isForbidden());
        }
    }
}
