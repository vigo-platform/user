package ir.vigoapp.user.dto.order;

public record CreateOrderRequest(Long plan_id, Long coach_id, Long athlete_id,
                                 int duration_days, String currency) {}
