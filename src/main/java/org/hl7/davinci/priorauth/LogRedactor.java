package org.hl7.davinci.priorauth;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Helpers to keep credentials and patient identifiers out of log output
 * (HIPAA minimum necessary).
 */
public final class LogRedactor {

  public static final String REDACTED = "[REDACTED]";

  private static final Set<String> SECRET_KEYS = Set.of("token", "access_token", "refresh_token",
      "client_assertion", "authorization", "jwks", "password", "secret");
  private static final Set<String> PATIENT_KEYS = Set.of("patient");
  private static final Set<String> OMITTED_KEYS = Set.of("resource", "organization");

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final byte[] HASH_KEY = new byte[32];

  static {
    new SecureRandom().nextBytes(HASH_KEY);
  }

  private LogRedactor() {
  }

  /**
   * Replace a patient identifier with a keyed hash. The key is random per JVM, so
   * values correlate within a single run but cannot be reversed or joined across
   * runs.
   *
   * @param patient the patient identifier (or reference)
   * @return masked representation safe for logging
   */
  public static String maskPatient(Object patient) {
    if (patient == null)
      return "null";
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(HASH_KEY, HMAC_ALGORITHM));
      byte[] digest = mac.doFinal(patient.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder("patient#");
      for (int i = 0; i < 6; i++)
        hex.append(String.format("%02x", digest[i]));
      return hex.toString();
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      return REDACTED;
    }
  }

  /**
   * Render a column/value map for logging with secrets redacted, patient
   * identifiers masked and resource bodies omitted.
   *
   * @param map the map to render
   * @return a log-safe string
   */
  public static String redactMap(Map<String, ?> map) {
    if (map == null)
      return "null";
    StringJoiner joiner = new StringJoiner(", ", "{", "}");
    for (Map.Entry<String, ?> entry : map.entrySet()) {
      String key = entry.getKey();
      String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
      String value;
      if (SECRET_KEYS.contains(normalized))
        value = REDACTED;
      else if (PATIENT_KEYS.contains(normalized))
        value = maskPatient(entry.getValue());
      else if (OMITTED_KEYS.contains(normalized))
        value = "<omitted>";
      else
        value = String.valueOf(entry.getValue());
      joiner.add(key + "=" + value);
    }
    return joiner.toString();
  }

  /**
   * Render the /auth/token request parameters without the client_assertion.
   */
  public static String tokenRequest(String scope, String grantType, String clientAssertionType) {
    return "scope=" + scope + "&grant_type=" + grantType + "&client_assertion_type=" + clientAssertionType
        + "&client_assertion=" + REDACTED;
  }
}
