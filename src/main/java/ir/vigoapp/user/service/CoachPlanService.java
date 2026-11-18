package ir.vigoapp.user.service;

import lombok.RequiredArgsConstructor;
import ir.vigoapp.user.api.*;
import ir.vigoapp.user.domain.AccountStatus;
import ir.vigoapp.user.domain.CoachStatus;
import ir.vigoapp.user.domain.Role;
import ir.vigoapp.user.domain.plan.PlanInclude;
import ir.vigoapp.user.domain.plan.PlanStatus;
import ir.vigoapp.user.dto.Requests;
import ir.vigoapp.user.dto.Views;
import ir.vigoapp.user.entity.CoachProfile;
import ir.vigoapp.user.entity.plan.CoachPlan;
import ir.vigoapp.user.entity.plan.CoachPlanInclude;
import ir.vigoapp.user.repository.CoachPlanIncludeRepository;
import ir.vigoapp.user.repository.CoachPlanRepository;
import ir.vigoapp.user.repository.CoachRepository;
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
@RequiredArgsConstructor
public class CoachPlanService {
    private final CoachPlanRepository plans;
    private final CoachPlanIncludeRepository includes;
    private final CoachRepository coaches;


    /** Creates a plan for the authenticated coach. The coach must be approved. */
    public Map<String, Object> create(UUID coachId, Requests.Plan request) {
        var coach = requireApprovedCoach(coachId);
        validate(request);
        var plan = new CoachPlan(coach);
        apply(plan, request);
        plan.setStatus(PlanStatus.ACTIVE);
        plans.saveAndFlush(plan);
        replaceIncludes(plan.getId(), request.includes());
        return view(plan);
    }

    public Map<String, Object> update(UUID coachId, Long planId, Requests.Plan request) {
        validate(request);
        var plan = ownedPlan(coachId, planId, true);
        if (plan.getStatus() == PlanStatus.ARCHIVED) throw ApiException.conflict("PLAN_ARCHIVED");
        apply(plan, request);
        plan.setUpdatedAt(Instant.now());
        plans.flush();
        replaceIncludes(plan.getId(), request.includes());
        return view(plan);
    }

    /** Soft delete: the plan leaves the marketplace, running subscriptions are untouched. */
    public void archive(UUID coachId, Long planId) {
        var plan = ownedPlan(coachId, planId, true);
        if (plan.getStatus() == PlanStatus.ARCHIVED) throw ApiException.conflict("INVALID_STATE");
        plan.setStatus(PlanStatus.ARCHIVED);
        plan.setUpdatedAt(Instant.now());
        plans.flush();
    }

    public void activate(UUID coachId, Long planId) {
        var plan = ownedPlan(coachId, planId, true);
        if (plan.getStatus() == PlanStatus.ACTIVE) throw ApiException.conflict("INVALID_STATE");
        plan.setStatus(PlanStatus.ACTIVE);
        plan.setUpdatedAt(Instant.now());
        plans.flush();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> own(UUID coachId) {
        return coaches.findByUser_PublicId(coachId)
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

    private CoachProfile requireApprovedCoach(UUID coachId) {
        var coach = coaches.findByUser_PublicId(coachId).orElseThrow(ApiException::forbidden);
        if (coach.getStatus() != CoachStatus.APPROVED || coach.getUser().getStatus() != AccountStatus.ACTIVE || coach.getUser().getRole() != Role.COACH)
            throw ApiException.forbidden();
        return coach;
    }

    /** 404 when the plan is unknown, 403 when it exists but belongs to another coach. */
    private CoachPlan ownedPlan(UUID coachId, Long planId, boolean lock) {
        var plan = (lock ? plans.lock(planId) : plans.findById(planId)).orElseThrow(ApiException::notFound);
        boolean owned = coaches.findByUser_PublicId(coachId)
                .filter(coach -> plan.getCoachId().equals(coach.getUserId()))
                .isPresent();
        if (!owned) throw ApiException.forbidden();
        return plan;
    }

    private void apply(CoachPlan plan, Requests.Plan r) {
        plan.setTitle(r.title().strip());
        plan.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().strip());
        plan.setPriceRial(r.price());
        plan.setDurationDays(r.duration_days());
        plan.setUpdatedAt(Instant.now());
    }

    private void replaceIncludes(Long planId, List<PlanInclude> values) {
        includes.deleteForPlan(planId);
        var unique = new LinkedHashSet<>(values);
        for (var include : unique) {
            var row = new CoachPlanInclude();
            row.setPlanId(planId); row.setInclude(include);
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
        v.put("plan_id", plan.getId());
        v.put("coach_id", plan.getCoachId());
        v.put("title", plan.getTitle());
        v.put("description", plan.getDescription());
        v.put("price", plan.getPriceRial());
        v.put("currency", plan.getCurrency());
        v.put("duration_days", plan.getDurationDays());
        // Declaration order is stable and matches the documented category list.
        v.put("includes", includes.findForPlan(plan.getId()).stream().map(i -> i.getInclude())
                .sorted(java.util.Comparator.comparingInt(Enum::ordinal)).toList());
        v.put("status", plan.getStatus());
        v.put("created_at", plan.getCreatedAt());
        v.put("updated_at", plan.getUpdatedAt());
        return v;
    }
}
