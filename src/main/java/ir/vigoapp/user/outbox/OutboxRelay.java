package ir.vigoapp.user.outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.*;
import java.util.concurrent.TimeUnit;
@Component @ConditionalOnProperty(name="app.outbox.enabled",havingValue="true",matchIfMissing=true)
public class OutboxRelay {
 private static final Logger log=LoggerFactory.getLogger(OutboxRelay.class);
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
   UUID eventId=tx.execute(s->{
    var rows=jdbc.queryForList("select event_id,aggregate_id,payload from outbox where id=? and lease_token=? and published_at is null for update",c.id,c.token);
    if(rows.isEmpty())return null;
    var row=rows.getFirst();
    try {
     // Wait for the producer terminal outcome (delivery.timeout.ms=30000). An interrupt
     // must not release the aggregate head while the original send is still in flight.
     kafka.send("user.events",row.get("aggregate_id").toString(),row.get("payload").toString()).join();
    } catch(Exception e){throw new PublishFailed(NestedExceptionUtils.getMostSpecificCause(e).getClass().getSimpleName());}
    jdbc.update("update outbox set published_at=now(),lease_token=null,lease_until=null where id=? and lease_token=?",c.id,c.token);
    return (UUID)row.get("event_id");
   });
   if(eventId!=null)log.atDebug().addKeyValue("event","outbox_event_published").addKeyValue("result","SUCCESS")
    .addKeyValue("event_id",eventId).addKeyValue("outbox_id",c.id).addKeyValue("topic","user.events").log("User event published to Kafka.");
  } catch(RuntimeException e) {
   retries.increment();
   // Do not persist/log broker exception text, event payload, PII, or credentials.
   var retry=tx.execute(s->jdbc.queryForList("update outbox set attempts=attempts+1,available_at=now()+interval '5 seconds',lease_token=null,lease_until=null where id=? and lease_token=? and published_at is null returning event_id,attempts",
    c.id,c.token).stream().findFirst().orElse(null));
   if(retry!=null){
    int attempts=(Integer)retry.get("attempts");
    if(attempts==1||attempts%12==0)log.atWarn().addKeyValue("event","outbox_publish_retry_scheduled").addKeyValue("result","RETRY")
     .addKeyValue("event_id",retry.get("event_id")).addKeyValue("outbox_id",c.id).addKeyValue("attempts",attempts)
     .addKeyValue("stage",e instanceof PublishFailed?"kafka_publish":"outbox_store")
     .addKeyValue("reason",e instanceof PublishFailed failure?failure.reason:e.getClass().getSimpleName())
     .log("User event delivery will be retried.");
   }
  }
 }
 record Claim(long id,UUID token) {}
 static final class PublishFailed extends RuntimeException {
  final String reason;
  PublishFailed(String reason){super("Event delivery not confirmed");this.reason=reason;}
 }
}
