package ir.vigoapp.user.api;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.*;
@RestControllerAdvice
public class Errors {
 public record FieldIssue(String field,String code,String message,String message_fa) {}
 @ExceptionHandler(ApiException.class) ResponseEntity<?> api(ApiException e) { return ResponseEntity.status(e.status).body(Envelope.error(e.code,e.getMessage(),e.persian,null)); }
 @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> validation(MethodArgumentNotValidException e) {
  var issues=e.getBindingResult().getFieldErrors().stream().map(f->new FieldIssue(f.getField(),"INVALID_FORMAT","Invalid field value","مقدار فیلد نامعتبر است")).toList();
  return ResponseEntity.badRequest().body(Envelope.error("VALIDATION_FAILED","Validation failed","اعتبارسنجی ناموفق بود",issues));
 }
 @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,HandlerMethodValidationException.class,ConstraintViolationException.class,IllegalArgumentException.class,org.springframework.web.bind.MissingServletRequestParameterException.class})
 ResponseEntity<?> bad(Exception e) { return ResponseEntity.badRequest().body(Envelope.error("VALIDATION_FAILED","Invalid request","درخواست نامعتبر است",null)); }
 @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> conflict() { return ResponseEntity.status(409).body(Envelope.error("INVALID_STATE","Operation conflicts with current state","عملیات با وضعیت فعلی سازگار نیست",null)); }
 @ExceptionHandler(NoResourceFoundException.class) ResponseEntity<?> missing() { return ResponseEntity.status(404).body(Envelope.error("NOT_FOUND","Resource not found","منبع یافت نشد",null)); }
 @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class) ResponseEntity<?> method() { return ResponseEntity.status(405).body(Envelope.error("METHOD_NOT_ALLOWED","Method not allowed","روش مجاز نیست",null)); }
 @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected() { return ResponseEntity.internalServerError().body(Envelope.error("INTERNAL_SERVER_ERROR","Internal server error","خطای داخلی سرور",null)); }
}
