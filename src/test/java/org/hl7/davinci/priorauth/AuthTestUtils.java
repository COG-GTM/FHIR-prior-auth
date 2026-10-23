package org.hl7.davinci.priorauth;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Test helper that registers a client with a fresh RSA key and obtains an access token through the
 * real /auth/register and /auth/token endpoints.
 */
public final class AuthTestUtils {

  private AuthTestUtils() {
  }

  public static String issueAccessToken(MockMvc mockMvc) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
    RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
    String kid = UUID.randomUUID().toString();

    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    JSONObject jwk = new JSONObject();
    jwk.put("kty", "RSA");
    jwk.put("alg", "RS384");
    jwk.put("kid", kid);
    jwk.put("e", encoder.encodeToString(publicKey.getPublicExponent().toByteArray()));
    jwk.put("n", encoder.encodeToString(publicKey.getModulus().toByteArray()));
    JSONArray keys = new JSONArray();
    keys.add(jwk);
    JSONObject jwks = new JSONObject();
    jwks.put("keys", keys);

    JSONObject registration = new JSONObject();
    registration.put("jwks", jwks);
    registration.put("organization_name", "Auth Test Client");
    registration.put("organization_contact", "auth-test@example.com");

    MvcResult registerResult = mockMvc
        .perform(MockMvcRequestBuilders.post("/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(registration.toJSONString()))
        .andExpect(MockMvcResultMatchers.status().isOk()).andReturn();
    String registerBody = registerResult.getResponse().getContentAsString();
    String clientId = registerBody.replaceAll("(?s).*client_id: \"([^\"]+)\".*", "$1");

    String clientAssertion = JWT.create().withKeyId(kid).withIssuer(clientId).withSubject(clientId)
        .withAudience(App.getBaseUrl() + "/auth/token").withJWTId(UUID.randomUUID().toString())
        .withExpiresAt(new Date(System.currentTimeMillis() + 5 * 60 * 1000))
        .sign(Algorithm.RSA384(publicKey, privateKey));

    MvcResult tokenResult = mockMvc
        .perform(MockMvcRequestBuilders.post("/auth/token").param("scope", "system/*.read")
            .param("grant_type", "client_credentials")
            .param("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
            .param("client_assertion", clientAssertion))
        .andExpect(MockMvcResultMatchers.status().isOk()).andReturn();
    JSONObject tokenResponse = (JSONObject) new JSONParser().parse(tokenResult.getResponse().getContentAsString());
    return (String) tokenResponse.get("access_token");
  }
}
