package ir.vigoapp.user.entity.plan;

import ir.vigoapp.user.domain.plan.PlanStatus;
import ir.vigoapp.user.entity.CoachProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "coach_plans")
public class CoachPlan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coach_user_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "coach_plans_coach_user_fkey"))
    public CoachProfile coach;

    @Column(name = "coach_user_id", nullable = false, insertable = false, updatable = false)
    public Long coachId;

    @Column(nullable = false, length = 100)
    public String title;

    @Column(length = 180)
    public String summary;

    @Column(columnDefinition = "text")
    public String description;

    @Column(name = "price_rial", nullable = false)
    public long priceRial;

    @Column(name = "compare_at_price_rial")
    public Long compareAtPriceRial;

    @Column(name = "currency", nullable = false, length = 3)
    public String currency = "IRR";

    @Column(name = "duration_days", nullable = false)
    public int durationDays;

    @Column(name = "first_delivery_days")
    public Integer firstDeliveryDays;

    @Convert(converter = PlanStatus.ActiveFlagConverter.class)
    @Column(name = "is_active", nullable = false)
    public PlanStatus status = PlanStatus.ARCHIVED;

    @Column(name = "is_recommended", nullable = false)
    public boolean recommended;

    @Column(name = "display_order", nullable = false)
    public int displayOrder;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = createdAt;

    @Version
    public long version;

    protected CoachPlan() {}

    public CoachPlan(CoachProfile coach) {
        this.coach = coach;
        this.coachId = coach.userId;
    }
}
