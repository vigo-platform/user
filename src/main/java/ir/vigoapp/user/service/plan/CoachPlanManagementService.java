package ir.vigoapp.user.service.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import ir.vigoapp.user.api.ApiException;
import ir.vigoapp.user.domain.AccountStatus;
import ir.vigoapp.user.domain.CoachStatus;
import ir.vigoapp.user.domain.Role;
import ir.vigoapp.user.domain.plan.PlanStatus;
import ir.vigoapp.user.dto.Views;
import ir.vigoapp.user.dto.plan.ManagedCoachPlan;
import ir.vigoapp.user.dto.plan.ManagedCoachPlan.FieldValue;
import ir.vigoapp.user.dto.plan.PublicCoachPlan.Label;
import ir.vigoapp.user.entity.CoachProfile;
import ir.vigoapp.user.entity.plan.CoachPlan;
import ir.vigoapp.user.repository.CoachPlanIncludeRepository;
import ir.vigoapp.user.repository.CoachPlanRepository;
import ir.vigoapp.user.repository.CoachRepository;
import ir.vigoapp.user.repository.plan.CoachPlanSelectionRepository;
import ir.vigoapp.user.service.plan.validation.PlanSubmissionValidator;
import ir.vigoapp.user.service.plan.validation.PlanValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

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
@Slf4j
public class CoachPlanManagementService {
    // LogstashEncoder writes Jackson 2 trees as JSON, and the terminal prints them with control characters escaped.
    private static final ObjectMapper LOG_JSON = new ObjectMapper();
    private final CoachPlanRepository plans;
    private final CoachPlanIncludeRepository includes;
    private final CoachRepository coaches;
    private final PlanSubmissionValidator validator;
    private final CoachPlanSelectionRepository selections;

    /** Validate against one catalog snapshot and publish the complete plan at commit. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ManagedCoachPlan create(long coachId, JsonNode body) {
        return write("create", coachId, null, outcome -> {
            var plan = new CoachPlan(requireApprovedCoach(coachId));
            var submission = validator.validateCreate(body, plan);
            persist(plan, submission);
            var created = view(plan);
            outcome.saved(plan, planChanges(null, created));
            return created;
        });
    }

    public ManagedCoachPlan patch(long coachId, Long planId, JsonNode body) {
        return write("patch", coachId, planId, outcome -> {
            var plan = lockEditablePlan(coachId, planId);
            var before = view(plan);
            if (body != null && body.isObject() && body.isEmpty()) {
                outcome.saved(plan, Map.of());
                return before;
            }

            boolean changesIncludes = body != null && body.has("includes");
            Set<String> existingIncludes = changesIncludes ? Set.copyOf(before.includes()) : Set.of();
            var submission = validator.validatePatch(body, plan, existingIncludes);
            if (changesIncludes) selections.clearIncludes(planId);
            selections.clearFieldValues(planId, submission.touchedFieldIds());
            selections.removeFeatures(planId, submission.removedFeatureIds());
            persist(plan, submission);
            var updated = view(plan);
            outcome.saved(plan, planChanges(before, updated));
            return updated;
        });
    }

    private void persist(CoachPlan plan, PlanSubmissionValidator.Submission submission) {
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
    }

    /** Soft delete: the plan leaves the marketplace, running subscriptions are untouched. */
    public void archive(long coachId, Long planId) {
        write("archive", coachId, planId, outcome -> {
            var plan = lockOwnedPlan(coachId, planId);
            if (plan.isArchived()) throw ApiException.conflict("INVALID_STATE");
            plan.setArchived(true);
            plan.setUpdatedAt(Instant.now());
            plans.flush();
            outcome.saved(plan, Map.of("is_archived", true));
            return null;
        });
    }

    public void unarchive(long coachId, Long planId) {
        write("unarchive", coachId, planId, outcome -> {
            var plan = lockOwnedPlan(coachId, planId);
            if (!plan.isArchived()) throw ApiException.conflict("INVALID_STATE");
            plan.setArchived(false);
            plan.setUpdatedAt(Instant.now());
            plans.flush();
            outcome.saved(plan, Map.of("is_archived", false));
            return null;
        });
    }

