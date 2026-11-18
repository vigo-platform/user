package ir.vigoapp.user.domain.plan;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

public enum PlanStatus {
    ACTIVE, ARCHIVED;

    @Converter
    public static class ActiveFlagConverter implements AttributeConverter<PlanStatus, Boolean> {
        @Override
        public Boolean convertToDatabaseColumn(PlanStatus status) {
            return status == null ? null : status == ACTIVE;
        }

        @Override
        public PlanStatus convertToEntityAttribute(Boolean active) {
            if (active == null) return null;
            return active ? ACTIVE : ARCHIVED;
        }
    }
}
