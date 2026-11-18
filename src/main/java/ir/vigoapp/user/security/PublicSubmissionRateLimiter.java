package ir.vigoapp.user.security;
import ir.vigoapp.user.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
/**
 * Fixed-window limiter for the anonymous submission endpoint.
 *
 * <p>State is in-process, so the limit applies per instance. That is sufficient
 * for the current single-replica deployment; a multi-replica deployment must move
 * this to a shared store instead of raising the limit. The map is swept so an
 * attacker rotating addresses cannot grow it without bound.
 */
@Component
public class PublicSubmissionRateLimiter {
 private static final int MAX_TRACKED_CLIENTS=10_000;
 private final int maxPerWindow;
 private final long windowSeconds;
 private final ConcurrentHashMap<String,Window> windows=new ConcurrentHashMap<>();
 private final AtomicLong lastSweep= new AtomicLong();
 public PublicSubmissionRateLimiter(@Value("${app.coach-applications.rate-limit.max-per-window:5}") int maxPerWindow,
   @Value("${app.coach-applications.rate-limit.window:PT15M}") Duration window) {
  if(maxPerWindow<1)throw new IllegalArgumentException("Rate limit must be positive");
  if(window.isNegative()||window.isZero())throw new IllegalArgumentException("Rate limit window must be positive");
  this.maxPerWindow=maxPerWindow;this.windowSeconds=Math.max(1,window.getSeconds());
 }
 public void requireAllowed(String clientKey) {
  long now=Instant.now().getEpochSecond();
  sweepIfNeeded(now);
  var current=windows.compute(clientKey,(key,existing)->
   existing==null||now-existing.windowStart>=windowSeconds?new Window(now,1):new Window(existing.windowStart,existing.count+1));
  if(current.count>maxPerWindow)throw new ApiException(429,"RATE_LIMITED",
   "Too many applications submitted; try again later","تعداد درخواست‌ها بیش از حد مجاز است؛ بعداً دوباره تلاش کنید");
 }
 private void sweepIfNeeded(long now) {
  if(windows.size()<MAX_TRACKED_CLIENTS)return;
  long previous=lastSweep.getAndSet(now);
  if(now-previous<windowSeconds)return;
  windows.entrySet().removeIf(entry->now-entry.getValue().windowStart>=windowSeconds);
 }
 private record Window(long windowStart,int count) {}
}
