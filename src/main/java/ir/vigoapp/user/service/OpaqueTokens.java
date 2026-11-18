package ir.vigoapp.user.service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
/**
 * Opaque bearer tokens for anonymous flows. Only digests are stored, so a database
 * read cannot replay a status link or an invitation.
 */
public final class OpaqueTokens {
 private static final SecureRandom RANDOM=new SecureRandom();
 private static final Pattern TOKEN=Pattern.compile("[A-Za-z0-9_-]{43}");
 private OpaqueTokens() {}
 /** 256 bits of entropy, base64url without padding to keep it URL and log safe. */
 public static String newToken() {
  byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);
  return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
 }
 public static boolean looksValid(String token) { return token!=null&&TOKEN.matcher(token).matches(); }
 public static String digest(String token) {
  try {
   return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
  } catch(NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable",e); }
 }
}
