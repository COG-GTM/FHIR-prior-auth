package org.hl7.davinci.priorauth.bfd;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import org.hl7.davinci.priorauth.App;
import org.hl7.davinci.priorauth.FhirUtils;
import org.hl7.davinci.priorauth.Database.Table;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Tests for PatientPriorAuthEndpoint.
 * Validates the GET /PriorAuthorization?patient={mbi} endpoint.
 */
@RunWith(SpringRunner.class)
@TestPropertySource(properties = {"server.servlet.contextPath=/fhir"})
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class PatientPriorAuthEndpointTest {

    @LocalServerPort
    private int port;

    @Autowired
    private WebApplicationContext wac;

    private static ResultMatcher cors = MockMvcResultMatchers.header().string("Access-Control-Allow-Origin", "*");
    private static ResultMatcher ok = MockMvcResultMatchers.status().isOk();
    private static ResultMatcher badRequest = MockMvcResultMatchers.status().isBadRequest();

    @BeforeClass
    public static void setup() {
        App.initializeAppDB();

        // Create a test ClaimResponse linked to a patient MBI
        ClaimResponse claimResponse = new ClaimResponse();
        claimResponse.setId("pa-test-1");
        claimResponse.setStatus(ClaimResponse.ClaimResponseStatus.ACTIVE);
        claimResponse.setDisposition("Granted");
        claimResponse.setOutcome(ClaimResponse.RemittanceOutcome.COMPLETE);

        Map<String, Object> responseMap = new HashMap<>();
        responseMap.put("id", "pa-test-1");
        responseMap.put("patient", "test-mbi-123");
        responseMap.put("status", FhirUtils.getStatusFromResource(claimResponse));
        responseMap.put("resource", claimResponse);
        App.getDB().write(Table.CLAIM_RESPONSE, responseMap);
    }

    @AfterClass
    public static void cleanup() {
        App.getDB().delete(Table.CLAIM_RESPONSE, "pa-test-1", "test-mbi-123");
    }

    @Test
    public void testGetPriorAuthByPatient() throws Exception {
        DefaultMockMvcBuilder builder = MockMvcBuilders.webAppContextSetup(wac);
        MockMvc mockMvc = builder.build();
        MockHttpServletRequestBuilder requestBuilder = MockMvcRequestBuilders
                .get("/PriorAuthorization?patient=test-mbi-123")
                .header("Accept", "application/fhir+json")
                .header("Access-Control-Request-Method", "GET")
                .header("Origin", "http://localhost:" + port)
                .header("Authorization", "Bearer Y3YWq2l08kvFqy50fQJY");

        MvcResult mvcresult = mockMvc.perform(requestBuilder).andExpect(ok).andExpect(cors).andReturn();

        String body = mvcresult.getResponse().getContentAsString();
        Assert.assertNotNull(body);
        Assert.assertFalse(body.isEmpty());

        // Parse as Bundle
        Bundle bundle = (Bundle) App.getFhirContext().newJsonParser().parseResource(body);
        Assert.assertNotNull(bundle);
        Assert.assertEquals(Bundle.BundleType.SEARCHSET, bundle.getType());
    }

    @Test
    public void testGetPriorAuthMissingPatient() throws Exception {
        DefaultMockMvcBuilder builder = MockMvcBuilders.webAppContextSetup(wac);
        MockMvc mockMvc = builder.build();
        MockHttpServletRequestBuilder requestBuilder = MockMvcRequestBuilders
                .get("/PriorAuthorization")
                .header("Accept", "application/fhir+json")
                .header("Access-Control-Request-Method", "GET")
                .header("Origin", "http://localhost:" + port)
                .header("Authorization", "Bearer Y3YWq2l08kvFqy50fQJY");

        // Should return 400 Bad Request when patient parameter is missing
        mockMvc.perform(requestBuilder).andExpect(badRequest);
    }

    @Test
    public void testGetPriorAuthEmptyPatient() throws Exception {
        DefaultMockMvcBuilder builder = MockMvcBuilders.webAppContextSetup(wac);
        MockMvc mockMvc = builder.build();
        MockHttpServletRequestBuilder requestBuilder = MockMvcRequestBuilders
                .get("/PriorAuthorization?patient=")
                .header("Accept", "application/fhir+json")
                .header("Access-Control-Request-Method", "GET")
                .header("Origin", "http://localhost:" + port)
                .header("Authorization", "Bearer Y3YWq2l08kvFqy50fQJY");

        // Should return 400 Bad Request when patient parameter is empty
        mockMvc.perform(requestBuilder).andExpect(badRequest);
    }

    @Test
    public void testGetPriorAuthNonExistentPatient() throws Exception {
        DefaultMockMvcBuilder builder = MockMvcBuilders.webAppContextSetup(wac);
        MockMvc mockMvc = builder.build();
        MockHttpServletRequestBuilder requestBuilder = MockMvcRequestBuilders
                .get("/PriorAuthorization?patient=non-existent-mbi-999")
                .header("Accept", "application/fhir+json")
                .header("Access-Control-Request-Method", "GET")
                .header("Origin", "http://localhost:" + port)
                .header("Authorization", "Bearer Y3YWq2l08kvFqy50fQJY");

        MvcResult mvcresult = mockMvc.perform(requestBuilder).andExpect(ok).andExpect(cors).andReturn();

        String body = mvcresult.getResponse().getContentAsString();
        Bundle bundle = (Bundle) App.getFhirContext().newJsonParser().parseResource(body);
        Assert.assertNotNull(bundle);
        // Should return empty bundle for non-existent patient
        Assert.assertEquals(0, bundle.getTotal());
    }

    @Test
    public void testGetPriorAuthXml() throws Exception {
        DefaultMockMvcBuilder builder = MockMvcBuilders.webAppContextSetup(wac);
        MockMvc mockMvc = builder.build();
        MockHttpServletRequestBuilder requestBuilder = MockMvcRequestBuilders
                .get("/PriorAuthorization?patient=test-mbi-123")
                .header("Accept", "application/fhir+xml")
                .header("Access-Control-Request-Method", "GET")
                .header("Origin", "http://localhost:" + port)
                .header("Authorization", "Bearer Y3YWq2l08kvFqy50fQJY");

        MvcResult mvcresult = mockMvc.perform(requestBuilder).andExpect(ok).andExpect(cors).andReturn();

        String body = mvcresult.getResponse().getContentAsString();
        Assert.assertNotNull(body);
        Assert.assertFalse(body.isEmpty());
    }
}
