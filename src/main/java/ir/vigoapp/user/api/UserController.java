package ir.vigoapp.user.api;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.service.ProfileService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
@RestController @RequestMapping("/api/v1")
public class UserController {
 private final ProfileService service;
 public UserController(ProfileService service){this.service=service;}
 private UUID id(Authentication a){return UUID.fromString(a.getName());}
 @GetMapping("/users/me") public Envelope<?> me(Authentication a){return Envelope.ok(service.get(id(a)));}
 @PutMapping("/users/me") public Envelope<?> update(Authentication a,@Valid @RequestBody Requests.Profile r){return Envelope.ok(service.update(id(a),r));}
 @GetMapping("/users") public Envelope<?> users(@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,@RequestParam(required=false) Role role,@RequestParam(required=false) AccountStatus status,@RequestParam(required=false) @Size(max=100) String q){return Envelope.ok(service.list(page,size,role,status,q));}
 @GetMapping("/users/{user_id}") public Envelope<?> user(@PathVariable UUID user_id){return Envelope.ok(service.get(user_id));}
 @PatchMapping("/users/{user_id}/status") public Envelope<?> status(Authentication a,@PathVariable UUID user_id,@Valid @RequestBody Requests.Status r){return Envelope.ok(service.status(id(a),user_id,r));}
 @PostMapping("/coaches/me/profile") @ResponseStatus(org.springframework.http.HttpStatus.CREATED) public Envelope<?> apply(Authentication a,@Valid @RequestBody Requests.Coach r){return Envelope.ok(service.apply(id(a),r));}
 @GetMapping("/coaches/me") public Envelope<?> own(Authentication a){return Envelope.ok(service.own(id(a)));}
 @PutMapping("/coaches/me") public Envelope<?> updateCoach(Authentication a,@Valid @RequestBody Requests.Coach r){return Envelope.ok(service.updateCoach(id(a),r));}
 @GetMapping("/coaches/{coach_id}") public Envelope<?> coach(@PathVariable UUID coach_id){return Envelope.ok(service.publicCoach(coach_id));}
 @GetMapping("/admin/coaches") public Envelope<?> applications(@RequestParam(defaultValue="0") @Min(0) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,@RequestParam(required=false) CoachStatus status){return Envelope.ok(service.listCoaches(page,size,status));}
 @GetMapping("/admin/coaches/{coach_id}") public Envelope<?> application(@PathVariable UUID coach_id){return Envelope.ok(service.adminCoach(coach_id));}
 @PostMapping("/admin/coaches/{coach_id}/approve") public Envelope<?> approve(Authentication a,@PathVariable UUID coach_id){return Envelope.ok(service.decide(id(a),coach_id,true,null));}
 @PostMapping("/admin/coaches/{coach_id}/reject") public Envelope<?> reject(Authentication a,@PathVariable UUID coach_id,@Valid @RequestBody Requests.Rejection r){return Envelope.ok(service.decide(id(a),coach_id,false,r.reason()));}
}
