package org.hl7.davinci.priorauth;

import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hl7.davinci.priorauth.Database.Table;
import org.hl7.davinci.priorauth.FhirUtils.ReviewAction;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.junit.After;
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
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@RunWith(SpringRunner.class)
@TestPropertySource(properties = "server.servlet.contextPath=/fhir")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class DebugEndpointTest {

  private static final String DENIED_ID = "release-denied";
  private static final String PENDED_ID = "release-pended";
  private static final String PENDED_UPDATE_ID = "release-pended-update";
  private static final String PATIENT = "1";
  private static final String ACCESS_TOKEN = "Bearer Y3YWq2l08kvFqy50fQJY";

  private static ResultMatcher badRequest = MockMvcResultMatchers.status().isBadRequest();
  private static ResultMatcher unauthorized = MockMvcResultMatchers.status().isUnauthorized();

  @Autowired
  private WebApplicationContext wac;

  @BeforeClass
  public static void setup() throws Exception {
    App.initializeAppDB();
    Path resources = Paths.get("src/test/resources");

    writeClaim(resources, DENIED_ID, null);
    writeBundle(resources, DENIED_ID);
    writeClaimResponse(DENIED_ID, ReviewAction.DENIED);

    writeClaim(resources, PENDED_ID, null);
    writeBundle(resources, PENDED_ID);
    writeClaimResponse(PENDED_ID, ReviewAction.PENDED);
  }

  @AfterClass
  public static void cleanup() throws Exception {
    setDebugMode(false);
    for (String id : new String[] { DENIED_ID, PENDED_ID, PENDED_UPDATE_ID }) {
      App.getDB().delete(Table.CLAIM, id, PATIENT);
      App.getDB().delete(Table.BUNDLE, id, PATIENT);
    }
    App.getDB().delete(Table.CLAIM_RESPONSE, DENIED_ID + "-response", PATIENT);
    App.getDB().delete(Table.CLAIM_RESPONSE, PENDED_ID + "-response", PATIENT);
  }

  @After
  public void resetDebugMode() throws Exception {
    setDebugMode(false);
  }

  @Test
  public void releaseClaimRefusedWhenNotInDebugMode() throws Exception {
    setDebugMode(false);
    mockMvc().perform(release(DENIED_ID).header("Authorization", ACCESS_TOKEN)).andExpect(badRequest);
    assertNoGrantedResponse(DENIED_ID);
  }

  @Test
  public void releaseClaimRequiresAccessToken() throws Exception {
    setDebugMode(true);
    mockMvc().perform(release(PENDED_ID)).andExpect(unauthorized);
    mockMvc().perform(release(PENDED_ID).header("Authorization", "Bearer invalid")).andExpect(unauthorized);
    Assert.assertEquals(ReviewAction.PENDED.value(), latestOutcome(PENDED_ID));
  }

  @Test
  public void releaseClaimRefusesDeniedClaimInDebugMode() throws Exception {
    setDebugMode(true);
    mockMvc().perform(release(DENIED_ID).header("Authorization", ACCESS_TOKEN)).andExpect(badRequest);
    assertNoGrantedResponse(DENIED_ID);
    Assert.assertEquals(ReviewAction.DENIED.value(), latestOutcome(DENIED_ID));
  }

  @Test
  public void onlyCurrentPendedClaimsAreReleasable() throws Exception {
    Assert.assertTrue(UpdateClaimTask.isReleasable(PENDED_ID));
    Assert.assertFalse(UpdateClaimTask.isReleasable(DENIED_ID));
    Assert.assertFalse(UpdateClaimTask.isReleasable("does-not-exist"));
    Assert.assertFalse(UpdateClaimTask.isReleasable(null));

    // A pended claim that has been superseded by an update must not be granted
    writeClaim(Paths.get("src/test/resources"), PENDED_UPDATE_ID, PENDED_ID);
    try {
      Assert.assertFalse(UpdateClaimTask.isReleasable(PENDED_ID));
    } finally {
      App.getDB().delete(Table.CLAIM, PENDED_UPDATE_ID, PATIENT);
    }
    Assert.assertTrue(UpdateClaimTask.isReleasable(PENDED_ID));
  }

  private MockMvc mockMvc() {
    return MockMvcBuilders.webAppContextSetup(wac).build();
  }

  private static MockHttpServletRequestBuilder release(String id) {
    return MockMvcRequestBuilders.get("/debug/ReleaseClaim").param("identifier", id);
  }

  private static String latestOutcome(String claimId) {
    return App.getDB().readString(Table.CLAIM_RESPONSE, Collections.singletonMap("claimId", claimId), "outcome");
  }

  private static void assertNoGrantedResponse(String claimId) {
    Map<String, Object> constraintMap = new HashMap<>();
    constraintMap.put("claimId", claimId);
    constraintMap.put("outcome", ReviewAction.APPROVED.value());
    List<IBaseResource> granted = App.getDB().readAll(Table.CLAIM_RESPONSE, constraintMap);
    Assert.assertTrue(granted.isEmpty());
  }

  private static void setDebugMode(boolean enabled) throws Exception {
    Field field = App.class.getDeclaredField("debugMode");
    field.setAccessible(true);
    field.setBoolean(null, enabled);
  }

  private static void writeClaim(Path resources, String id, String related) throws Exception {
    try (FileInputStream in = new FileInputStream(resources.resolve("claim-minimal.json").toString())) {
      Claim claim = (Claim) App.getFhirContext().newJsonParser().parseResource(in);
      claim.setId(id);
      Map<String, Object> claimMap = new HashMap<>();
      claimMap.put("id", id);
      claimMap.put("patient", PATIENT);
      claimMap.put("status", FhirUtils.getStatusFromResource(claim));
      claimMap.put("resource", claim);
      if (related != null)
        claimMap.put("related", related);
      App.getDB().write(Table.CLAIM, claimMap);
    }
  }

  private static void writeBundle(Path resources, String id) throws Exception {
    try (FileInputStream in = new FileInputStream(resources.resolve("bundle-minimal.json").toString())) {
      Bundle bundle = (Bundle) App.getFhirContext().newJsonParser().parseResource(in);
      bundle.setId(id);
      Map<String, Object> bundleMap = new HashMap<>();
      bundleMap.put("id", id);
      bundleMap.put("patient", PATIENT);
      bundleMap.put("resource", bundle);
      App.getDB().write(Table.BUNDLE, bundleMap);
    }
  }

  private static void writeClaimResponse(String claimId, ReviewAction outcome) throws Exception {
    try (FileInputStream in = new FileInputStream(Paths.get("src/test/resources/claimresponse-minimal.json").toString())) {
      Bundle response = (Bundle) App.getFhirContext().newJsonParser().parseResource(in);
      String id = claimId + "-response";
      response.setId(id);
      Map<String, Object> responseMap = new HashMap<>();
      responseMap.put("id", id);
      responseMap.put("claimId", claimId);
      responseMap.put("patient", PATIENT);
      responseMap.put("status", FhirUtils.getStatusFromResource(response));
      responseMap.put("outcome", outcome.value());
      responseMap.put("resource", response);
      App.getDB().write(Table.CLAIM_RESPONSE, responseMap);
    }
  }
}
