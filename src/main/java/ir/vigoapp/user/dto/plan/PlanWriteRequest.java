package ir.vigoapp.user.dto.plan;

import java.util.List;

/** The normalized coach submission, after checking the JSON types and catalog. */
public record PlanWriteRequest(String title, String summary, String description, long price, Long compareAtPrice,
                               int durationDays, Integer firstDeliveryDays, List<String> includes,
                               List<Long> featureIds, List<PlanFieldValue> fieldValues) {}
