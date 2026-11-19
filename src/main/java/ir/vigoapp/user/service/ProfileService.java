package ir.vigoapp.user.service;
import lombok.RequiredArgsConstructor;
import ir.vigoapp.user.api.*;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.dto.Requests;
import ir.vigoapp.user.dto.Views;
import ir.vigoapp.user.entity.CoachProfile;
import ir.vigoapp.user.entity.UserAccount;
import ir.vigoapp.user.outbox.OutboxWriter;
import ir.vigoapp.user.repository.CoachPlanRepository;
import ir.vigoapp.user.repository.CoachPlanRepository.ActivePlanPrice;
import ir.vigoapp.user.repository.CoachRepository;
import ir.vigoapp.user.repository.UserRepository;
import ir.vigoapp.user.service.plan.PublicCoachPlanService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
@Service @Transactional
@RequiredArgsConstructor
public class ProfileService {
 private static final Logger log=LoggerFactory.getLogger(ProfileService.class);
 private final UserRepository users;private final CoachRepository coaches;private final OutboxWriter events;private final ObjectMapper json;
 private final CoachPlanRepository plans;
 private final PublicCoachPlanService publicPlans;
 private UserAccount user(long id){return users.findById(id).orElseThrow(ApiException::notFound);}
 private UserAccount locked(long id){return users.lock(id).orElseThrow(ApiException::notFound);}
 private UserAccount activeLocked(long id){var u=locked(id);if(u.getStatus()!=AccountStatus.ACTIVE)throw ApiException.forbidden();return u;}
 private CoachProfile coach(long id){return coaches.findByUserId(id).orElseThrow(ApiException::notFound);}
 public Views.Profile get(long id){return Views.profile(user(id));}
 public Views.Profile update(long id,Requests.Profile r){var u=activeLocked(id);u.setDisplayName(r.displayName());u.setAvatarUrl(r.avatarUrl());u.setBirthDate(r.birthDate());u.setGender(r.gender());u.setUpdatedAt(Instant.now());return Views.profile(u);}
 public Views.Paged<Views.Profile> list(int page,int size,Role role,AccountStatus status,String q){
  Specification<UserAccount> spec=(root,query,cb)->{
   List<jakarta.persistence.criteria.Predicate> p=new ArrayList<>();
   if(role!=null)p.add(cb.equal(root.get("role"),role));if(status!=null)p.add(cb.equal(root.get("status"),status));
   if(q!=null&&!q.isBlank()){String term="%"+q.toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";p.add(cb.or(cb.like(cb.lower(root.get("displayName")),term,'\\'),cb.like(cb.lower(root.get("email")),term,'\\')));}
   return cb.and(p.toArray(jakarta.persistence.criteria.Predicate[]::new));};
  return Views.page(users.findAll(spec,PageRequest.of(page,size,Sort.by("createdAt").descending().and(Sort.by("id")))),Views::profile);
 }
 public Views.Profile status(long actor,long target,Requests.Status request){
  if(actor==target)throw new ApiException(400,"SELF_ACTION_NOT_ALLOWED","Cannot change own status","تغییر وضعیت خود مجاز نیست");
  var u=locked(target);
  if(u.getRole()==Role.ADMIN&&request.status()==AccountStatus.SUSPENDED)throw new ApiException(403,"ADMIN_PROTECTED","Administrator is protected","حساب مدیر محافظت شده است");
  if(u.getStatus()!=request.status()){
   var old=u.getStatus();u.setStatus(request.status());u.setUpdatedAt(Instant.now());
   Map<String,Object> data=new LinkedHashMap<>();data.put("user_id",u.getId());data.put("old_status",old);data.put("new_status",u.getStatus());data.put("reason",request.reason());data.put("changed_by",actor);
   events.emit(u,"UserStatusChanged",data);
   log.atInfo().addKeyValue("event","user_status_change_staged").addKeyValue("result","STAGED")
    .addKeyValue("actor_user_id",actor).addKeyValue("user_id",target).addKeyValue("old_status",old).addKeyValue("new_status",u.getStatus())
    .log("User account status change staged in the transaction.");
  }return Views.profile(u);
 }
 public Map<String,Object> apply(long id,Requests.Coach r){
  var u=activeLocked(id);if(u.getRole()!=Role.ATHLETE)throw ApiException.forbidden();
  if(coaches.existsById(u.getId()))throw ApiException.conflict("COACH_PROFILE_EXISTS");
  var c=new CoachProfile();c.setUser(u);fill(c,r);coaches.saveAndFlush(c);return Views.coach(c,true,json);
 }
 public Map<String,Object> own(long id){return Views.coach(coach(id),true,json);}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Map<String,Object> publicCoach(long id){
  var c=coach(id);var u=org.hibernate.Hibernate.unproxy(c.getUser(),UserAccount.class);
  if(c.getStatus()!=CoachStatus.APPROVED||u.getStatus()!=AccountStatus.ACTIVE||u.getRole()!=Role.COACH)throw ApiException.notFound();
  var price=plans.findCheapestActivePrices(List.of(id)).stream().findFirst().orElse(null);
  var view=publicCoachView(c,price);
  view.put("plans",publicPlans.summariesForPublicCoach(id));
  return view;
 }

 @Transactional(readOnly=true)
 public Views.Paged<Map<String,Object>> listPublicCoaches(int page,int size){
  var pageable=PageRequest.of(page,size,Sort.by("createdAt").descending().and(Sort.by("userId")));
  var result=coaches.findAllByStatusAndUser_StatusAndUser_Role(CoachStatus.APPROVED,AccountStatus.ACTIVE,Role.COACH,pageable);
  var coachIds=result.getContent().stream().map(CoachProfile::getUserId).toList();
  Map<Long,ActivePlanPrice> prices=coachIds.isEmpty()?Map.of():plans.findCheapestActivePrices(coachIds).stream()
   .collect(Collectors.toMap(ActivePlanPrice::getCoachId,Function.identity()));
  return Views.page(result,c->publicCoachView(c,prices.get(c.getUserId())));
 }

 private Map<String,Object> publicCoachView(CoachProfile coach,ActivePlanPrice price){
  var view=Views.coach(coach,false,json);
  view.put("has_active_plan",price!=null);
  view.put("price",price==null?null:price.getPrice());
  view.put("compare_at_price",price==null?null:price.getCompareAtPrice());
  view.put("currency",price==null?null:price.getCurrency());
  return view;
 }

 public Map<String,Object> adminCoach(long id){return Views.coach(coach(id),true,json);}
 public Map<String,Object> updateCoach(long id,Requests.Coach r){
  var u=activeLocked(id);if(u.getRole()!=Role.ATHLETE&&u.getRole()!=Role.COACH)throw ApiException.forbidden();
  var c=coach(id);fill(c,r);if(c.getStatus()==CoachStatus.REJECTED){c.setStatus(CoachStatus.PENDING);c.setRejectionReason(null);}return Views.coach(c,true,json);
 }
 public Views.Paged<Map<String,Object>> listCoaches(int page,int size,CoachStatus status){
  Specification<CoachProfile> spec=(root,q,cb)->status==null?cb.conjunction():cb.equal(root.get("status"),status);
  return Views.page(coaches.findAll(spec,PageRequest.of(page,size,Sort.by("createdAt").descending().and(Sort.by("userId")))),c->Views.coach(c,true,json));
 }
 public Map<String,Object> decide(long actor,long id,boolean approve,String reason){
  var u=org.hibernate.Hibernate.unproxy(locked(id),UserAccount.class);var c=coach(id);
  if(c.getStatus()!=CoachStatus.PENDING)throw ApiException.conflict("INVALID_STATE");
  if(u.getStatus()!=AccountStatus.ACTIVE||u.getRole()!=Role.ATHLETE)throw ApiException.conflict("INVALID_STATE");
  c.setStatus(approve?CoachStatus.APPROVED:CoachStatus.REJECTED);c.setRejectionReason(approve?null:reason);c.setUpdatedAt(Instant.now());
  Map<String,Object> data=new LinkedHashMap<>();data.put("coach_id",u.getId());data.put("reviewed_by",actor);if(!approve)data.put("reason",reason);
  events.emit(u,approve?"CoachApproved":"CoachRejected",data);
  if(approve){u.setRole(Role.COACH);u.setUpdatedAt(Instant.now());events.emit(u,"UserRoleChanged",Map.of("user_id",u.getId(),"old_role","ATHLETE","new_role","COACH","changed_by",actor));}
  return Views.coach(c,true,json);
 }
 private void fill(CoachProfile c,Requests.Coach r){
  c.setDisplayName(r.displayName());c.setHeadline(r.headline());c.setBio(r.bio());c.setSpecialties(json.writeValueAsString(r.specialties()));c.setExperienceYears(r.experienceYears());
  c.setCertifications(json.writeValueAsString(r.certifications()==null?List.of():r.certifications()));c.setInstagramHandle(r.instagramHandle());c.setResultImageUrls(json.writeValueAsString(r.resultImageUrls()==null?List.of():r.resultImageUrls()));c.setUpdatedAt(Instant.now());
 }
}
