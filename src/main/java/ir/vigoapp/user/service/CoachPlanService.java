package ir.vigoapp.user.service;

import ir.vigoapp.user.api.*;
import ir.vigoapp.user.domain.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Coach plan management.
 *
 * <p>Ownership is always taken from the authenticated actor, never from the
 * request body, so a coach cannot create or edit a plan that belongs to someone
 * else. Customers can only read plans; every write path is coach-only.
 */
@Service
@Transactional
public class CoachPlanService {
    private final CoachPlanRepository plans;
    private final CoachPlanIncludeRepository includes;
    private final CoachRepository coaches;

    public CoachPlanService(CoachPlanRepository plans, CoachPlanIncludeRepository includes, CoachRepository coaches) {
        this.plans = plans; this.includes = includes; this.coaches = coaches;
    }

    /** Creates a plan for the authenticated coach. The coach must be approved. */
    public Map<String, Object> create(UUID coachId, Requests.Plan request) {
        requireApprovedCoach(coachId);
        validate(request);
        var plan = new CoachPlan();
        plan.coachId = coachId;
        apply(plan, request);
        plan.status = PlanStatus.ACTIVE;
        plans.saveAndFlush(plan);
        replaceIncludes(plan.id, request.includes());
        return view(plan);
    }

    public Map<String, Object> update(UUID coachId, UUID planId, Requests.Plan request) {
        validate(request);
        var plan = ownedPlan(coachId, planId, true);
        if (plan.status == PlanStatus.ARCHIVED) throw ApiException.conflict("PLAN_ARCHIVED");
        apply(plan, request);
        plan.updatedAt = Instant.now();
        plans.flush();
        replaceIncludes(plan.id, request.includes());
        return view(plan);
    }

    /** Soft delete: the plan leaves the marketplace, running subscriptions are untouched. */
    public void archive(UUID coachId, UUID planId) {
        var plan = ownedPlan(coachId, planId, true);
        if (plan.status == PlanStatus.ARCHIVED) throw ApiException.conflict("INVALID_STATE");
        plan.status = PlanStatus.ARCHIVED;
        plan.updatedAt = Instant.now();
        plans.flush();
    }

    public void activate(UUID coachId, UUID planId) {
        var plan = ownedPlan(coachId, planId, true);
        if (plan.status == PlanStatus.ACTIVE) throw ApiException.conflict("INVALID_STATE");
        plan.status = PlanStatus.ACTIVE;
        plan.updatedAt = Instant.now();
        plans.flush();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> own(UUID coachId) {
        return plans.findByCoachIdOrderByCreatedAtDesc(coachId).stream().map(this::view).toList();
    }

    /** Public catalogue: only active plans of approved, active coaches. */
    @Transactional(readOnly = true)
    public Views.Paged<Map<String, Object>> marketplace(int page, int size, UUID coachId) {
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
    public Map<String, Object> detail(UUID planId) {
        var plan = plans.findById(planId).orElseThrow(ApiException::notFound);
        boolean visible = plan.status == PlanStatus.ACTIVE && isPublicCoach(plan.coachId);
        if (!visible) throw ApiException.notFound();
        return view(plan);
    }

    @Transactional(readOnly = true)
    public CoachPlan requirePurchasable(UUID planId) {
        var plan = plans.findById(planId).orElseThrow(ApiException::notFound);
        if (plan.status != PlanStatus.ACTIVE) throw ApiException.conflict("PLAN_ARCHIVED");
        return plan;
    }

    private boolean isPublicCoach(UUID coachId) {
        return coaches.findByUser_PublicId(coachId)
                .filter(c -> c.status == CoachStatus.APPROVED)
                .filter(c -> c.user.status == AccountStatus.ACTIVE && c.user.role == Role.COACH)
                .isPresent();
    }

    private void requireApprovedCoach(UUID coachId) {
        var coach = coaches.findByUser_PublicId(coachId).orElseThrow(ApiException::forbidden);
        if (coach.status != CoachStatus.APPROVED || coach.user.status != AccountStatus.ACTIVE || coach.user.role != Role.COACH)
            throw ApiException.forbidden();
    }

    /** 404 when the plan is unknown, 403 when it exists but belongs to another coach. */
    private CoachPlan ownedPlan(UUID coachId, UUID planId, boolean lock) {
        var plan = (lock ? plans.lock(planId) : plans.findById(planId)).orElseThrow(ApiException::notFound);
        if (!plan.coachId.equals(coachId)) throw ApiException.forbidden();
        return plan;
    }

    private void apply(CoachPlan plan, Requests.Plan r) {
        plan.title = r.title().strip();
        plan.description = r.description() == null || r.description().isBlank() ? null : r.description().strip();
        plan.price = r.price();
        plan.durationDays = r.duration_days();
        plan.updatedAt = Instant.now();
    }

    private void replaceIncludes(UUID planId, List<PlanInclude> values) {
        includes.deleteForPlan(planId);
        var unique = new LinkedHashSet<>(values);
        for (var include : unique) {
            var row = new CoachPlanInclude();
            row.planId = planId; row.include = include;
            includes.save(row);
        }
        includes.flush();
    }

    private static void validate(Requests.Plan r) {
        if (r.includes().isEmpty()) throw new ApiException(400, "VALIDATION_FAILED",
                "At least one included service is required", "حداقل یک خدمت باید انتخاب شود");
    }

    private Map<String, Object> view(CoachPlan plan) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("plan_id", plan.id);
        v.put("coach_id", plan.coachId);
        v.put("title", plan.title);
        v.put("description", plan.description);
        v.put("price", plan.price);
        v.put("currency", plan.currency);
        v.put("duration_days", plan.durationDays);
        // Declaration order is stable and matches the documented category list.
        v.put("includes", includes.findForPlan(plan.id).stream().map(i -> i.include)
                .sorted(java.util.Comparator.comparingInt(Enum::ordinal)).toList());
        v.put("status", plan.status);
        v.put("created_at", plan.createdAt);
        v.put("updated_at", plan.updatedAt);
        return v;
    }
}
