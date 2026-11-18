package ir.vigoapp.user.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
/** A coach's sellable plan. Ownership is the coach public UUID. */
@Entity @Table(name="coach_plans")
public class CoachPlan implements org.springframework.data.domain.Persistable<UUID> {
 /** See PlanSubscription: explicit newness keeps save() from merging a fresh entity. */
 @Transient private boolean isNew=true;
 @Override public UUID getId(){return id;}
 @Override public boolean isNew(){return isNew;}
 @PostLoad @PostPersist void markNotNew(){isNew=false;}

 @Id @Column(name="plan_id") public UUID id=UUID.randomUUID();
 @Column(name="coach_id",nullable=false) public UUID coachId;
 @Column(nullable=false,length=80) public String title;
 @Column(length=1500) public String description;
 @Column(nullable=false) public long price;
 @Column(nullable=false,length=3) public String currency="IRR";
 @Column(name="duration_days",nullable=false) public int durationDays;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) public PlanStatus status=PlanStatus.ACTIVE;
 @Column(name="created_at",nullable=false) public Instant createdAt=Instant.now();
 @Column(name="updated_at",nullable=false) public Instant updatedAt=createdAt;
 @Version public long version;
 @Override public String toString() { return "CoachPlan[id="+id+",coach="+coachId+"]"; }
}
