package org.hl7.davinci.priorauth;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.hl7.davinci.priorauth.Database.Table;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@RunWith(SpringRunner.class)
@TestPropertySource(properties = "server.servlet.contextPath=/fhir")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class DebugReleaseClaimTest {

  private static final String CLIENT_ID = "em166-test-client-" + UUID.randomUUID();
  private static final String CLIENT_TOKEN = UUID.randomUUID().toString();
  private static final String BUNDLE_ID = "em166-release-" + UUID.randomUUID();
  private static final String PATIENT = "pat013";

  @Autowired
  private WebApplicationContext wac;

  private MockMvc mockMvc;

  @BeforeClass
  public static void setup() throws FileNotFoundException {
    App.initializeAppDB();

    Map<String, Object> clientMap = new HashMap<>();
    clientMap.put("id", CLIENT_ID);
    clientMap.put("token", CLIENT_TOKEN);
    App.getDB().write(Table.CLIENT, clientMap);

    Path fixture = Paths.get("src/test/resources").resolve("bundle-prior-auth.json");
    Bundle bundle = (Bundle) App.getFhirContext().newJsonParser().parseResource(new FileInputStream(fixture.toString()));
    Map<String, Object> bundleMap = new HashMap<>();
    bundleMap.put("id", BUNDLE_ID);
    bundleMap.put("patient", PATIENT);
    bundleMap.put("resource", bundle);
    App.getDB().write(Table.BUNDLE, bundleMap);
  }

  @AfterClass
  public static void cleanup() {
    App.getDB().delete(Table.BUNDLE, BUNDLE_ID, PATIENT);
    App.getDB().delete(Table.CLIENT, CLIENT_ID);
    setDebugMode(false);
  }

  @Before
  public void buildMockMvc() {
    mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
  }

  @After
  public void resetDebugMode() {
    setDebugMode(false);
  }

  private static void setDebugMode(boolean enabled) {
    ReflectionTestUtils.setField(App.class, "debugMode", enabled);
  }

  private MockHttpServletRequestBuilder release(String token) {
    MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.get("/debug/ReleaseClaim")
        .param("identifier", BUNDLE_ID);
    if (token != null)
      builder.header("Authorization", "Bearer " + token);
    return builder;
  }

  @Test
  public void rejectedWhenDebugModeOffWithoutToken() throws Exception {
    setDebugMode(false);
    mockMvc.perform(release(null)).andExpect(MockMvcResultMatchers.status().isBadRequest());
  }

  @Test
  public void rejectedWhenDebugModeOffEvenWithValidToken() throws Exception {
    setDebugMode(false);
    mockMvc.perform(release(CLIENT_TOKEN)).andExpect(MockMvcResultMatchers.status().isBadRequest());
  }

  @Test
  public void rejectedInDebugModeWithoutToken() throws Exception {
    setDebugMode(true);
    mockMvc.perform(release(null)).andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  public void rejectedInDebugModeWithInvalidToken() throws Exception {
    setDebugMode(true);
    mockMvc.perform(release("not-a-real-token")).andExpect(MockMvcResultMatchers.status().isUnauthorized());
  }

  @Test
  public void releaseInDebugModeWithValidTokenIsAuditedWithCallerIdentity() throws Exception {
    setDebugMode(true);
    mockMvc.perform(release(CLIENT_TOKEN)).andExpect(MockMvcResultMatchers.status().isOk());

    String auditResource = App.getDB().readString(Table.AUDIT, Collections.singletonMap("what", "Claim/" + BUNDLE_ID),
        "resource");
    Assert.assertNotNull("Expected an audit event for the release", auditResource);
    Assert.assertTrue(auditResource.contains("Released pended claim " + BUNDLE_ID));
    Assert.assertTrue(auditResource.contains(CLIENT_ID));
  }
}
