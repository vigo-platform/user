package ir.vigoapp.user.entity.plan.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "plan_feature_catalog")
public class PlanFeatureDefinition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 64, updatable = false)
    public String code;

    @Column(nullable = false, length = 100)
    public String title;

    @Column(columnDefinition = "text")
    public String description;

    @Column(nullable = false, length = 24)
    public String category;

    @Column(name = "is_active", nullable = false)
    public boolean active = true;

    @Column(name = "display_order", nullable = false)
    public int displayOrder;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = createdAt;
}
