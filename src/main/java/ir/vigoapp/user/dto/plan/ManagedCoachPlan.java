package ir.vigoapp.user.dto.plan;

import ir.vigoapp.user.domain.plan.PlanStatus;

import java.time.Instant;
import java.util.List;

public record ManagedCoachPlan(long planId, long coachId, String title, String summary, String description,
                               long price, Long compareAtPrice, String currency, int durationDays,
                               Integer firstDeliveryDays, List<String> includes, List<Long> featureIds,
                               List<PlanFieldValue> fieldValues, PlanStatus status, boolean isActive,
                               boolean isArchived, Instant createdAt, Instant updatedAt) {}
