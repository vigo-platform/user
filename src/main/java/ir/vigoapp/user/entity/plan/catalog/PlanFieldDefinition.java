package ir.vigoapp.user.entity.plan.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "plan_field_definitions")
public class PlanFieldDefinition {
    public enum ValueType { INTEGER, SINGLE_SELECT, MULTI_SELECT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feature_id",
            foreignKey = @ForeignKey(name = "plan_field_definitions_feature_fkey"))
    public PlanFeatureDefinition feature;

    @Column(nullable = false, unique = true, length = 64, updatable = false)
    public String code;

    @Column(nullable = false, length = 100)
    public String label;

    @Column(name = "help_text", columnDefinition = "text")
    public String helpText;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 20)
    public ValueType valueType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_rules", nullable = false, columnDefinition = "jsonb")
    public Map<String, Object> validationRules = new LinkedHashMap<>();

    @Column(name = "is_required", nullable = false)
    public boolean required;

    @Column(name = "is_active", nullable = false)
    public boolean active = true;

    @Column(name = "display_order", nullable = false)
    public int displayOrder;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = createdAt;
}
