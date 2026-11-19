package ir.vigoapp.user.service.plan;

import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.domain.AccountStatus;
import ir.vigoapp.user.domain.CoachStatus;
import ir.vigoapp.user.domain.Role;
import ir.vigoapp.user.domain.plan.PlanStatus;
import ir.vigoapp.user.dto.Views;
import ir.vigoapp.user.dto.plan.ManagedCoachPlan;
import ir.vigoapp.user.dto.plan.PlanFieldValue;
import ir.vigoapp.user.entity.CoachProfile;
import ir.vigoapp.user.entity.plan.CoachPlan;
import ir.vigoapp.user.repository.CoachPlanIncludeRepository;
import ir.vigoapp.user.repository.CoachPlanRepository;
import ir.vigoapp.user.repository.CoachRepository;
import ir.vigoapp.user.repository.plan.CoachPlanSelectionRepository;
import ir.vigoapp.user.service.plan.validation.PlanSubmissionValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Coach plan management.
 *
 * <p>Ownership is always taken from the authenticated actor, never from the
 * request body, so a coach cannot create or edit a plan that belongs to someone
 * else.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class CoachPlanManagementService {
    private final CoachPlanRepository plans;
    private final CoachPlanIncludeRepository includes;
    private final CoachRepository coaches;
    private final PlanSubmissionValidator validator;
    private final CoachPlanSelectionRepository selections;

    /** Validate against one catalog snapshot and publish the complete plan at commit. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ManagedCoachPlan create(long coachId, JsonNode body) {
        var plan = new CoachPlan(requireApprovedCoach(coachId));
        var submission = validator.validate(body, plan, Set.of());
        return save(plan, submission);
    }

    public ManagedCoachPlan update(long coachId, Long planId, JsonNode body) {
        var plan = lockOwnedPlan(coachId, planId);
        if (plan.isArchived()) throw ApiException.conflict("PLAN_ARCHIVED");
        var submission = validator.validate(body, plan, Set.copyOf(includes.findCodesForPlan(planId)));
        selections.clear(planId);
        return save(plan, submission);
    }

    private ManagedCoachPlan save(CoachPlan plan, PlanSubmissionValidator.Submission submission) {
        var request = submission.request();
        plan.setTitle(request.title());
        plan.setSummary(request.summary());
        plan.setDescription(request.description());
        plan.setPrice(request.price());
        plan.setCompareAtPrice(request.compareAtPrice());
        plan.setDurationDays(request.durationDays());
        plan.setFirstDeliveryDays(request.firstDeliveryDays());
        plan.setUpdatedAt(Instant.now());
        plans.saveAndFlush(plan);
        selections.insert(plan.getId(), submission.includeIds(), request.featureIds(), request.fieldValues());
        return response(plan, request.includes(), request.featureIds(), request.fieldValues());
    }

    /** Soft delete: the plan leaves the marketplace, running subscriptions are untouched. */
    public void archive(long coachId, Long planId) {
        var plan = lockOwnedPlan(coachId, planId);
        if (plan.isArchived()) throw ApiException.conflict("INVALID_STATE");
        plan.setArchived(true);
        plan.setUpdatedAt(Instant.now());
        plans.flush();
    }

    public void unarchive(long coachId, Long planId) {
        var plan = lockOwnedPlan(coachId, planId);
        if (!plan.isArchived()) throw ApiException.conflict("INVALID_STATE");
        plan.setArchived(false);
        plan.setUpdatedAt(Instant.now());
        plans.flush();
    }

    @Transactional(readOnly = true)
    public List<ManagedCoachPlan> own(long coachId) {
        return coaches.findByUserId(coachId)
                .map(coach -> plans.findByCoachIdOrderByCreatedAtDesc(coach.getUserId()).stream().map(this::view).toList())
                .orElseGet(List::of);
    }

    /** Public catalogue of active, unarchived plans from approved, active coaches. */
    @Transactional(readOnly = true)
    public Views.Paged<ManagedCoachPlan> marketplace(int page, int size, Long coachId) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<CoachPlan> result;
        if (coachId != null) {
            if (!isPublicCoach(coachId)) return Views.page(Page.empty(pageable), this::view);
            result = plans.findByCoachIdAndActiveTrueAndArchivedFalse(coachId, pageable);
        } else {
            var approved = coaches.findApprovedCoachIds(CoachStatus.APPROVED, AccountStatus.ACTIVE, Role.COACH);
            if (approved.isEmpty()) return Views.page(Page.empty(pageable), this::view);
            result = plans.findByCoachIdInAndActiveTrueAndArchivedFalse(approved, pageable);
        }
        return Views.page(result, this::view);
    }

    @Transactional(readOnly = true)
    public ManagedCoachPlan detail(Long planId) {
        var plan = plans.findById(planId).orElseThrow(ApiException::notFound);
        boolean visible = plan.getStatus() == PlanStatus.ACTIVE && isPublicCoach(plan.getCoachId());
        if (!visible) throw ApiException.notFound();
        return view(plan);
    }

    @Transactional(readOnly = true)
    public CoachPlan requirePurchasable(Long planId) {
        var plan = plans.findById(planId).orElseThrow(ApiException::notFound);
        if (plan.isArchived()) throw ApiException.conflict("PLAN_ARCHIVED");
        if (!plan.isActive()) throw ApiException.conflict("PLAN_INACTIVE");
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
    private CoachPlan lockOwnedPlan(long coachId, Long planId) {
        var plan = plans.lock(planId).orElseThrow(ApiException::notFound);
        if (plan.getCoachId() != coachId) throw ApiException.forbidden();
        return plan;
    }

    private ManagedCoachPlan view(CoachPlan plan) {
        return response(plan, includes.findCodesForPlan(plan.getId()), selections.findFeatureIds(plan.getId()),
                selections.findFieldValues(plan.getId()));
    }

    private ManagedCoachPlan response(CoachPlan plan, List<String> includeCodes, List<Long> featureIds,
                                  List<PlanFieldValue> fieldValues) {
        return new ManagedCoachPlan(plan.getId(), plan.getCoachId(), plan.getTitle(), plan.getSummary(),
                plan.getDescription(), plan.getPrice(), plan.getCompareAtPrice(), plan.getCurrency(),
                plan.getDurationDays(), plan.getFirstDeliveryDays(), includeCodes, featureIds, fieldValues,
                plan.getStatus(), plan.isActive(), plan.isArchived(), plan.getCreatedAt(), plan.getUpdatedAt());
    }
}
