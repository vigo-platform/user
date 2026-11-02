package ir.vigoapp.user.api;
import java.time.Instant;
public record Envelope<T>(boolean success,String code,String message,String message_fa,T data,Instant timestamp) {
 public static <T> Envelope<T> ok(T data) { return new Envelope<>(true,"SUCCESS","Operation successful","عملیات موفق بود",data,Instant.now()); }
 public static <T> Envelope<T> error(String code,String en,String fa,T data) { return new Envelope<>(false,code,en,fa,data,Instant.now()); }
}
