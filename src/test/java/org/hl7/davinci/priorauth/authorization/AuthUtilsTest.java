package org.hl7.davinci.priorauth.authorization;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.hl7.davinci.priorauth.App;
import org.hl7.davinci.priorauth.Database.Table;
import org.hl7.fhir.r4.model.Organization;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class AuthUtilsTest {

  private static final String CLIENT_ID = "em-171-test-" + UUID.randomUUID();

  @BeforeClass
  public static void setupClass() {
    App.initializeAppDB();

    Organization organization = new Organization();
    organization.setId(CLIENT_ID);
    organization.setName("EM-171 Test Client");
    Map<String, Object> dataMap = new HashMap<>();
    dataMap.put("id", CLIENT_ID);
    dataMap.put("jwks", null);
    dataMap.put("jwks_url", null);
    dataMap.put("token", null);
    dataMap.put("organization", organization);
    Assert.assertTrue(App.getDB().write(Table.CLIENT, dataMap));
  }

  private static MockHttpServletRequest bearer(String token) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    return request;
  }

  private static String newToken() {
    return "plain-" + UUID.randomUUID().toString().replace("-", "");
  }

  @Test
  public void testHashTokenIsSha256Hex() {
    Assert.assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        AuthUtils.hashToken("abc"));
  }

  @Test
  public void testStoredTokenIsHashedAndValidates() {
    String token = newToken();
    Assert.assertTrue(AuthUtils.storeAccessToken(CLIENT_ID, token));

    String stored = App.getDB().readString(Table.CLIENT, Collections.singletonMap("id", CLIENT_ID), "token");
    Assert.assertEquals(AuthUtils.hashToken(token), stored);
    Assert.assertNotEquals(token, stored);
    Assert.assertNull(App.getDB().readString(Table.CLIENT, Collections.singletonMap("token", token), "id"));

    Assert.assertTrue(AuthUtils.validateAccessToken(bearer(token)));
    Assert.assertEquals(CLIENT_ID, AuthUtils.getClientId(bearer(token)));
  }

  @Test
  public void testInvalidTokenAndStoredHashAreRejected() {
    Assume.assumeFalse("true".equalsIgnoreCase(System.getenv("BYPASS_AUTH")));
    String token = newToken();
    Assert.assertTrue(AuthUtils.storeAccessToken(CLIENT_ID, token));

    Assert.assertFalse(AuthUtils.validateAccessToken(bearer(newToken())));
    // The stored hash itself must not be usable as a bearer token
    Assert.assertFalse(AuthUtils.validateAccessToken(bearer(AuthUtils.hashToken(token))));
    Assert.assertFalse(AuthUtils.validateAccessToken(new MockHttpServletRequest()));
  }

  @Test
  public void testClientTableContainsNoPlaintextTokens() {
    String token = newToken();
    Assert.assertTrue(AuthUtils.storeAccessToken(CLIENT_ID, token));

    String clientTable = App.getDB().runQuery("SELECT * FROM Client", true, false);
    Assert.assertTrue(clientTable.contains(AuthUtils.hashToken(token)));
    Assert.assertFalse(clientTable.contains(token));
    // Seeded client tokens are hashed too
    Assert.assertFalse(clientTable.contains("Y3YWq2l08kvFqy50fQJY"));
  }
}
