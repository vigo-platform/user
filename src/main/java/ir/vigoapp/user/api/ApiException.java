package ir.vigoapp.user.api;
public class ApiException extends RuntimeException {
 public final int status; public final String code; public final String persian;
 public ApiException(int status,String code,String english,String persian) { super(english);this.status=status;this.code=code;this.persian=persian; }
 public static ApiException notFound() { return new ApiException(404,"NOT_FOUND","Resource not found","منبع یافت نشد"); }
 public static ApiException forbidden() { return new ApiException(403,"FORBIDDEN","Access denied","دسترسی مجاز نیست"); }
 public static ApiException conflict(String code) { return new ApiException(409,code,"Operation conflicts with current state","عملیات با وضعیت فعلی سازگار نیست"); }
}
