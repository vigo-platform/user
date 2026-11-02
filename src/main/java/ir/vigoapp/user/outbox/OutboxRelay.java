package ir.vigoapp.user.outbox;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.TimeUnit;
@Component @ConditionalOnProperty(name="app.outbox.enabled",havingValue="true",matchIfMissing=true)
public class OutboxRelay {
 private final JdbcTemplate jdbc; private final KafkaTemplate<String,String> kafka; private final TransactionTemplate tx;
 private final io.micrometer.core.instrument.Counter retries;
 public OutboxRelay(JdbcTemplate jdbc,KafkaTemplate<String,String> kafka,PlatformTransactionManager manager,MeterRegistry metrics) {
  this.jdbc=jdbc;this.kafka=kafka;this.tx=new TransactionTemplate(manager);this.tx.setTimeout(90);
  retries=metrics.counter("user.outbox.retries");
  metrics.gauge("user.outbox.backlog",this,r->r.number("select count(*) from outbox where published_at is null"));
  metrics.gauge("user.outbox.lag.seconds",this,r->r.number("select coalesce(extract(epoch from now()-min(occurred_at)),0) from outbox where published_at is null"));
 }
 private double number(String sql) { try {return jdbc.queryForObject(sql,Double.class);} catch(RuntimeException e){return Double.NaN;} }
 @Scheduled(fixedDelayString="${app.outbox.poll-ms:1000}")
 public void poll() {
  // One head per aggregate; delayed/leased heads block successors, not unrelated aggregates.
  List<Claim> claims=tx.execute(s->jdbc.query("""
   WITH candidates AS (
    SELECT o.id FROM outbox o WHERE o.published_at IS NULL AND o.available_at<=now()
     AND (o.lease_until IS NULL OR o.lease_until<now())
     AND NOT EXISTS (SELECT 1 FROM outbox p WHERE p.aggregate_id=o.aggregate_id AND p.aggregate_version<o.aggregate_version AND p.published_at IS NULL)
    ORDER BY o.id LIMIT 25 FOR UPDATE OF o SKIP LOCKED
   ) UPDATE outbox o SET lease_token=?,lease_until=now()+interval '60 seconds'
    FROM candidates c WHERE o.id=c.id RETURNING o.id,o.lease_token
   """,(rs,n)->new Claim(rs.getLong(1),rs.getObject(2,UUID.class)),UUID.randomUUID()));
  if(claims==null)return;
  for(Claim c:claims) publish(c);
 }
 void publish(Claim c) {
  // Keep the row lock through broker ACK and mark. Reclaim uses SKIP LOCKED, so a slow
  // worker cannot be overtaken. Recheck token under lock fences stale claimed workers.
  try {
   tx.executeWithoutResult(s->{
    var rows=jdbc.queryForList("select aggregate_id,payload from outbox where id=? and lease_token=? and published_at is null for update",c.id,c.token);
    if(rows.isEmpty())return;
    var row=rows.getFirst();
    try {
     // Wait for the producer terminal outcome (delivery.timeout.ms=30000). An interrupt
     // must not release the aggregate head while the original send is still in flight.
     kafka.send("user.events",row.get("aggregate_id").toString(),row.get("payload").toString()).join();
    } catch(Exception e){throw new PublishFailed();}
    jdbc.update("update outbox set published_at=now(),lease_token=null,lease_until=null where id=? and lease_token=?",c.id,c.token);
   });
  } catch(RuntimeException e) {
   retries.increment();
   // Do not persist/log broker exception text, event payload, PII, or credentials.
   tx.executeWithoutResult(s->jdbc.update("update outbox set attempts=attempts+1,available_at=now()+interval '5 seconds',lease_token=null,lease_until=null where id=? and lease_token=? and published_at is null",c.id,c.token));
  }
 }
 record Claim(long id,UUID token) {}
 static final class PublishFailed extends RuntimeException { PublishFailed(){super("Event delivery not confirmed");} }
}
