package ir.vigoapp.user;
import ir.vigoapp.user.api.*;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.dto.Requests;
import ir.vigoapp.user.entity.CoachProfile;
import ir.vigoapp.user.entity.UserAccount;
import ir.vigoapp.user.outbox.OutboxWriter;
import ir.vigoapp.user.repository.CoachRepository;
import ir.vigoapp.user.repository.UserRepository;
import ir.vigoapp.user.service.ProfileService;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class ProfileServiceTest {
 UserRepository users=mock(UserRepository.class);CoachRepository coaches=mock(CoachRepository.class);OutboxWriter events=mock(OutboxWriter.class);
 ProfileService service=new ProfileService(users,coaches,events,JsonMapper.builder().findAndAddModules().build());
 UserAccount u;CoachProfile c;UUID admin=UUID.randomUUID();
 @BeforeEach void init(){u=new UserAccount();u.setId(1L);u.setDisplayName("Test Athlete");c=new CoachProfile();c.setUser(u);c.setUserId(u.getId());c.setDisplayName("Test Coach");c.setSpecialties("[\"fitness\"]");c.setBio("A synthetic biography long enough.");
  when(users.lock(u.getPublicId())).thenReturn(Optional.of(u));when(coaches.findByUser_PublicId(u.getPublicId())).thenReturn(Optional.of(c));}
 @Test void approvalHasTwoOrderedEventsAndCannotRepeat(){
  var result=service.decide(admin,u.getPublicId(),true,null);assertThat(u.getRole()).isEqualTo(Role.COACH);assertThat(c.getStatus()).isEqualTo(CoachStatus.APPROVED);assertThat(result.get("status")).isEqualTo(CoachStatus.APPROVED);
  var order=inOrder(events);order.verify(events).emit(eq(u),eq("CoachApproved"),anyMap());order.verify(events).emit(eq(u),eq("UserRoleChanged"),anyMap());
  assertThatThrownBy(()->service.decide(admin,u.getPublicId(),true,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("INVALID_STATE"));
 }
 @Test void rejectionAndResubmitPreserveAthleteRole(){
  service.decide(admin,u.getPublicId(),false,"Needs valid certification");assertThat(u.getRole()).isEqualTo(Role.ATHLETE);assertThat(c.getStatus()).isEqualTo(CoachStatus.REJECTED);
  service.updateCoach(u.getPublicId(),request());assertThat(c.getStatus()).isEqualTo(CoachStatus.PENDING);assertThat(c.getRejectionReason()).isNull();
 }
 @Test void publicViewHasNoPrivateReviewDataAndRequiresActiveApproved(){
  assertThatThrownBy(()->service.publicCoach(u.getPublicId())).isInstanceOf(ApiException.class);
  c.setStatus(CoachStatus.APPROVED);u.setRole(Role.COACH);
  var view=service.publicCoach(u.getPublicId());assertThat(view).doesNotContainKeys("status","rejection_reason");assertThat(view.get("min_plan_price")).isNull();assertThat(view.get("active_athletes_count")).isEqualTo(0);
  u.setStatus(AccountStatus.SUSPENDED);assertThatThrownBy(()->service.publicCoach(u.getPublicId())).isInstanceOf(ApiException.class);
 }
 @Test void selfAndAdministratorSuspensionAreProtected(){
  assertThatThrownBy(()->service.status(u.getPublicId(),u.getPublicId(),new Requests.Status(AccountStatus.SUSPENDED,null))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("SELF_ACTION_NOT_ALLOWED"));
  u.setRole(Role.ADMIN);assertThatThrownBy(()->service.status(admin,u.getPublicId(),new Requests.Status(AccountStatus.SUSPENDED,null))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("ADMIN_PROTECTED"));
  verifyNoInteractions(events);
 }
 @Test void duplicateApplicationIsConflictAndRoleCannotEscalate(){
  when(coaches.existsById(1L)).thenReturn(true);assertThatThrownBy(()->service.apply(u.getPublicId(),request())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("COACH_PROFILE_EXISTS"));
  u.setRole(Role.ADMIN);assertThatThrownBy(()->service.apply(u.getPublicId(),request())).isInstanceOf(ApiException.class);
 }
 @Test void noOpStatusDoesNotEmitAndSuspensionEmits(){
  service.status(admin,u.getPublicId(),new Requests.Status(AccountStatus.ACTIVE,null));verifyNoInteractions(events);
  service.status(admin,u.getPublicId(),new Requests.Status(AccountStatus.SUSPENDED,"Moderation"));verify(events).emit(eq(u),eq("UserStatusChanged"),anyMap());
 }
 static Requests.Coach request(){return new Requests.Coach("Test Coach",null,"A synthetic biography long enough.",List.of("fitness"),3,List.of(),null,List.of());}
}
