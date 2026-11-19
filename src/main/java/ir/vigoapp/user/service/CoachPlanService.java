package ir.vigoapp.user.service;

import lombok.RequiredArgsConstructor;
import ir.vigoapp.user.api.*;
import ir.vigoapp.user.domain.AccountStatus;
import ir.vigoapp.user.domain.CoachStatus;
import ir.vigoapp.user.domain.Role;
import ir.vigoapp.user.domain.plan.PlanStatus;
import ir.vigoapp.user.dto.Requests;
import ir.vigoapp.user.dto.Views;
import ir.vigoapp.user.dto.plan.CreatePlanFormOptions.Item;
import ir.vigoapp.user.dto.plan.PlanOptionDetails.Field;
import ir.vigoapp.user.entity.CoachProfile;
import ir.vigoapp.user.entity.plan.CoachPlan;
import ir.vigoapp.user.entity.plan.CoachPlanInclude;
import ir.vigoapp.user.entity.plan.catalog.PlanIncludeOption;
import ir.vigoapp.user.repository.CoachPlanIncludeRepository;
import ir.vigoapp.user.repository.CoachPlanRepository;
import ir.vigoapp.user.repository.CoachRepository;
import ir.vigoapp.user.repository.plan.CoachPlanSelectionRepository;
import ir.vigoapp.user.repository.plan.PlanCatalogRepository;
import ir.vigoapp.user.repository.plan.PlanCatalogRepository.FieldDefinition;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Coach plan management.
 *
 * <p>Ownership is always taken from the authenticated actor, never from the
 * request body, so a coach cannot create or edit a plan that belongs to someone
 * else. Customers can only read plans; every write path is coach-only.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class CoachPlanService {
    private final CoachPlanRepository plans;
    private final CoachPlanIncludeRepository includes;
    private final CoachRepository coaches;
    private final PlanCatalogRepository catalog;
    private final CoachPlanSelectionRepository selections;

    /** Creates a plan for the authenticated coach. The coach must be approved. */
    public Map<String, Object> create(long coachId, Requests.Plan request) {
        var coach = requireApprovedCoach(coachId);
        validate(request);
        validateSelections(request);
        var selectedOptions = resolveIncludes(request.includes(), Set.of());
        var plan = new CoachPlan(coach);
        apply(plan, request);
        plan.setStatus(PlanStatus.ACTIVE);
        plans.saveAndFlush(plan);
        replaceIncludes(plan, selectedOptions);
        selections.replace(plan.getId(), request.featureIds(), request.fieldValues());
        return view(plan);
    }

    public Map<String, Object> update(long coachId, Long planId, Requests.Plan request) {
        validate(request);
        var plan = ownedPlan(coachId, planId, true);
        if (plan.getStatus() == PlanStatus.ARCHIVED) throw ApiException.conflict("PLAN_ARCHIVED");
        validateSelections(request);
        var alreadySelected = new HashSet<>(includes.findCodesForPlan(planId));
        var selectedOptions = resolveIncludes(request.includes(), alreadySelected);
        apply(plan, request);
        plan.setUpdatedAt(Instant.now());
        plans.flush();
        replaceIncludes(plan, selectedOptions);
        selections.replace(planId, request.featureIds(), request.fieldValues());
        return view(plan);
    }

    /** Soft delete: the plan leaves the marketplace, running subscriptions are untouched. */
    public void archive(long coachId, Long planId) {
        var plan = ownedPlan(coachId, planId, true);
        if (plan.getStatus() == PlanStatus.ARCHIVED) throw ApiException.conflict("INVALID_STATE");
        plan.setStatus(PlanStatus.ARCHIVED);
        plan.setUpdatedAt(Instant.now());
        plans.flush();
    }

    public void activate(long coachId, Long planId) {
        var plan = ownedPlan(coachId, planId, true);
        if (plan.getStatus() == PlanStatus.ACTIVE) throw ApiException.conflict("INVALID_STATE");
        plan.setStatus(PlanStatus.ACTIVE);
        plan.setUpdatedAt(Instant.now());
        plans.flush();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> own(long coachId) {
        return coaches.findByUserId(coachId)
                .map(coach -> plans.findByCoachIdOrderByCreatedAtDesc(coach.getUserId()).stream().map(this::view).toList())
                .orElseGet(List::of);
    }

    /** Public catalogue: only active plans of approved, active coaches. */
    @Transactional(readOnly = true)
    public Views.Paged<Map<String, Object>> marketplace(int page, int size, Long coachId) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<CoachPlan> result;
        if (coachId != null) {
            if (!isPublicCoach(coachId)) return Views.page(Page.empty(pageable), this::view);
            result = plans.findByCoachIdAndStatus(coachId, PlanStatus.ACTIVE, pageable);
        } else {
            var approved = coaches.findApprovedCoachIds(CoachStatus.APPROVED, AccountStatus.ACTIVE, Role.COACH);
            if (approved.isEmpty()) return Views.page(Page.empty(pageable), this::view);
            result = plans.findByStatusAndCoachIdIn(PlanStatus.ACTIVE, approved, pageable);
        }
        return Views.page(result, this::view);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long planId) {
        var plan = plans.findById(planId).orElseThrow(ApiException::notFound);
        boolean visible = plan.getStatus() == PlanStatus.ACTIVE && isPublicCoach(plan.getCoachId());
        if (!visible) throw ApiException.notFound();
        return view(plan);
    }

    @Transactional(readOnly = true)
    public CoachPlan requirePurchasable(Long planId) {
        var plan = plans.findById(planId).orElseThrow(ApiException::notFound);
        if (plan.getStatus() != PlanStatus.ACTIVE) throw ApiException.conflict("PLAN_ARCHIVED");
        return plan;
    }

    private boolean isPublicCoach(Long coachId) {
        return coaches.findByUserId(coachId)
                .filter(c -> c.getStatus() == CoachStatus.APPROVED)
                .filter(c -> c.getUser().getStatus() == AccountStatus.ACTIVE && c.getUser().getRole() == Role.COACH)
                .isPresent();
    }

    private CoachProfile requireApprovedCoach(long coachId) {
        var coach = coaches.findByUserId(coachId).orElseThrow(ApiException::forbidden);
        if (coach.getStatus() != CoachStatus.APPROVED || coach.getUser().getStatus() != AccountStatus.ACTIVE || coach.getUser().getRole() != Role.COACH)
            throw ApiException.forbidden();
        return coach;
    }

    /** 404 when the plan is unknown, 403 when it exists but belongs to another coach. */
    private CoachPlan ownedPlan(long coachId, Long planId, boolean lock) {
        var plan = (lock ? plans.lock(planId) : plans.findById(planId)).orElseThrow(ApiException::notFound);
        boolean owned = coaches.findByUserId(coachId)
                .filter(coach -> plan.getCoachId().equals(coach.getUserId()))
                .isPresent();
        if (!owned) throw ApiException.forbidden();
        return plan;
    }

    private void apply(CoachPlan plan, Requests.Plan r) {
        plan.setTitle(r.title().strip());
        plan.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().strip());
        plan.setPriceRial(r.price());
        plan.setDurationDays(r.durationDays());
        plan.setUpdatedAt(Instant.now());
    }

    private List<PlanIncludeOption> resolveIncludes(List<String> codes, Set<String> alreadySelected) {
        var uniqueCodes = new HashSet<>(codes);
        var options = includes.findOptionsByCodeIn(uniqueCodes);
        if (options.size() != uniqueCodes.size()) {
            throw new ApiException(400, "VALIDATION_FAILED",
                    "An included service is unknown", "یکی از خدمات انتخاب‌شده ناشناخته است");
        }
        if (options.stream().anyMatch(option -> !option.isActive() && !alreadySelected.contains(option.getCode()))) {
            throw new ApiException(400, "VALIDATION_FAILED",
                    "An included service is inactive", "یکی از خدمات انتخاب‌شده غیرفعال است");
        }
        return options;
    }

    private void replaceIncludes(CoachPlan plan, List<PlanIncludeOption> options) {
        includes.deleteForPlan(plan.getId());
        var selections = options.stream().map(option -> new CoachPlanInclude(plan, option)).toList();
        includes.saveAllAndFlush(selections);
    }

    private static void validate(Requests.Plan r) {
        if (r.includes().isEmpty()) throw new ApiException(400, "VALIDATION_FAILED",
                "At least one included service is required", "حداقل یک خدمت باید انتخاب شود");
    }

    private void validateSelections(Requests.Plan request) {
        var selectedFeatures = new HashSet<>(request.featureIds());
        if (!selectedFeatures.isEmpty()) {
            var activeFeatures = catalog.findFeatures().stream().map(Item::id).collect(Collectors.toSet());
            if (selectedFeatures.size() != request.featureIds().size() || !activeFeatures.containsAll(selectedFeatures)) {
                throw invalidSelection("Features must be active and selected once", "فیچرها باید فعال باشند و هرکدام یک بار انتخاب شوند");
            }
        }

        var definitions = catalog.findActiveFieldDefinitions();
        var fieldsById = definitions.stream()
                .collect(Collectors.toMap(definition -> definition.field().id(), Function.identity()));
        var submitted = new HashSet<Long>();
        for (var selection : request.fieldValues()) {
            var definition = fieldsById.get(selection.fieldId());
            if (definition == null) {
                throw invalidSelection("Unknown or inactive field: " + selection.fieldId(),
                        "فیلد ناشناخته یا غیرفعال است: " + selection.fieldId());
            }
            var field = definition.field();
            if (definition.featureId() != null && !selectedFeatures.contains(definition.featureId())) {
                throw invalidSelection("Select the feature before setting " + field.code(),
                        "برای تکمیل فیلد «" + field.label() + "» ابتدا فیچر آن را انتخاب کنید");
            }
            if (!submitted.add(field.id()) || !isValidValue(field, selection.value())) {
                throw invalidSelection("Invalid or duplicate value for " + field.code(),
                        "مقدار فیلد «" + field.label() + "» نامعتبر یا تکراری است");
            }
        }

        definitions.stream().map(FieldDefinition::field).filter(Field::required)
                .filter(field -> !submitted.contains(field.id())).findFirst().ifPresent(field -> {
                    throw invalidSelection("Missing required field: " + field.code(),
                            "تکمیل فیلد «" + field.label() + "» اجباری است");
                });
    }

    private static boolean isValidValue(Field field, JsonNode value) {
        if (value == null) return false;
        var rules = field.validationRules();
        return switch (field.valueType()) {
            case INTEGER -> value.isIntegralNumber() && value.canConvertToInt()
                    && value.intValue() >= rules.path("min").asInt(Integer.MIN_VALUE)
                    && value.intValue() <= rules.path("max").asInt(Integer.MAX_VALUE);
            case SINGLE_SELECT -> isAllowedOption(value, rules.path("options"));
            case MULTI_SELECT -> {
                if (!value.isArray() || value.isEmpty()) yield false;
                var seen = new HashSet<JsonNode>();
                for (var option : value) {
                    if (!isAllowedOption(option, rules.path("options")) || !seen.add(option)) yield false;
                }
                yield true;
            }
        };
    }

    private static boolean isAllowedOption(JsonNode value, JsonNode options) {
        if (!value.isString()) return false;
        for (var option : options) {
            if (value.equals(option.path("code"))) return true;
        }
        return false;
    }

    private static ApiException invalidSelection(String message, String persian) {
        return new ApiException(400, "VALIDATION_FAILED", message, persian);
    }

    private Map<String, Object> view(CoachPlan plan) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("planId", plan.getId());
        v.put("coachId", plan.getCoachId());
        v.put("title", plan.getTitle());
        v.put("description", plan.getDescription());
        v.put("price", plan.getPriceRial());
        v.put("currency", plan.getCurrency());
        v.put("durationDays", plan.getDurationDays());
        v.put("includes", includes.findCodesForPlan(plan.getId()));
        v.put("featureIds", selections.findFeatureIds(plan.getId()));
        v.put("fieldValues", selections.findFieldValues(plan.getId()));
        v.put("status", plan.getStatus());
        v.put("createdAt", plan.getCreatedAt());
        v.put("updatedAt", plan.getUpdatedAt());
        return v;
    }
}
