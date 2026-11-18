package ir.vigoapp.user.api;
import ir.vigoapp.user.domain.CoachApplicationStatus;
import ir.vigoapp.user.dto.Envelope;
import ir.vigoapp.user.dto.Requests;
import ir.vigoapp.user.service.CoachApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
/** Admin review of anonymous applications. Authorization is enforced by the filter chain. */
@RestController @RequestMapping("/api/v1/admin/coaches/applications")
public class AdminCoachApplicationController {
 private static final Logger log=LoggerFactory.getLogger(AdminCoachApplicationController.class);
 private final CoachApplicationService service;
 public AdminCoachApplicationController(CoachApplicationService service) { this.service=service; }
 private UUID id(Authentication a){return UUID.fromString(a.getName());}
 @GetMapping
 public Envelope<?> list(@RequestParam(defaultValue="0") @Min(0) int page,
   @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
   @RequestParam(required=false) CoachApplicationStatus status) {
  return Envelope.ok(service.list(page,size,status));
 }
 @GetMapping("/{application_id}")
 public Envelope<?> get(@PathVariable UUID application_id) { return Envelope.ok(service.get(application_id)); }
 @PostMapping("/{application_id}/approve")
 public Envelope<?> approve(Authentication a,@PathVariable UUID application_id) {
  UUID actor=id(a);var result=service.approve(actor,application_id);
  log.atInfo().addKeyValue("event","coach_application_approved").addKeyValue("result","SUCCESS")
   .addKeyValue("application_id",application_id).addKeyValue("actor_public_id",actor)
   .log("Coach application approved; invitation issued.");
  return Envelope.ok(result);
 }
 @PostMapping("/{application_id}/reject")
 public Envelope<?> reject(Authentication a,@PathVariable UUID application_id,@Valid @RequestBody Requests.Rejection r) {
  UUID actor=id(a);var result=service.reject(actor,application_id,r.reason());
  log.atInfo().addKeyValue("event","coach_application_rejected").addKeyValue("result","SUCCESS")
   .addKeyValue("application_id",application_id).addKeyValue("actor_public_id",actor).log("Coach application rejected.");
  return Envelope.ok(result);
 }
}
