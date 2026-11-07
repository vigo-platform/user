package ir.vigoapp.user.domain;
import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;
@Entity @Table(name="users")
public class UserAccount {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Column(name="public_id",nullable=false,unique=true) public UUID publicId=UUID.randomUUID();
 @Column(length=254) public String email;
 @Column(length=16) public String phone;
 @Column(name="password_hash",nullable=false,length=2048) private String passwordHash;
 public String passwordHash() { return passwordHash; }
 public void replacePassword(String encoded) { passwordHash=encoded; }
 @Column(name="display_name",nullable=false,length=121) public String displayName;
 @Column(name="avatar_url") public String avatarUrl;
 @Column(name="birth_date") public LocalDate birthDate;
 @Enumerated(EnumType.STRING) public Gender gender;
 @Enumerated(EnumType.STRING) @Column(nullable=false) public Role role=Role.ATHLETE;
 @Enumerated(EnumType.STRING) @Column(nullable=false) public AccountStatus status=AccountStatus.ACTIVE;
 @Column(name="email_verified",nullable=false) public boolean emailVerified;
 @Column(name="phone_verified",nullable=false) public boolean phoneVerified;
 @Column(name="two_factor_authentication",nullable=false) public boolean twoFactorAuthentication;
 @Column(name="credential_version",nullable=false) public long credentialVersion=1;
 @Column(name="credentials_changed_at",nullable=false) public Instant credentialsChangedAt=Instant.now();
 @Column(name="aggregate_version",nullable=false) public long aggregateVersion;
 @Column(name="created_at",nullable=false) public Instant createdAt=Instant.now();
 @Column(name="updated_at",nullable=false) public Instant updatedAt=createdAt;
 @Version public long version;
 @Override public String toString() { return "UserAccount[redacted]"; }
}
