package org.hl7.davinci.priorauth;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

import org.hl7.davinci.priorauth.Database.Table;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.hamcrest.Matchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@RunWith(SpringRunner.class)
@TestPropertySource(properties = "server.servlet.contextPath=/fhir")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class LogEndpointTest {

  private static final String CLIENT_ID = "log-endpoint-test-client";
  private static final String CLIENT_TOKEN = "log-endpoint-test-token";
  private static final String ORIGIN = "http://evil.example.com";

  @Autowired
  private WebApplicationContext wac;

  private static ResultMatcher ok = MockMvcResultMatchers.status().isOk();
  private static ResultMatcher notFound = MockMvcResultMatchers.status().isNotFound();
  private static ResultMatcher unauthorized = MockMvcResultMatchers.status().isUnauthorized();
  private static final String LOG_MARKER = "LogEndpointTest marker";

  private static ResultMatcher noCors = MockMvcResultMatchers.header().doesNotExist("Access-Control-Allow-Origin");

  @BeforeClass
  public static void setup() throws Exception {
    App.initializeAppDB();
    Path logPath = Paths.get(PALogger.getLogPath());
    if (logPath.getParent() != null)
      Files.createDirectories(logPath.getParent());
    Files.write(logPath, (LOG_MARKER + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
  public void resetDebugMode() throws Exception {
    setDebugMode(false);
  }

  private static void setDebugMode(boolean enabled) throws Exception {
    Field field = App.class.getDeclaredField("debugMode");
    field.setAccessible(true);
    field.setBoolean(null, enabled);
  }

  private MockMvc mockMvc() {
    return MockMvcBuilders.webAppContextSetup(wac).build();
  }

  private MockHttpServletRequestBuilder getLog() {
    return MockMvcRequestBuilders.get("/Log").header("Origin", ORIGIN);
  }

  @Test
  public void unauthenticatedGetLogIsRejected() throws Exception {
    mockMvc().perform(getLog()).andExpect(notFound).andExpect(noCors);
  }

  @Test
  public void authenticatedGetLogOutsideDebugModeIsRejected() throws Exception {
    mockMvc().perform(getLog().header("Authorization", "Bearer " + CLIENT_TOKEN)).andExpect(notFound)
        .andExpect(noCors);
  }

  @Test
  public void unauthenticatedGetLogInDebugModeIsRejected() throws Exception {
    setDebugMode(true);
    mockMvc().perform(getLog()).andExpect(unauthorized).andExpect(noCors);
  }

  @Test
  public void invalidTokenGetLogInDebugModeIsRejected() throws Exception {
    setDebugMode(true);
    mockMvc().perform(getLog().header("Authorization", "Bearer not-a-real-token")).andExpect(unauthorized)
        .andExpect(noCors);
  }

  @Test
  public void authenticatedGetLogInDebugModeSucceedsWithoutCors() throws Exception {
    setDebugMode(true);
    mockMvc().perform(getLog().header("Authorization", "Bearer " + CLIENT_TOKEN)).andExpect(ok).andExpect(noCors)
        .andExpect(MockMvcResultMatchers.content().string(Matchers.containsString(LOG_MARKER)));
  }
}
