package ir.vigoapp.user;
import ir.vigoapp.user.api.*;
import ir.vigoapp.user.domain.*;
import ir.vigoapp.user.service.ProfileService;
import ir.vigoapp.user.outbox.OutboxWriter;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class ProfileServiceTest {
 UserRepository users=mock(UserRepository.class);CoachRepository coaches=mock(CoachRepository.class);OutboxWriter events=mock(OutboxWriter.class);
 ProfileService service=new ProfileService(users,coaches,events,JsonMapper.builder().findAndAddModules().build());
 UserAccount u;CoachProfile c;UUID admin=UUID.randomUUID();
 @BeforeEach void init(){u=new UserAccount();u.id=1L;u.displayName="Test Athlete";c=new CoachProfile();c.user=u;c.userId=u.id;c.displayName="Test Coach";c.specialties="[\"fitness\"]";c.bio="A synthetic biography long enough.";
  when(users.lock(u.publicId)).thenReturn(Optional.of(u));when(coaches.findByUser_PublicId(u.publicId)).thenReturn(Optional.of(c));}
 @Test void approvalHasTwoOrderedEventsAndCannotRepeat(){
  var result=service.decide(admin,u.publicId,true,null);assertThat(u.role).isEqualTo(Role.COACH);assertThat(c.status).isEqualTo(CoachStatus.APPROVED);assertThat(result.get("status")).isEqualTo(CoachStatus.APPROVED);
  var order=inOrder(events);order.verify(events).emit(eq(u),eq("CoachApproved"),anyMap());order.verify(events).emit(eq(u),eq("UserRoleChanged"),anyMap());
  assertThatThrownBy(()->service.decide(admin,u.publicId,true,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("INVALID_STATE"));
 }
 @Test void rejectionAndResubmitPreserveAthleteRole(){
  service.decide(admin,u.publicId,false,"Needs valid certification");assertThat(u.role).isEqualTo(Role.ATHLETE);assertThat(c.status).isEqualTo(CoachStatus.REJECTED);
  service.updateCoach(u.publicId,request());assertThat(c.status).isEqualTo(CoachStatus.PENDING);assertThat(c.rejectionReason).isNull();
 }
 @Test void publicViewHasNoPrivateReviewDataAndRequiresActiveApproved(){
  assertThatThrownBy(()->service.publicCoach(u.publicId)).isInstanceOf(ApiException.class);
  c.status=CoachStatus.APPROVED;u.role=Role.COACH;
  var view=service.publicCoach(u.publicId);assertThat(view).doesNotContainKeys("status","rejection_reason");assertThat(view.get("min_plan_price")).isNull();assertThat(view.get("active_athletes_count")).isEqualTo(0);
  u.status=AccountStatus.SUSPENDED;assertThatThrownBy(()->service.publicCoach(u.publicId)).isInstanceOf(ApiException.class);
 }
 @Test void selfAndAdministratorSuspensionAreProtected(){
  assertThatThrownBy(()->service.status(u.publicId,u.publicId,new Requests.Status(AccountStatus.SUSPENDED,null))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("SELF_ACTION_NOT_ALLOWED"));
  u.role=Role.ADMIN;assertThatThrownBy(()->service.status(admin,u.publicId,new Requests.Status(AccountStatus.SUSPENDED,null))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("ADMIN_PROTECTED"));
  verifyNoInteractions(events);
 }
 @Test void duplicateApplicationIsConflictAndRoleCannotEscalate(){
  when(coaches.existsById(1L)).thenReturn(true);assertThatThrownBy(()->service.apply(u.publicId,request())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("COACH_PROFILE_EXISTS"));
  u.role=Role.ADMIN;assertThatThrownBy(()->service.apply(u.publicId,request())).isInstanceOf(ApiException.class);
 }
 @Test void noOpStatusDoesNotEmitAndSuspensionEmits(){
  service.status(admin,u.publicId,new Requests.Status(AccountStatus.ACTIVE,null));verifyNoInteractions(events);
  service.status(admin,u.publicId,new Requests.Status(AccountStatus.SUSPENDED,"Moderation"));verify(events).emit(eq(u),eq("UserStatusChanged"),anyMap());
 }
 static Requests.Coach request(){return new Requests.Coach("Test Coach",null,"A synthetic biography long enough.",List.of("fitness"),3,List.of(),null,List.of());}
}
