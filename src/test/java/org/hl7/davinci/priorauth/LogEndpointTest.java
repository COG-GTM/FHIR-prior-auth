package org.hl7.davinci.priorauth;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

import org.hamcrest.Matchers;
import org.hl7.davinci.priorauth.Database.Table;
import org.hl7.davinci.priorauth.authorization.AuthUtils;
import org.hl7.davinci.priorauth.endpoint.LogEndpoint;
import org.junit.After;
import org.junit.AfterClass;
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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@RunWith(SpringRunner.class)
@TestPropertySource(properties = { "server.servlet.contextPath=/fhir",
    "priorauth.log.admin-token=" + LogEndpointTest.LOG_ADMIN_TOKEN })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class LogEndpointTest {

  static final String LOG_ADMIN_TOKEN = "log-endpoint-test-admin-token";
  private static final String CLIENT_ID = "log-endpoint-test-client";
  private static final String CLIENT_TOKEN = "log-endpoint-test-token";
  private static final String ORIGIN = "http://evil.example.com";
  private static final String LOG_MARKER = "LogEndpointTest marker";

  @Autowired
  private WebApplicationContext wac;

  private static ResultMatcher ok = MockMvcResultMatchers.status().isOk();
  private static ResultMatcher notFound = MockMvcResultMatchers.status().isNotFound();
  private static ResultMatcher unauthorized = MockMvcResultMatchers.status().isUnauthorized();
  private static ResultMatcher noCors = MockMvcResultMatchers.header().doesNotExist("Access-Control-Allow-Origin");

  @BeforeClass
  public static void setup() throws Exception {
    App.initializeAppDB();
    Path logPath = Paths.get(PALogger.getLogPath());
    if (logPath.getParent() != null)
      Files.createDirectories(logPath.getParent());
    PALogger.getLogger().info(LOG_MARKER);
    if (!Files.exists(logPath)
        || !new String(Files.readAllBytes(logPath), StandardCharsets.UTF_8).contains(LOG_MARKER)) {
      Files.write(logPath, (LOG_MARKER + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    Map<String, Object> client = new HashMap<>();
    client.put("id", CLIENT_ID);
    client.put("token", CLIENT_TOKEN);
    App.getDB().write(Table.CLIENT, client);
  }

  @AfterClass
  public static void cleanup() {
    App.getDB().delete(Table.CLIENT, CLIENT_ID);
  }

  @After
  public void reset() throws Exception {
    setDebugMode(false);
    ReflectionTestUtils.setField(wac.getBean(LogEndpoint.class), "adminToken", LOG_ADMIN_TOKEN);
  }

  private static void setDebugMode(boolean enabled) throws Exception {
    Field field = App.class.getDeclaredField("debugMode");
    field.setAccessible(true);
    field.setBoolean(null, enabled);
  }

  private static String sharedDefaultToken() throws Exception {
    Field field = AuthUtils.class.getDeclaredField("ADMIN_TOKEN");
    field.setAccessible(true);
    return (String) field.get(null);
  }

  private MockMvc mockMvc() {
    return MockMvcBuilders.webAppContextSetup(wac).build();
  }

  private MockHttpServletRequestBuilder getLog() {
    return MockMvcRequestBuilders.get("/Log").header("Origin", ORIGIN);
  }

  private MockHttpServletRequestBuilder getLog(String token) {
    return getLog().header("Authorization", "Bearer " + token);
  }

  @Test
  public void unauthenticatedGetLogIsRejected() throws Exception {
    mockMvc().perform(getLog()).andExpect(notFound).andExpect(noCors);
  }

  @Test
  public void adminGetLogOutsideDebugModeIsRejected() throws Exception {
    mockMvc().perform(getLog(LOG_ADMIN_TOKEN)).andExpect(notFound).andExpect(noCors);
  }

  @Test
  public void getLogWithoutConfiguredAdminTokenIsRejected() throws Exception {
    setDebugMode(true);
    ReflectionTestUtils.setField(wac.getBean(LogEndpoint.class), "adminToken", "");
    mockMvc().perform(getLog()).andExpect(notFound).andExpect(noCors);
    mockMvc().perform(getLog("")).andExpect(notFound).andExpect(noCors);
  }

  @Test
  public void unauthenticatedGetLogInDebugModeIsRejected() throws Exception {
    setDebugMode(true);
    mockMvc().perform(getLog()).andExpect(unauthorized).andExpect(noCors);
  }

  @Test
  public void invalidTokenGetLogInDebugModeIsRejected() throws Exception {
    setDebugMode(true);
    mockMvc().perform(getLog("not-a-real-token")).andExpect(unauthorized).andExpect(noCors);
  }

  @Test
  public void clientTokenGetLogInDebugModeIsRejected() throws Exception {
    setDebugMode(true);
    mockMvc().perform(getLog(CLIENT_TOKEN)).andExpect(unauthorized).andExpect(noCors);
  }

  @Test
  public void sharedDefaultClientTokenGetLogInDebugModeIsRejected() throws Exception {
    setDebugMode(true);
    mockMvc().perform(getLog(sharedDefaultToken())).andExpect(unauthorized).andExpect(noCors);
  }

  @Test
  public void adminGetLogInDebugModeSucceedsWithoutCorsOrCaching() throws Exception {
    setDebugMode(true);
    mockMvc().perform(getLog(LOG_ADMIN_TOKEN)).andExpect(ok).andExpect(noCors)
        .andExpect(MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
        .andExpect(MockMvcResultMatchers.content().string(Matchers.containsString(LOG_MARKER)));
  }
}
