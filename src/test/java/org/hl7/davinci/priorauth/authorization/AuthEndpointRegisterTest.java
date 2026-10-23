package org.hl7.davinci.priorauth.authorization;

import org.hl7.davinci.priorauth.App;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
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
public class AuthEndpointRegisterTest {

  private static final String REGISTRATION_TOKEN = "test-registration-admin-token";
  private static final String JWKS = "{\"keys\":[{\"kty\":\"RSA\",\"e\":\"AQAB\",\"kid\":\"test\",\"alg\":\"RS384\",\"n\":\"abc\"}]}";

  @Autowired
  private WebApplicationContext wac;

  private MockMvc mockMvc;

  @BeforeClass
  public static void setupDb() {
    App.initializeAppDB();
  }

  @AfterClass
  public static void clearProperty() {
    System.clearProperty(ClientRegistrationSecurity.REGISTRATION_TOKEN_PROPERTY);
  }

  @Before
  public void setup() {
    System.setProperty(ClientRegistrationSecurity.REGISTRATION_TOKEN_PROPERTY, REGISTRATION_TOKEN);
    mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
  }

  @After
  public void teardown() {
    System.clearProperty(ClientRegistrationSecurity.REGISTRATION_TOKEN_PROPERTY);
  }

  private static String body(String keyMaterial) {
    return "{" + keyMaterial + ",\"organization_name\":\"Test Org\",\"organization_contact\":\"test@example.com\"}";
  }

  private static String jwksUrlBody(String url) {
    return body("\"jwks_url\":\"" + url + "\"");
  }

  private static MockHttpServletRequestBuilder register(String content) {
    return MockMvcRequestBuilders.post("/auth/register").content(content).header("Content-Type", "application/json");
  }

  @Test
  public void unauthenticatedRegisterIsRejected() throws Exception {
    mockMvc.perform(register(body("\"jwks\":" + JWKS))).andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  public void wrongTokenRegisterIsRejected() throws Exception {
    mockMvc.perform(register(body("\"jwks\":" + JWKS)).header("Authorization", "Bearer not-the-token"))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  public void registerIsDisabledWhenNoTokenConfigured() throws Exception {
    System.clearProperty(ClientRegistrationSecurity.REGISTRATION_TOKEN_PROPERTY);
    mockMvc.perform(register(body("\"jwks\":" + JWKS)).header("Authorization", "Bearer " + REGISTRATION_TOKEN))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  public void authorizedRegisterWithJwksSucceeds() throws Exception {
    mockMvc.perform(register(body("\"jwks\":" + JWKS)).header("Authorization", "Bearer " + REGISTRATION_TOKEN))
        .andExpect(MockMvcResultMatchers.status().isOk());
  }

  @Test
  public void httpJwksUrlIsRejected() throws Exception {
    mockMvc.perform(register(jwksUrlBody("http://example.com/jwks")).header("Authorization", "Bearer " + REGISTRATION_TOKEN))
        .andExpect(MockMvcResultMatchers.status().isBadRequest());
  }

  @Test
  public void internalJwksUrlsAreRejected() throws Exception {
    String[] urls = { "https://127.0.0.1/jwks", "https://localhost/jwks", "https://169.254.169.254/latest/meta-data",
        "https://10.0.0.5/jwks", "https://192.168.1.10/jwks", "https://[::1]/jwks", "file:///etc/passwd" };
    for (String url : urls) {
      mockMvc.perform(register(jwksUrlBody(url)).header("Authorization", "Bearer " + REGISTRATION_TOKEN))
          .andExpect(MockMvcResultMatchers.status().isBadRequest());
    }
  }

  @Test
  public void internalJwksUrlRejectedEvenWithInlineJwks() throws Exception {
    mockMvc.perform(register(body("\"jwks\":" + JWKS + ",\"jwks_url\":\"https://127.0.0.1/jwks\""))
        .header("Authorization", "Bearer " + REGISTRATION_TOKEN))
        .andExpect(MockMvcResultMatchers.status().isBadRequest());
  }
}
