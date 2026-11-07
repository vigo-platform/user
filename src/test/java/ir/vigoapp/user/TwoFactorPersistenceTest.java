package ir.vigoapp.user;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import ir.vigoapp.user.domain.UserAccount;
import ir.vigoapp.user.domain.UserRepository;
import ir.vigoapp.user.outbox.OutboxWriter;
import ir.vigoapp.user.service.CredentialService;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TwoFactorPersistenceTest {
    @Test
    void migrationBackfillsExistingAccountsAndGrpcReadsPersistedSetting() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setPort(0).start()) {
            var dataSource = postgres.getPostgresDatabase();
            var db = new JdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).target("1").load().migrate();
            UUID publicId = UUID.randomUUID();
            long id = db.queryForObject("""
                insert into users(public_id,email,phone,password_hash,display_name,role,status,
                    credential_version,credentials_changed_at,created_at,updated_at)
                values (?,? ,?,'encoded-test-hash','Existing Account','ATHLETE','ACTIVE',1,now(),now(),now())
                returning id
                """, Long.class, publicId, "persisted@example.com", "+989121234567");

            // Upgrade the existing schema instead of rewriting V1 or recreating data.
            Flyway.configure().dataSource(dataSource).load().migrate();
            assertThat(db.queryForObject("select two_factor_authentication from users where id=?", Boolean.class, id)).isFalse();
            assertThat(db.queryForObject("select is_nullable from information_schema.columns where table_name='users' and column_name='two_factor_authentication'", String.class)).isEqualTo("NO");

            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(UserAccount.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            factory.afterPropertiesSet();
            EntityManager entityManager = null;
            try {
                entityManager = factory.getObject().createEntityManager();
                var repository = new JpaRepositoryFactory(entityManager).getRepository(UserRepository.class);
                var service = new CredentialService(repository, mock(OutboxWriter.class), Duration.ZERO);
                assertThat(service.byId(id).getTwoFactorEnabled()).isFalse();

                // Force a DB read after each change; the mapper must not hard-code false
                // or infer enrollment from email_verified / phone_verified.
                for (boolean enabled : new boolean[]{true, false}) {
                    db.update("update users set two_factor_authentication=? where id=?", enabled, id);
                    entityManager.clear();
                    assertThat(service.byId(id).getTwoFactorEnabled()).isEqualTo(enabled);
                    assertThat(service.loginById(id).getUser().getTwoFactorEnabled()).isEqualTo(enabled);
                    assertThat(service.loginByEmail("persisted@example.com").getUser().getTwoFactorEnabled()).isEqualTo(enabled);
                    assertThat(service.loginByPhone("+989121234567").getUser().getTwoFactorEnabled()).isEqualTo(enabled);
                }
            } finally {
                if (entityManager != null) entityManager.close();
                factory.destroy();
            }
        }
    }
}
