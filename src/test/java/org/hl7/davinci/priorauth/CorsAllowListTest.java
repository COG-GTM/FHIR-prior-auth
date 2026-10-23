package org.hl7.davinci.priorauth;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.hl7.fhir.r4.model.CapabilityStatement;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * EM-162 / 45 CFR 164.312(e): CORS and WebSocket origins are restricted to the
 * configured allow-list.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "server.servlet.contextPath=/fhir",
    "priorauth.cors.allowed-origins=" + CorsAllowListTest.ALLOWED_ORIGIN })
public class CorsAllowListTest {

  static final String ALLOWED_ORIGIN = "https://allowed.example.org";
  private static final String DISALLOWED_ORIGIN = "https://evil.example.com";
  private static final String ACAO = "Access-Control-Allow-Origin";

  @LocalServerPort
  private int port;

  @Autowired
  private WebApplicationContext wac;

  private MockMvc mockMvc;

  @Before
  public void setup() {
    App.initializeAppDB();
    mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
  }

  @Test
  public void disallowedOriginGetsNoAllowOriginHeader() throws Exception {
    mockMvc.perform(MockMvcRequestBuilders.get("/metadata").header("Accept", "application/fhir+json")
        .header("Origin", DISALLOWED_ORIGIN))
        .andExpect(MockMvcResultMatchers.status().isForbidden())
        .andExpect(MockMvcResultMatchers.header().doesNotExist(ACAO));
  }

  @Test
  public void disallowedOriginPreflightGetsNoAllowOriginHeader() throws Exception {
    mockMvc.perform(MockMvcRequestBuilders.options("/Claim/$submit").header("Origin", DISALLOWED_ORIGIN)
        .header("Access-Control-Request-Method", "POST"))
        .andExpect(MockMvcResultMatchers.header().doesNotExist(ACAO));
  }

  @Test
  public void allowedOriginIsEchoedNotWildcard() throws Exception {
    MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/metadata")
        .header("Accept", "application/fhir+json").header("Origin", ALLOWED_ORIGIN))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.header().string(ACAO, ALLOWED_ORIGIN)).andReturn();

    CapabilityStatement capabilityStatement = (CapabilityStatement) App.getFhirContext().newJsonParser()
        .parseResource(result.getResponse().getContentAsString());
    Assert.assertTrue(capabilityStatement.getRestFirstRep().getSecurity().getCors());
  }

  @Test
  public void allowedOriginPreflightSucceeds() throws Exception {
    mockMvc.perform(MockMvcRequestBuilders.options("/Claim/$submit").header("Origin", ALLOWED_ORIGIN)
        .header("Access-Control-Request-Method", "POST"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.header().string(ACAO, ALLOWED_ORIGIN));
  }

  @Test
  public void websocketHandshakeRejectsDisallowedOrigin() throws Exception {
    Assert.assertEquals(403, websocketHandshakeStatus(DISALLOWED_ORIGIN));
  }

  @Test
  public void websocketHandshakeAcceptsAllowedOrigin() throws Exception {
    Assert.assertEquals(101, websocketHandshakeStatus(ALLOWED_ORIGIN));
  }

  private int websocketHandshakeStatus(String origin) throws Exception {
    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(10000);
      String request = "GET /fhir" + WebSocketConfig.ENDPOINT_CONNECT + " HTTP/1.1\r\n"
          + "Host: localhost:" + port + "\r\n"
          + "Upgrade: websocket\r\n"
          + "Connection: Upgrade\r\n"
          + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
          + "Sec-WebSocket-Version: 13\r\n"
          + "Origin: " + origin + "\r\n\r\n";
      OutputStream out = socket.getOutputStream();
      out.write(request.getBytes(StandardCharsets.US_ASCII));
      out.flush();
      BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
      String statusLine = in.readLine();
      Assert.assertNotNull(statusLine);
      return Integer.parseInt(statusLine.split(" ")[1]);
    }
  }
}
