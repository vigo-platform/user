package ir.vigoapp.user.outbox;
import lombok.RequiredArgsConstructor;
import ir.vigoapp.user.entity.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
@Component
@RequiredArgsConstructor
public class OutboxWriter {
 private static final Logger log=LoggerFactory.getLogger(OutboxWriter.class);
 private final JdbcTemplate jdbc; private final ObjectMapper json;
 // Caller holds the aggregate user row lock (or owns a newly inserted user) in this transaction.
 public void emit(UserAccount user,String type,Map<String,Object> data) {
  emit(user.getPublicId(),user.nextAggregateVersion(),type,data);
 }
 /**
  * Emits an event for an aggregate that is not a user account (for example an
  * anonymous coach application). The caller supplies the next aggregate version
  * under its own row lock, so per-aggregate ordering still holds in the relay.
  */
 public void emit(UUID aggregateId,long aggregateVersion,String type,Map<String,Object> data) {
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Outbox requires transaction");
  if(aggregateVersion<1)throw new IllegalArgumentException("Aggregate version must be positive");
  Instant now=Instant.now(); UUID id=UUID.randomUUID();
  Map<String,Object> event=new LinkedHashMap<>();
  event.put("event_id",id);event.put("event_type",type);event.put("event_version",1);event.put("occurred_at",now.toString());
  event.put("producer","user-service");event.put("aggregate_id",aggregateId);event.put("aggregate_version",aggregateVersion);event.put("data",data);
  jdbc.update("insert into outbox(event_id,aggregate_id,aggregate_version,payload,occurred_at) values (?,?,?,?,?)",id,aggregateId,aggregateVersion,json.writeValueAsString(event),java.sql.Timestamp.from(now));
  log.atDebug().addKeyValue("event","outbox_event_staged").addKeyValue("event_id",id).addKeyValue("event_type",type)
   .addKeyValue("aggregate_id",aggregateId).addKeyValue("aggregate_version",aggregateVersion).log("User event staged in the outbox transaction.");
 }
}
