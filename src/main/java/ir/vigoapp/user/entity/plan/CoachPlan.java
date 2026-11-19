package ir.vigoapp.user.entity.plan;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ir.vigoapp.user.domain.plan.PlanStatus;
import ir.vigoapp.user.entity.CoachProfile;
import jakarta.persistence.Column;
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

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "coach_plans")
public class CoachPlan {
    public static final String DEFAULT_CURRENCY = "IRR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coach_user_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "coach_plans_coach_user_fkey"))
    @Setter(AccessLevel.NONE)
    private CoachProfile coach;

    @Column(name = "coach_user_id", nullable = false, insertable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Long coachId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(length = 180)
    private String summary;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private long price;

    @Column(name = "compare_at_price")
    private Long compareAtPrice;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = DEFAULT_CURRENCY;

    @Column(name = "duration_days", nullable = false)
    private int durationDays;

    @Column(name = "first_delivery_days")
    private Integer firstDeliveryDays;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "is_archived", nullable = false)
    private boolean archived;

    @Column(name = "is_recommended", nullable = false)
    private boolean recommended;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = createdAt;

    @Version
    private long version;

    public CoachPlan(CoachProfile coach) {
        this.coach = coach;
        this.coachId = coach.getUserId();
    }

    public PlanStatus getStatus() {
        if (archived) return PlanStatus.ARCHIVED;
        return active ? PlanStatus.ACTIVE : PlanStatus.INACTIVE;
    }
}
