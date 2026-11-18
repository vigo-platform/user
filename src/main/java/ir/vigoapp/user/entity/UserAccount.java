package ir.vigoapp.user.entity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import ir.vigoapp.user.domain.AccountStatus;
import ir.vigoapp.user.domain.Gender;
import ir.vigoapp.user.domain.Role;
import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;
@Getter
@Setter
@NoArgsConstructor
@Entity @Table(name="users")
public class UserAccount {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="public_id",nullable=false,unique=true) private UUID publicId=UUID.randomUUID();
 @Column(length=254) private String email;
 @Column(length=16) private String phone;
 @Getter
 @Accessors(fluent = true)
 @Setter(AccessLevel.NONE)
 @Column(name="password_hash",nullable=false,length=2048) private String passwordHash;
 public void replacePassword(String encoded) { passwordHash=encoded; }
 @Column(name="display_name",nullable=false,length=121) private String displayName;
 @Column(name="avatar_url") private String avatarUrl;
 @Column(name="birth_date") private LocalDate birthDate;
 @Enumerated(EnumType.STRING) private Gender gender;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private Role role=Role.ATHLETE;
 @Enumerated(EnumType.STRING) @Column(nullable=false) private AccountStatus status=AccountStatus.ACTIVE;
 @Column(name="email_verified",nullable=false) private boolean emailVerified;
 @Column(name="phone_verified",nullable=false) private boolean phoneVerified;
 @Column(name="two_factor_authentication",nullable=false) private boolean twoFactorAuthentication;
 @Column(name="credential_version",nullable=false) private long credentialVersion=1;
 @Column(name="credentials_changed_at",nullable=false) private Instant credentialsChangedAt=Instant.now();
 @Column(name="aggregate_version",nullable=false) private long aggregateVersion;
 @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
 @Column(name="updated_at",nullable=false) private Instant updatedAt=createdAt;
 @Version private long version;
 public long nextAggregateVersion() { return ++aggregateVersion; }
 @Override public String toString() { return "UserAccount[redacted]"; }
}
