package ir.vigoapp.user.service;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.api.*;
import ir.vigoapp.user.outbox.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
@Service @Transactional
public class ProfileService {
 private final UserRepository users;private final CoachRepository coaches;private final OutboxWriter events;private final ObjectMapper json;
 public ProfileService(UserRepository users,CoachRepository coaches,OutboxWriter events,ObjectMapper json){this.users=users;this.coaches=coaches;this.events=events;this.json=json;}
 private UserAccount user(UUID id){return users.findByPublicId(id).orElseThrow(ApiException::notFound);}
 private UserAccount locked(UUID id){return users.lock(id).orElseThrow(ApiException::notFound);}
 private UserAccount activeLocked(UUID id){var u=locked(id);if(u.status!=AccountStatus.ACTIVE)throw ApiException.forbidden();return u;}
 private CoachProfile coach(UUID id){return coaches.findByUser_PublicId(id).orElseThrow(ApiException::notFound);}
 public Views.Profile get(UUID id){return Views.profile(user(id));}
 public Views.Profile update(UUID id,Requests.Profile r){var u=activeLocked(id);u.displayName=r.display_name();u.avatarUrl=r.avatar_url();u.birthDate=r.birth_date();u.gender=r.gender();u.updatedAt=Instant.now();return Views.profile(u);}
 public Views.Paged<Views.Profile> list(int page,int size,Role role,AccountStatus status,String q){
  Specification<UserAccount> spec=(root,query,cb)->{
   List<jakarta.persistence.criteria.Predicate> p=new ArrayList<>();
   if(role!=null)p.add(cb.equal(root.get("role"),role));if(status!=null)p.add(cb.equal(root.get("status"),status));
   if(q!=null&&!q.isBlank()){String term="%"+q.toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";p.add(cb.or(cb.like(cb.lower(root.get("displayName")),term,'\\'),cb.like(cb.lower(root.get("email")),term,'\\')));}
   return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));};
  return Views.page(users.findAll(spec,PageRequest.of(page,size,Sort.by("createdAt").descending().and(Sort.by("id")))),Views::profile);
 }
 public Views.Profile status(UUID actor,UUID target,Requests.Status request){
  if(actor.equals(target))throw new ApiException(400,"SELF_ACTION_NOT_ALLOWED","Cannot change own status","تغییر وضعیت خود مجاز نیست");
  var u=locked(target);
  if(u.role==Role.ADMIN&&request.status()==AccountStatus.SUSPENDED)throw new ApiException(403,"ADMIN_PROTECTED","Administrator is protected","حساب مدیر محافظت شده است");
  if(u.status!=request.status()){
   var old=u.status;u.status=request.status();u.updatedAt=Instant.now();
   Map<String,Object> data=new LinkedHashMap<>();data.put("user_id",u.publicId);data.put("old_status",old);data.put("new_status",u.status);data.put("reason",request.reason());data.put("changed_by",actor);
   events.emit(u,"UserStatusChanged",data);
  }return Views.profile(u);
 }
 public Map<String,Object> apply(UUID id,Requests.Coach r){
  var u=activeLocked(id);if(u.role!=Role.ATHLETE)throw ApiException.forbidden();
  if(coaches.existsById(u.id))throw ApiException.conflict("COACH_PROFILE_EXISTS");
  var c=new CoachProfile();c.user=u;fill(c,r);coaches.saveAndFlush(c);return Views.coach(c,true,json);
 }
 public Map<String,Object> own(UUID id){return Views.coach(coach(id),true,json);}
 public Map<String,Object> publicCoach(UUID id){var c=coach(id);if(c.status!=CoachStatus.APPROVED||c.user.status!=AccountStatus.ACTIVE||c.user.role!=Role.COACH)throw ApiException.notFound();return Views.coach(c,false,json);}
 public Map<String,Object> adminCoach(UUID id){return Views.coach(coach(id),true,json);}
 public Map<String,Object> updateCoach(UUID id,Requests.Coach r){
  var u=activeLocked(id);if(u.role!=Role.ATHLETE&&u.role!=Role.COACH)throw ApiException.forbidden();
  var c=coach(id);fill(c,r);if(c.status==CoachStatus.REJECTED){c.status=CoachStatus.PENDING;c.rejectionReason=null;}return Views.coach(c,true,json);
 }
 public Views.Paged<Map<String,Object>> listCoaches(int page,int size,CoachStatus status){
  Specification<CoachProfile> spec=(root,q,cb)->status==null?cb.conjunction():cb.equal(root.get("status"),status);
  return Views.page(coaches.findAll(spec,PageRequest.of(page,size,Sort.by("createdAt").descending().and(Sort.by("userId")))),c->Views.coach(c,true,json));
 }
 public Map<String,Object> decide(UUID actor,UUID id,boolean approve,String reason){
  var u=locked(id);var c=coach(id);
  if(c.status!=CoachStatus.PENDING)throw ApiException.conflict("INVALID_STATE");
  if(u.status!=AccountStatus.ACTIVE||u.role!=Role.ATHLETE)throw ApiException.conflict("INVALID_STATE");
  c.status=approve?CoachStatus.APPROVED:CoachStatus.REJECTED;c.rejectionReason=approve?null:reason;c.updatedAt=Instant.now();
  Map<String,Object> data=new LinkedHashMap<>();data.put("coach_id",u.publicId);data.put("reviewed_by",actor);if(!approve)data.put("reason",reason);
  events.emit(u,approve?"CoachApproved":"CoachRejected",data);
  if(approve){u.role=Role.COACH;u.updatedAt=Instant.now();events.emit(u,"UserRoleChanged",Map.of("user_id",u.publicId,"old_role","ATHLETE","new_role","COACH","changed_by",actor));}
  return Views.coach(c,true,json);
 }
 private void fill(CoachProfile c,Requests.Coach r){
  c.displayName=r.display_name();c.headline=r.headline();c.bio=r.bio();c.specialties=json.writeValueAsString(r.specialties());c.experienceYears=r.experience_years();
  c.certifications=json.writeValueAsString(r.certifications()==null?List.of():r.certifications());c.instagramHandle=r.instagram_handle();c.resultImageUrls=json.writeValueAsString(r.result_image_urls()==null?List.of():r.result_image_urls());c.updatedAt=Instant.now();
 }
}
