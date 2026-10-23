package org.hl7.davinci.priorauth;

import java.math.BigInteger;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import org.hl7.davinci.priorauth.Database.Table;
import org.hl7.davinci.priorauth.authorization.AuthUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * EM-170: SMART scopes are granted per token and enforced on FHIR operations.
 */
@RunWith(SpringRunner.class)
@TestPropertySource(properties = "server.servlet.contextPath=/fhir")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class AuthScopeTest {

  private static final String KID = "em170-test-kid";

  @Autowired
  private WebApplicationContext wac;

  private static KeyPair keyPair;
  private static String completeClaim;
  private static final List<String> clientIds = new ArrayList<>();
  private static final List<String> claimIds = new ArrayList<>();

  @BeforeClass
  public static void setup() throws Exception {
    App.initializeAppDB();
    completeClaim = new String(Files.readAllBytes(Paths.get("src/test/resources/bundle-prior-auth.json")));
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keyPair = generator.generateKeyPair();
  }

  @AfterClass
  public static void cleanup() {
    for (String id : clientIds)
      App.getDB().delete(Table.CLIENT, id);
    for (String id : claimIds) {
      App.getDB().delete(Table.BUNDLE, id, "pat013");
      App.getDB().delete(Table.CLAIM, id, "pat013");
      App.getDB().delete(Table.CLAIM_RESPONSE, id, "pat013");
    }
  }

  @Test
  public void scopeMatching() {
    Assert.assertTrue(AuthUtils.scopeSatisfies("system/Claim.write", "system/Claim.write"));
    Assert.assertTrue(AuthUtils.scopeSatisfies("system/*.read system/*.write", "system/Claim.write"));
    Assert.assertTrue(AuthUtils.scopeSatisfies("system/Claim.*", "system/Claim.write"));
    Assert.assertTrue(AuthUtils.scopeSatisfies("system/*.read", "system/ClaimResponse.read"));
    Assert.assertFalse(AuthUtils.scopeSatisfies("system/*.read", "system/Claim.write"));
    Assert.assertFalse(AuthUtils.scopeSatisfies("system/ClaimResponse.write", "system/Claim.write"));
    Assert.assertFalse(AuthUtils.scopeSatisfies("patient/Claim.write", "system/Claim.write"));
    Assert.assertFalse(AuthUtils.scopeSatisfies("offline_access", "system/Claim.write"));
    Assert.assertFalse(AuthUtils.scopeSatisfies(null, "system/Claim.write"));
  }

  @Test
  public void unsupportedScopeIsRejected() throws Exception {
    String clientId = registerClient();
    mockMvc().perform(tokenRequest(clientId, "system/*.read system/Patient.write"))
        .andExpect(MockMvcResultMatchers.status().isBadRequest())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error").value("invalid_scope"));
    Assert.assertNull(App.getDB().readString(Table.CLIENT, Collections.singletonMap("id", clientId), "token"));
  }

  @Test
  public void submitWithoutTokenIsUnauthorized() throws Exception {
    mockMvc().perform(submitRequest(null)).andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  public void readOnlyTokenIsForbiddenOnSubmit() throws Exception {
    String token = requestToken(registerClient(), "system/*.read");
    mockMvc().perform(submitRequest(token)).andExpect(MockMvcResultMatchers.status().isForbidden())
        .andExpect(MockMvcResultMatchers.jsonPath("$.error").value("insufficient_scope"));
  }

  @Test
  public void writeTokenIsAcceptedOnSubmit() throws Exception {
    String token = requestToken(registerClient(), "system/Claim.write system/*.read");
    MvcResult result = mockMvc().perform(submitRequest(token)).andExpect(MockMvcResultMatchers.status().isCreated())
        .andReturn();
    String location = result.getResponse().getHeader("Location");
    if (location != null && location.contains("identifier=")) {
      String id = location.substring(location.indexOf("identifier=") + 11);
      claimIds.add(id.contains("&") ? id.substring(0, id.indexOf('&')) : id);
    }
  }

  @Test
  public void readOnlyTokenCanStillInquire() throws Exception {
    String token = requestToken(registerClient(), "system/*.read");
    int status = mockMvc()
        .perform(MockMvcRequestBuilders.post("/Claim/$inquire").content(completeClaim)
            .header("Content-Type", "application/fhir+json").header("Authorization", "Bearer " + token))
        .andReturn().getResponse().getStatus();
    Assert.assertNotEquals(401, status);
    Assert.assertNotEquals(403, status);
  }

  private MockMvc mockMvc() {
    return MockMvcBuilders.webAppContextSetup(wac).build();
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder submitRequest(String token) {
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder = MockMvcRequestBuilders
        .post("/Claim/$submit").content(completeClaim).header("Content-Type", "application/fhir+json");
    if (token != null)
      builder.header("Authorization", "Bearer " + token);
    return builder;
  }

  /**
   * Run the SMART Backend Services token flow and verify the granted scope is
   * returned and stored with the token.
   */
  private String requestToken(String clientId, String scope) throws Exception {
    MvcResult result = mockMvc().perform(tokenRequest(clientId, scope))
        .andExpect(MockMvcResultMatchers.status().isOk()).andReturn();
    JSONObject body = (JSONObject) new JSONParser().parse(result.getResponse().getContentAsString());
    String token = (String) body.get("access_token");
    Assert.assertNotNull(token);
    Assert.assertEquals(scope, body.get("scope"));
    Assert.assertEquals(scope, App.getDB().readString(Table.CLIENT, Collections.singletonMap("token", token), "scope"));
    return token;
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder tokenRequest(String clientId,
      String scope) throws Exception {
    String audience = new URL("http", "localhost", 80, "").toString() + "/auth/token";
    String assertion = JWT.create().withKeyId(KID).withIssuer(clientId).withSubject(clientId).withAudience(audience)
        .withJWTId(UUID.randomUUID().toString()).withExpiresAt(new Date(System.currentTimeMillis() + 300000))
        .sign(Algorithm.RSA384((RSAPublicKey) keyPair.getPublic(), (RSAPrivateKey) keyPair.getPrivate()));
    return MockMvcRequestBuilders.post("/auth/token").param("scope", scope).param("grant_type", "client_credentials")
        .param("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
        .param("client_assertion", assertion);
  }

  @SuppressWarnings("unchecked")
  private String registerClient() {
    RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
    JSONObject key = new JSONObject();
    key.put("kty", "RSA");
    key.put("kid", KID);
    key.put("alg", "RS384");
    key.put("e", base64Url(publicKey.getPublicExponent()));
    key.put("n", base64Url(publicKey.getModulus()));
    JSONObject jwks = new JSONObject();
    jwks.put("keys", Collections.singletonList(key));

    String clientId = "em170-" + UUID.randomUUID();
    Map<String, Object> client = new HashMap<>();
    client.put("id", clientId);
    client.put("jwks", jwks.toJSONString());
    App.getDB().write(Table.CLIENT, client);
    clientIds.add(clientId);
    return clientId;
  }

  private static String base64Url(BigInteger value) {
    byte[] bytes = value.toByteArray();
    if (bytes.length > 1 && bytes[0] == 0) {
      byte[] trimmed = new byte[bytes.length - 1];
      System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
      bytes = trimmed;
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
