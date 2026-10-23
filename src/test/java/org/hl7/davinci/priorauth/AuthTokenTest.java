package org.hl7.davinci.priorauth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;

import javax.servlet.http.HttpServletRequest;

import org.hl7.davinci.priorauth.Database.Table;
import org.hl7.davinci.priorauth.authorization.AuthUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@RunWith(SpringRunner.class)
@TestPropertySource(properties = "server.servlet.contextPath=/fhir")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class AuthTokenTest {

  /** Formerly hard-coded admin token; publicly known and must never authenticate. */
  private static final String LEGACY_ADMIN_TOKEN = "Y3YWq2l08kvFqy50fQJY";
  private static final String SEEDED_CLIENT_ID = "797da153-9a36-4493-9910-10648a4deb03";

  private static String completeClaim;

  @Autowired
  private WebApplicationContext wac;

  private MockMvc mockMvc;

  @BeforeClass
  public static void setup() throws IOException {
    App.initializeAppDB();
    completeClaim = new String(Files.readAllBytes(Paths.get("src/test/resources/bundle-prior-auth.json")));
  }

  @Before
  public void buildMockMvc() {
    mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
  }

  private MockHttpServletRequestBuilder submit(String bearerToken) {
    MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.post("/Claim/$submit").content(completeClaim)
        .header("Content-Type", "application/fhir+json");
    if (bearerToken != null)
      builder.header("Authorization", "Bearer " + bearerToken);
    return builder;
  }

  private static HttpServletRequest requestWithToken(String token) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + token);
    return request;
  }

  @Test
  public void legacyAdminTokenIsRejectedOnSubmit() throws Exception {
    mockMvc.perform(submit(LEGACY_ADMIN_TOKEN)).andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  public void missingTokenIsRejectedOnSubmit() throws Exception {
    mockMvc.perform(submit(null)).andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  public void legacyAdminTokenIsNotValidAndHasNoIdentity() {
    HttpServletRequest request = requestWithToken(LEGACY_ADMIN_TOKEN);
    Assert.assertFalse(AuthUtils.validateAccessToken(request));
    Assert.assertNotEquals("Admin", AuthUtils.getClientId(request));
  }

  @Test
  public void seededClientTokenIsNotHardCoded() {
    String seededToken = App.getDB().readString(Table.CLIENT, Collections.singletonMap("id", SEEDED_CLIENT_ID),
        "token");
    Assert.assertNotNull(seededToken);
    Assert.assertNotEquals(LEGACY_ADMIN_TOKEN, seededToken);
  }

  @Test
  public void freshlyIssuedTokenIsAccepted() throws Exception {
    String accessToken = AuthTestUtils.issueAccessToken(mockMvc);
    Assert.assertNotNull(accessToken);
    Assert.assertNotEquals(LEGACY_ADMIN_TOKEN, accessToken);
    Assert.assertTrue(AuthUtils.validateAccessToken(requestWithToken(accessToken)));

    mockMvc.perform(submit(accessToken)).andExpect(MockMvcResultMatchers.status().isCreated());
  }
}
