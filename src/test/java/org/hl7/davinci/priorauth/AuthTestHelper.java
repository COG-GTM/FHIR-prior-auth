package org.hl7.davinci.priorauth;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.hl7.davinci.priorauth.Database.Table;

/**
 * Helper for tests that call endpoints protected by a bearer access token.
 */
public class AuthTestHelper {

  /**
   * Register a client with a freshly issued access token.
   *
   * @return the client id
   */
  public static String registerClient(String token) {
    String clientId = UUID.randomUUID().toString();
    Map<String, Object> dataMap = new HashMap<>();
    dataMap.put("id", clientId);
    dataMap.put("token", token);
    App.getDB().write(Table.CLIENT, dataMap);
    return clientId;
  }

  public static String newToken() {
    return UUID.randomUUID().toString();
  }

  public static void deleteClient(String clientId) {
    App.getDB().delete(Table.CLIENT, clientId);
  }

  /**
   * @return the outcome code of the most recent AuditEvent for the referenced resource
   */
  public static String latestAuditOutcome(String referenceUrl) {
    return App.getDB().readString(Table.AUDIT, Collections.singletonMap("what", referenceUrl), "outcome");
  }
}
