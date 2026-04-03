package org.hl7.davinci.priorauth.bfd;

import java.util.List;

import org.hl7.davinci.priorauth.App;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.hl7.fhir.r4.model.Patient;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * Tests for BfdClientService.
 * These tests verify the BFD client can be instantiated and handles
 * connection failures gracefully when BFD is not available.
 */
@RunWith(SpringRunner.class)
@TestPropertySource(properties = "server.servlet.contextPath=/fhir")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class BfdClientServiceTest {

    @BeforeClass
    public static void setup() {
        App.initializeAppDB();
    }

    @Test
    public void testBfdConfigurationDefaults() {
        BfdConfiguration config = new BfdConfiguration();
        Assert.assertNotNull(config);
        // Default URL should be set
        Assert.assertNotNull(config.getServerUrl());
    }

    @Test
    public void testBfdConfigurationDisabledByDefault() {
        // Without environment variables, BFD should not be fully enabled
        BfdConfiguration config = new BfdConfiguration();
        // The default URL is localhost which means BFD is technically configured
        // but mTLS won't be configured without cert paths
        Assert.assertFalse(config.isMtlsConfigured());
    }

    @Test
    public void testBfdClientServiceInstantiation() {
        BfdConfiguration config = new BfdConfiguration();
        BfdClientService client = new BfdClientService(config);
        Assert.assertNotNull(client);
    }

    @Test
    public void testGetPatientByMbiHandlesConnectionFailure() {
        // When BFD server is not available, should return null gracefully
        BfdConfiguration config = new BfdConfiguration();
        BfdClientService client = new BfdClientService(config);
        Patient patient = client.getPatientByMbi("1234567890");
        Assert.assertNull(patient);
    }

    @Test
    public void testGetEobsByPatientHandlesConnectionFailure() {
        // When BFD server is not available, should return empty list gracefully
        BfdConfiguration config = new BfdConfiguration();
        BfdClientService client = new BfdClientService(config);
        List<ExplanationOfBenefit> eobs = client.getEobsByPatient("test-patient-id");
        Assert.assertNotNull(eobs);
        Assert.assertTrue(eobs.isEmpty());
    }

    @Test
    public void testGetCoverageByPatientHandlesConnectionFailure() {
        BfdConfiguration config = new BfdConfiguration();
        BfdClientService client = new BfdClientService(config);
        List<Coverage> coverage = client.getCoverageByPatient("test-patient-id");
        Assert.assertNotNull(coverage);
        Assert.assertTrue(coverage.isEmpty());
    }

    @Test
    public void testGetClaimByPatientHandlesConnectionFailure() {
        BfdConfiguration config = new BfdConfiguration();
        BfdClientService client = new BfdClientService(config);
        List<Claim> claims = client.getClaimByPatient("test-patient-id");
        Assert.assertNotNull(claims);
        Assert.assertTrue(claims.isEmpty());
    }

    @Test
    public void testGetClaimResponseByPatientHandlesConnectionFailure() {
        BfdConfiguration config = new BfdConfiguration();
        BfdClientService client = new BfdClientService(config);
        List<ClaimResponse> responses = client.getClaimResponseByPatient("test-patient-id");
        Assert.assertNotNull(responses);
        Assert.assertTrue(responses.isEmpty());
    }
}
