package ir.vigoapp.user;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NumericUserIdMigrationTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void convertsReviewerAndQueuedEventsBeforeRemovingPublicId() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().start()) {
            var dataSource = postgres.getPostgresDatabase();
            var db = new JdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).target("10").load().migrate();
            var coach = UUID.randomUUID();
            var admin = UUID.randomUUID();
            db.update("""
                    INSERT INTO users (id, public_id, email, password_hash, display_name, role, status,
                                       credentials_changed_at, created_at, updated_at)
                    VALUES (10, ?, 'coach@example.test', 'hash', 'Coach', 'COACH', 'ACTIVE', now(), now()),
                           (4, ?, 'admin@example.test', 'hash', 'Admin', 'ADMIN', 'ACTIVE', now(), now())
                    """, coach, admin);
            var application = UUID.randomUUID();
            db.update("""
                    INSERT INTO coach_applications (id, full_name, first_name, last_name, email, bio, specialties,
                                                    experience_years, status, reviewed_by, created_at, updated_at)
                    VALUES (?, 'Test Coach', 'Test', 'Coach', 'applicant@example.test', 'Bio', '[]',
                            1, 'APPROVED', ?, now(), now())
                    """, application, admin);
            var published = stage(db, coach, 1, "UserRegistered", Map.of("user_id", coach), true);
            var queued = stage(db, coach, 2, "UserStatusChanged",
                    Map.of("user_id", coach, "changed_by", admin, "new_status", "ACTIVE"), false);
            var approval = stage(db, application, 2, "CoachApplicationApproved",
                    Map.of("application_id", application, "reviewed_by", admin), false);

            Flyway.configure().dataSource(dataSource).load().migrate();

            assertThat(db.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'public_id'
                    """, Long.class)).isZero();
            assertThat(db.queryForObject("SELECT reviewed_by FROM coach_applications WHERE id = ?",
                    Long.class, application)).isEqualTo(4L);
            var accountEvent = json.readTree(payload(db, queued));
            assertThat(accountEvent.path("event_id").asString()).isEqualTo(queued.toString());
            assertThat(accountEvent.path("event_version").intValue()).isEqualTo(2);
            assertThat(accountEvent.path("aggregate_id").longValue()).isEqualTo(10L);
            assertThat(accountEvent.path("data")).isEqualTo(json.readTree("""
                    {"user_id":10,"changed_by":4,"new_status":"ACTIVE"}
                    """));
            assertThat(db.queryForObject("SELECT aggregate_id FROM outbox WHERE event_id = ?",
                    String.class, queued)).isEqualTo("10");
            var applicationEvent = json.readTree(payload(db, approval));
            assertThat(applicationEvent.path("aggregate_id").asString()).isEqualTo(application.toString());
            assertThat(applicationEvent.path("data").path("reviewed_by").longValue()).isEqualTo(4L);
            var publishedEvent = json.readTree(payload(db, published));
            assertThat(publishedEvent.path("aggregate_id").asString()).isEqualTo(coach.toString());
            assertThat(publishedEvent.path("event_version").intValue()).isEqualTo(1);
        }
    }

    private UUID stage(JdbcTemplate db, UUID aggregate, long version, String type, Map<String, Object> data,
                       boolean published) {
        var event = UUID.randomUUID();
        String payload = json.writeValueAsString(Map.of("event_id", event, "aggregate_id", aggregate,
                "aggregate_version", version, "event_type", type, "event_version", 1, "data", data));
        db.update("""
                INSERT INTO outbox (event_id, aggregate_id, aggregate_version, payload, occurred_at, published_at)
                VALUES (?, ?, ?, ?, now(), CASE WHEN ? THEN now() END)
                """, event, aggregate, version, payload, published);
        return event;
    }

    private String payload(JdbcTemplate db, UUID id) {
        return db.queryForObject("SELECT payload FROM outbox WHERE event_id = ?", String.class, id);
    }
}