    private static <T> T write(String operation, long coachId, Long planId, Function<WriteOutcome, T> action) {
        var outcome = new WriteOutcome(operation, coachId, planId);
        try {
            T result = action.apply(outcome);
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    && TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(outcome);
            } else {
                outcome.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
            }
            return result;
        } catch (ApiException error) {
            var event = outcome.event(Level.WARN).addKeyValue("result", "REJECTED")
                    .addKeyValue("stage", "service")
                    .addKeyValue("error_code", error.code).addKeyValue("status", error.status);
            if (error instanceof PlanValidationException validation) {
                event.addKeyValue("validation_issue_count", validation.issues().size())
                        .addKeyValue("validation_codes", validation.issues().stream().map(issue -> issue.code()).distinct().toList());
            }
            event.log("Coach plan write was rejected.");
            throw error;
        } catch (RuntimeException error) {
            outcome.event(Level.ERROR).addKeyValue("result", "FAILURE").addKeyValue("stage", "service")
                    .addKeyValue("reason", error.getClass().getSimpleName()).log("Coach plan write failed.");
            throw error;
        }
    }

    private static final class WriteOutcome implements TransactionSynchronization {
        private final String operation;
        private final long coachId;
        private final Long requestedPlanId;
        private final long startedAt = System.nanoTime();
        private Long savedPlanId;
        private String planStatus;
        private Map<String, Object> changes;

        private WriteOutcome(String operation, long coachId, Long requestedPlanId) {
            this.operation = operation;
            this.coachId = coachId;
            this.requestedPlanId = requestedPlanId;
        }

        private void saved(CoachPlan plan, Map<String, Object> changes) {
            savedPlanId = plan.getId();
            planStatus = plan.getStatus().name();
            this.changes = changes;
        }

        private LoggingEventBuilder event(Level level) {
            var event = log.atLevel(level).addKeyValue("event", "coach_plan_write_completed")
                    .addKeyValue("operation", operation).addKeyValue("actor_user_id", coachId).addKeyValue("coach_id", coachId)
                    .addKeyValue("duration_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            if (requestedPlanId != null) event.addKeyValue("plan_id", requestedPlanId);
            return event;
        }

        @Override
        public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) {
                boolean rolledBack = status == STATUS_ROLLED_BACK;
                event(Level.ERROR).addKeyValue("result", rolledBack ? "FAILURE" : "UNKNOWN")
                        .addKeyValue("stage", "transaction")
                        .addKeyValue("reason", rolledBack ? "transaction_rolled_back" : "transaction_outcome_unknown")
                        .log("Coach plan commit could not be confirmed.");
                return;
            }
            var event = event(Level.INFO).addKeyValue("result", changes.isEmpty() ? "NO_CHANGE" : "SUCCESS")
                    .addKeyValue("plan_status", planStatus);
            if (requestedPlanId == null) event.addKeyValue("plan_id", savedPlanId);
            if (!changes.isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode values = LOG_JSON.valueToTree(changes);
                event.addKeyValue("changed_fields", List.copyOf(changes.keySet()))
                        .addKeyValue("changes", values);
            }
            event.log("Coach plan write completed.");
        }
    }

    private static Map<String, Object> planChanges(ManagedCoachPlan before, ManagedCoachPlan after) {
        var changes = planProperties(after);
        if (before != null) {
            var previous = planProperties(before);
            changes.entrySet().removeIf(entry -> Objects.equals(entry.getValue(), previous.get(entry.getKey())));
        }

        var previousAnswers = new LinkedHashMap<Long, JsonNode>();
        if (before != null) before.fieldValues().forEach(answer -> previousAnswers.put(answer.fieldId(), answer.value()));
        var changedAnswers = new ArrayList<Map<String, Object>>();
        for (var answer : after.fieldValues()) {
            if (!Objects.equals(previousAnswers.remove(answer.fieldId()), answer.value())) {
                changedAnswers.add(loggedFieldValue(answer));
            }
        }
        previousAnswers.keySet().forEach(id -> changedAnswers.add(Map.of("field_id", id, "removed", true)));
        if (!changedAnswers.isEmpty()) changes.put("field_values", changedAnswers);
        return changes;
    }

    private static Map<String, Object> planProperties(ManagedCoachPlan plan) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("title", plan.title());
        properties.put("summary", plan.summary());
        properties.put("description", plan.description());
        properties.put("price", plan.price());
        properties.put("compare_at_price", plan.compareAtPrice());
        properties.put("currency", plan.currency());
        properties.put("duration_days", plan.durationDays());
        properties.put("first_delivery_days", plan.firstDeliveryDays());
        properties.put("includes", plan.includes());
        properties.put("feature_ids", plan.featureIds());
        properties.put("is_active", plan.isActive());
        properties.put("is_archived", plan.isArchived());
        return properties;
    }

    private static Map<String, Object> loggedFieldValue(FieldValue answer) {
        var value = answer.value();
        long id = answer.fieldId();
        if (value.isIntegralNumber()) return Map.of("field_id", id, "value_integer", value.longValue());
        if (value.isString()) return Map.of("field_id", id, "value_text", value.stringValue());
        if (value.isArray()) {
            var options = new ArrayList<String>();
            value.forEach(option -> options.add(option.stringValue()));
            return Map.of("field_id", id, "value_options", options);
        }
        return Map.of("field_id", id, "value_json", value.toString());
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

    private CoachPlan lockEditablePlan(long coachId, Long planId) {
        var plan = lockOwnedPlan(coachId, planId);
        if (plan.isArchived()) throw ApiException.conflict("PLAN_ARCHIVED");
        if (!plan.isActive()) throw ApiException.conflict("PLAN_INACTIVE");
        return plan;
    }

    private ManagedCoachPlan view(CoachPlan plan) {
        long planId = plan.getId();
        var features = selections.findSelectedFeatures(planId).stream()
                .map(feature -> new Label(feature.id(), feature.code(), feature.label()))
                .sorted(Comparator.comparingLong(Label::id))
                .toList();
        var fieldValues = selections.findSelectedFields(planId).stream()
                .map(selection -> {
                    var field = selection.field();
                    return new FieldValue(field.id(), selection.featureId(), field.code(), field.label(),
                            field.value(), field.selectedOptions());
                })
                .sorted(Comparator.comparingLong(FieldValue::fieldId))
                .toList();

        return new ManagedCoachPlan(planId, plan.getCoachId(), plan.getTitle(), plan.getSummary(),
                plan.getDescription(), plan.getPrice(), plan.getCompareAtPrice(), plan.getCurrency(),
                plan.getDurationDays(), plan.getFirstDeliveryDays(), includes.findCodesForPlan(planId), features, fieldValues,
                plan.getStatus(), plan.isActive(), plan.isArchived(), plan.getCreatedAt(), plan.getUpdatedAt());
    }
}
