package ir.vigoapp.user.outbox;
import ir.vigoapp.user.domain.UserAccount;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
@Component
public class OutboxWriter {
 private final JdbcTemplate jdbc; private final ObjectMapper json;
 public OutboxWriter(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}
 // Caller holds the aggregate user row lock (or owns a newly inserted user) in this transaction.
 public void emit(UserAccount user,String type,Map<String,Object> data) {
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Outbox requires transaction");
  long sequence=++user.aggregateVersion; Instant now=Instant.now(); UUID id=UUID.randomUUID();
  Map<String,Object> event=new LinkedHashMap<>();
  event.put("event_id",id);event.put("event_type",type);event.put("event_version",1);event.put("occurred_at",now.toString());
  event.put("producer","user-service");event.put("aggregate_id",user.publicId);event.put("aggregate_version",sequence);event.put("data",data);
  jdbc.update("insert into outbox(event_id,aggregate_id,aggregate_version,payload,occurred_at) values (?,?,?,?,?)",id,user.publicId,sequence,json.writeValueAsString(event),java.sql.Timestamp.from(now));
 }
}
