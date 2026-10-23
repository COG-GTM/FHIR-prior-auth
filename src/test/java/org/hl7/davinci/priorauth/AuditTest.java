package org.hl7.davinci.priorauth;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.hl7.davinci.priorauth.Audit.AuditEventOutcome;
import org.hl7.davinci.priorauth.Database.Table;
import org.hl7.fhir.r4.model.Claim;
import org.junit.AfterClass;
import org.junit.Assert;
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
public class AuditTest {

  private static final String CLAIM_ID = "audit-test";
  private static final String PATIENT_ID = "audit-patient";
  private static final String ADMIN_TOKEN = "Bearer Y3YWq2l08kvFqy50fQJY";

  @Autowired
  private WebApplicationContext wac;

  @BeforeClass
  public static void setup() throws FileNotFoundException {
    App.initializeAppDB();
    Path fixture = Paths.get("src/test/resources").resolve("claim-minimal.json");
    Claim claim = (Claim) App.getFhirContext().newJsonParser()
        .parseResource(new FileInputStream(fixture.toString()));
    Map<String, Object> claimMap = new HashMap<>();
    claimMap.put("id", CLAIM_ID);
    claimMap.put("patient", PATIENT_ID);
    claimMap.put("status", FhirUtils.getStatusFromResource(claim));
    claimMap.put("resource", claim);
    App.getDB().write(Table.CLAIM, claimMap);
  }

  @AfterClass
  public static void cleanup() {
    App.getDB().delete(Table.CLAIM, CLAIM_ID, PATIENT_ID);
  }

  /** Unique per-test client address so the matching Audit row can be found. */
  private static String uniqueIp() {
    int n = UUID.randomUUID().hashCode();
    return "10." + ((n >>> 16) & 0xff) + "." + ((n >>> 8) & 0xff) + "." + (n & 0xff);
  }

  private static String auditOutcomeForIp(String ip) {
    return App.getDB().readString(Table.AUDIT, Collections.singletonMap("ip", ip), "outcome");
  }

  private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder builder, String ip) {
    return builder.with(request -> {
      request.setRemoteAddr(ip);
      return request;
    });
  }

  private MockMvc mockMvc() {
    return MockMvcBuilders.webAppContextSetup(wac).build();
  }

  @Test
  public void successfulReadIsAuditedAsSuccess() throws Exception {
    String ip = uniqueIp();
    mockMvc()
        .perform(from(MockMvcRequestBuilders.get("/Claim?identifier=" + CLAIM_ID + "&patient.identifier=" + PATIENT_ID)
            .header("Accept", "application/fhir+json").header("Authorization", ADMIN_TOKEN), ip))
        .andExpect(MockMvcResultMatchers.status().isOk());

    Assert.assertEquals(AuditEventOutcome.SUCCESS.toCode(), auditOutcomeForIp(ip));
  }

  @Test
  public void unauthorizedSubmitIsAuditedAsFailure() throws Exception {
    String ip = uniqueIp();
    mockMvc()
        .perform(from(MockMvcRequestBuilders.post("/Claim/$submit").content("{}")
            .header("Content-Type", "application/fhir+json").header("Authorization", "Bearer not-a-valid-token"), ip))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());

    Assert.assertEquals(AuditEventOutcome.MINOR_FAILURE.toCode(), auditOutcomeForIp(ip));
  }

  @Test
  public void unauthorizedInquireIsAuditedAsFailure() throws Exception {
    String ip = uniqueIp();
    mockMvc()
        .perform(from(MockMvcRequestBuilders.post("/Claim/$inquire").content("{}")
            .header("Content-Type", "application/fhir+json"), ip))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());

    Assert.assertEquals(AuditEventOutcome.MINOR_FAILURE.toCode(), auditOutcomeForIp(ip));
  }

  @Test
  public void spoofedForwardedForIsNotRecordedWithoutTrustedProxy() throws Exception {
    String ip = uniqueIp();
    String spoofed = uniqueIp();
    mockMvc()
        .perform(from(MockMvcRequestBuilders.post("/Claim/$submit").content("{}")
            .header("Content-Type", "application/fhir+json").header("X-Forwarded-For", spoofed), ip))
        .andExpect(MockMvcResultMatchers.status().isUnauthorized());

    Assert.assertEquals(AuditEventOutcome.MINOR_FAILURE.toCode(), auditOutcomeForIp(ip));
    Assert.assertNull(auditOutcomeForIp(spoofed));
  }

  @Test
  public void ipAddressIgnoresForwardedForWhenNoTrustedProxies() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("203.0.113.10");
    request.addHeader("X-Forwarded-For", "1.2.3.4");
    Assert.assertEquals("203.0.113.10", Audit.getIPAddress(request, Collections.emptySet()));
  }

  @Test
  public void ipAddressIgnoresForwardedForFromUntrustedPeer() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("203.0.113.10");
    request.addHeader("X-Forwarded-For", "1.2.3.4");
    Assert.assertEquals("203.0.113.10", Audit.getIPAddress(request, Set.of("10.0.0.1")));
  }

  @Test
  public void ipAddressHonoursForwardedForFromTrustedProxy() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.1");
    request.addHeader("X-Forwarded-For", "6.6.6.6, 198.51.100.7, 10.0.0.2");
    Assert.assertEquals("198.51.100.7", Audit.getIPAddress(request, Set.of("10.0.0.1", "10.0.0.2")));
  }

  @Test
  public void ipAddressFallsBackToRemoteAddrWhenNoForwardedFor() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.1");
    Assert.assertEquals("10.0.0.1", Audit.getIPAddress(request, Set.of("10.0.0.1")));
  }
}
