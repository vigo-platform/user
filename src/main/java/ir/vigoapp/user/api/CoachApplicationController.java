package ir.vigoapp.user.api;
import lombok.RequiredArgsConstructor;
import ir.vigoapp.user.dto.Envelope;
import ir.vigoapp.user.dto.Requests;
import ir.vigoapp.user.security.PublicSubmissionRateLimiter;
import ir.vigoapp.user.service.CoachApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
/**
 * Anonymous endpoints. They are intentionally not the authenticated coach profile
 * routes: a visitor can apply here without registering, logging in or holding a token.
 */
@RestController @RequestMapping("/api/v1/coaches/applications")
@RequiredArgsConstructor
public class CoachApplicationController {
 private static final Logger log=LoggerFactory.getLogger(CoachApplicationController.class);
 private final CoachApplicationService service;
 private final PublicSubmissionRateLimiter limiter;
 @PostMapping @ResponseStatus(HttpStatus.CREATED)
 public Envelope<?> submit(@Valid @RequestBody Requests.Application body,HttpServletRequest request) {
  limiter.requireAllowed(clientKey(request));
  var result=service.submit(body);
  log.atInfo().addKeyValue("event","coach_application_received").addKeyValue("result","SUCCESS")
   .addKeyValue("application_id",result.get("applicationId")).log("Public coach application accepted for review.");
  return Envelope.ok(result);
 }
 @GetMapping("/{lookup_token}")
 public Envelope<?> status(@PathVariable("lookup_token") String lookupToken) {
  return Envelope.ok(service.status(lookupToken));
 }
 private static String clientKey(HttpServletRequest request) {
  // The gateway rewrites X-Forwarded-For from the socket address, but this
  // endpoint is also reachable directly, so the socket address is authoritative.
  return request.getRemoteAddr()==null?"unknown":request.getRemoteAddr();
 }
}
