package ir.vigoapp.user.service;
import java.util.Locale;
public final class Identifiers {
 private Identifiers() {}
 public static String email(String value) {
  if(value==null)throw new IllegalArgumentException("Email required");
  String v=value.strip().toLowerCase(Locale.ROOT);
  if(v.length()>254||!v.matches("^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?[.])+[a-z]{2,63}$"))throw new IllegalArgumentException("Invalid email");
  return v;
 }
 public static String phone(String value) {
  if(value==null)throw new IllegalArgumentException("Phone required");
  String v=value.strip();
  if(!v.matches("^[+][1-9][0-9]{7,14}$"))throw new IllegalArgumentException("Invalid E.164 phone");
  return v;
 }
 public static String name(String value) { if(value==null||value.isBlank()||value.strip().length()>60)throw new IllegalArgumentException("Invalid name");return value.strip(); }
 public static void hash(String value) { if(value==null||value.isBlank()||value.length()>2048)throw new IllegalArgumentException("Invalid encoded hash"); }
 public static void id(long id) {if(id<=0)throw new IllegalArgumentException("Invalid user ID");}
}
