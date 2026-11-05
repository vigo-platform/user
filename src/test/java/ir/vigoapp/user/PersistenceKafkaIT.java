package ir.vigoapp.user;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.service.*;
import ir.vigoapp.user.outbox.*;
import ir.vigoapp.contracts.user.v1.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.profiles.active=dev","app.grpc.dev-plaintext-enabled=true","app.grpc.port=0","app.outbox.enabled=false","management.server.port=0","app.jwt.jwks-uri=https://auth.example.test/jwks","app.jwt.issuer=https://auth.example.test","app.jwt.audience=vigo-api"})
@Testcontainers(disabledWithoutDocker=true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PersistenceKafkaIT {
 @Container static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("vigo_user_service");
 @Container static KafkaContainer kafka=new KafkaContainer("apache/kafka:3.9.1");
 @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("spring.datasource.url",postgres::getJdbcUrl);p.add("spring.datasource.username",postgres::getUsername);p.add("spring.datasource.password",postgres::getPassword);p.add("spring.kafka.bootstrap-servers",kafka::getBootstrapServers);}
 @Autowired CredentialService credentials;@Autowired UserRepository users;@Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager manager;@Autowired KafkaTemplate<String,String> producer;@Autowired OutboxWriter writer;
 @Autowired ProfileService profiles;
 @Test @Order(5) void retryHeadBlocksSuccessorAndExpiredLeaseReclaimsStableEvents(){
  var created=credentials.create(request("ordered@example.test"));UUID id=users.findById(created.getUserId()).orElseThrow().publicId;
  new TransactionTemplate(manager).executeWithoutResult(s->{var u=users.lock(id).orElseThrow();writer.emit(u,"UserStatusChanged",Map.of("user_id",id,"old_status","ACTIVE","new_status","SUSPENDED"));});
  var ids=jdbc.queryForList("select event_id from outbox where aggregate_id=? order by aggregate_version",UUID.class,id);
  @SuppressWarnings("unchecked") KafkaTemplate<String,String> failing=org.mockito.Mockito.mock(KafkaTemplate.class);
  org.mockito.Mockito.when(failing.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("synthetic broker failure")));
  var failedRelay=new OutboxRelay(jdbc,failing,manager,new SimpleMeterRegistry());failedRelay.poll();failedRelay.poll();
  org.mockito.Mockito.verify(failing,org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.eq("user.events"),org.mockito.ArgumentMatchers.eq(id.toString()),org.mockito.ArgumentMatchers.anyString());
  assertThat(jdbc.queryForObject("select count(*) from outbox where aggregate_id=? and published_at is null",Long.class,id)).isEqualTo(2);
  // Simulate a crashed worker's expired lease; another worker can reclaim only this head.
  jdbc.update("update outbox set available_at=now(),lease_token=?,lease_until=now()-interval '1 second' where aggregate_id=? and aggregate_version=1",UUID.randomUUID(),id);
  var relay=new OutboxRelay(jdbc,producer,manager,new SimpleMeterRegistry());relay.poll();
  assertThat(jdbc.queryForObject("select count(*) from outbox where aggregate_id=? and published_at is null",Long.class,id)).isEqualTo(1);
  relay.poll();assertThat(jdbc.queryForObject("select count(*) from outbox where aggregate_id=? and published_at is null",Long.class,id)).isZero();
  assertThat(jdbc.queryForList("select event_id from outbox where aggregate_id=? order by aggregate_version",UUID.class,id)).containsExactlyElementsOf(ids);
 }
 @Test @Order(6) void twoRelayWorkersCannotOvertakeUnacknowledgedHead()throws Exception{
  var created=credentials.create(request("workers@example.test"));UUID id=users.findById(created.getUserId()).orElseThrow().publicId;
  new TransactionTemplate(manager).executeWithoutResult(s->{var u=users.lock(id).orElseThrow();writer.emit(u,"UserRoleChanged",Map.of("user_id",id,"old_role","ATHLETE","new_role","COACH"));});
  @SuppressWarnings("unchecked") KafkaTemplate<String,String> slow=org.mockito.Mockito.mock(KafkaTemplate.class);
  var pending=new CompletableFuture<org.springframework.kafka.support.SendResult<String,String>>();var entered=new CountDownLatch(1);
  org.mockito.Mockito.when(slow.send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenAnswer(i->{entered.countDown();return pending;});
  var first=new OutboxRelay(jdbc,slow,manager,new SimpleMeterRegistry());var second=new OutboxRelay(jdbc,slow,manager,new SimpleMeterRegistry());
  try(var pool=Executors.newFixedThreadPool(2)){
   try {
    var worker=pool.submit(first::poll);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
    pool.submit(second::poll).get(5,TimeUnit.SECONDS);
    org.mockito.Mockito.verify(slow,org.mockito.Mockito.times(1)).send(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
    pending.complete(null);worker.get(5,TimeUnit.SECONDS);
   }finally{pending.complete(null);}
  }
  assertThat(jdbc.queryForObject("select count(*) from outbox where aggregate_id=? and published_at is null",Long.class,id)).isEqualTo(1);
  new OutboxRelay(jdbc,producer,manager,new SimpleMeterRegistry()).poll();
 }
 CreateUserRequest request(String email){return CreateUserRequest.newBuilder().setEmail(email).setFirstName("Synthetic").setLastName("User").setPasswordHash("synthetic-unchanged-hash").build();}
 @Test @Order(7) void publicCoachListFiltersBeforePagingAndCounting(){
  seedCoach("suspended",Role.COACH,AccountStatus.SUSPENDED,CoachStatus.APPROVED);
  seedCoach("athlete",Role.ATHLETE,AccountStatus.ACTIVE,CoachStatus.APPROVED);
  seedCoach("pending",Role.COACH,AccountStatus.ACTIVE,CoachStatus.PENDING);
  UUID first=seedCoach("first",Role.COACH,AccountStatus.ACTIVE,CoachStatus.APPROVED);
  UUID second=seedCoach("second",Role.COACH,AccountStatus.ACTIVE,CoachStatus.APPROVED);

  var page=profiles.listPublicCoaches(0,1);
  assertThat(page.total_elements()).isEqualTo(2);
  assertThat(page.total_pages()).isEqualTo(2);
  assertThat(page.content()).singleElement().satisfies(coach->assertThat(coach)
   .containsEntry("coach_id",first).doesNotContainKeys("status","rejection_reason","email","phone"));
  assertThat(profiles.listPublicCoaches(1,1).content()).extracting(coach->coach.get("coach_id")).containsExactly(second);
  assertThat(profiles.listPublicCoaches(2,1).content()).isEmpty();
 }
 private UUID seedCoach(String name,Role role,AccountStatus accountStatus,CoachStatus coachStatus){
  long id=credentials.create(request("coach-"+name+"@example.test")).getUserId();
  jdbc.update("update users set role=?, status=? where id=?",role.name(),accountStatus.name(),id);
  jdbc.update("""
   insert into coach_profiles (user_id, display_name, bio, specialties, experience_years,
    certifications, result_image_urls, status, created_at, updated_at)
   values (?, ?, 'Synthetic biography', '["fitness"]', 3, '[]', '[]', ?,
    timestamp '2025-01-01 00:00:00', timestamp '2025-01-01 00:00:00')
   """,id,name,coachStatus.name());
  return users.findById(id).orElseThrow().publicId;
 }
 @Test @Order(1) void migrationAndConcurrentDatabaseUniqueness()throws Exception{
  try(var pool=Executors.newFixedThreadPool(2)){
   var barrier=new CyclicBarrier(2);Callable<Boolean> action=()->{barrier.await();try{credentials.create(request(" UNIQUE@EXAMPLE.TEST "));return true;}catch(org.springframework.dao.DataIntegrityViolationException e){return false;}};
   var a=pool.submit(action);var b=pool.submit(action);assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder(true,false);
  }
  assertThat(jdbc.queryForObject("select count(*) from users where email='unique@example.test'",Long.class)).isEqualTo(1);
  assertThat(jdbc.queryForObject("select password_hash from users where email='unique@example.test'",String.class)).isEqualTo("synthetic-unchanged-hash");
 }
 @Test @Order(2) void databaseCasHasExactlyOneWinner()throws Exception{
  var u=credentials.create(request("cas@example.test"));
  var request=ChangeUserPasswordRequest.newBuilder().setUserId(u.getUserId()).setExpectedCredentialVersion(1).setNewPasswordHash("new-synthetic-hash").build();
  try(var pool=Executors.newFixedThreadPool(2)){
   var barrier=new CyclicBarrier(2);Callable<String> action=()->{barrier.await();try{return Long.toString(credentials.change(request));}catch(io.grpc.StatusRuntimeException e){return e.getStatus().getCode().name();}};
   var a=pool.submit(action);var b=pool.submit(action);assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder("2","ABORTED");
  }
 }
 @Test @Order(3) void domainAndOutboxRollbackTogether(){
  var tx=new TransactionTemplate(manager);
  assertThatThrownBy(()->tx.executeWithoutResult(s->{credentials.create(request("rollback@example.test"));throw new IllegalStateException("force rollback");})).isInstanceOf(IllegalStateException.class);
  assertThat(users.findByEmail("rollback@example.test")).isEmpty();
  assertThat(jdbc.queryForObject("select count(*) from outbox where payload like '%rollback@example.test%'",Long.class)).isZero();
 }
 @Test @Order(4) void brokerAckThenMarkAndStableEventIds(){
  var u=credentials.create(request("relay@example.test"));
  UUID id=users.findById(u.getUserId()).orElseThrow().publicId;
  var relay=new OutboxRelay(jdbc,producer,manager,new SimpleMeterRegistry());
  relay.poll();
  assertThat(jdbc.queryForObject("select count(*) from outbox where published_at is null",Long.class)).isZero();
  Properties config=new Properties();config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,kafka.getBootstrapServers());config.put(ConsumerConfig.GROUP_ID_CONFIG,UUID.randomUUID().toString());config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
  try(var consumer=new KafkaConsumer<String,String>(config,new StringDeserializer(),new StringDeserializer())){
   consumer.subscribe(List.of("user.events"));String event=null;Instant deadline=Instant.now().plusSeconds(20);
   while(Instant.now().isBefore(deadline)&&event==null){for(var record:consumer.poll(Duration.ofSeconds(1)))if(id.toString().equals(record.key()))event=record.value();}
   assertThat(event).isNotNull().contains("UserRegistered","event_id","aggregate_version","user-service").doesNotContain("password_hash","synthetic-unchanged-hash");
   String stored=jdbc.queryForObject("select payload from outbox where aggregate_id=?",String.class,id);assertThat(event).isEqualTo(stored);
  }
 }
}
