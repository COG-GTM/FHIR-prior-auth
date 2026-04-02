package org.hl7.davinci.priorauth.bfd;

import org.hl7.davinci.priorauth.App;
import org.hl7.davinci.priorauth.FhirUtils.Disposition;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * Tests for PasToBfdTransformer.
 * Validates that PAS decisions are correctly transformed to BFD-compatible format
 * conforming to CARIN IG expectations.
 */
@RunWith(SpringRunner.class)
@TestPropertySource(properties = "server.servlet.contextPath=/fhir")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class PasToBfdTransformerTest {

    private static final String X12_SYSTEM = "https://codesystem.x12.org/005010/306";
    private static final String PA_STATUS_EXTENSION_URL = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-paStatus";

    @BeforeClass
    public static void setup() {
        App.initializeAppDB();
    }

    private ClaimResponse createTestClaimResponse(String disposition) {
        ClaimResponse response = new ClaimResponse();
        response.setId("test-response-1");
        response.setStatus(ClaimResponse.ClaimResponseStatus.ACTIVE);
        response.setDisposition(disposition);
        response.setOutcome(ClaimResponse.RemittanceOutcome.COMPLETE);
        return response;
    }

    @Test
    public void testTransformGrantedClaimResponse() {
        ClaimResponse response = createTestClaimResponse("Granted");
        Extension extension = PasToBfdTransformer.transformClaimResponseToEobExtension(response);

        Assert.assertNotNull(extension);
        Assert.assertEquals(PA_STATUS_EXTENSION_URL, extension.getUrl());
    }

    @Test
    public void testTransformDeniedClaimResponse() {
        ClaimResponse response = createTestClaimResponse("Denied");
        Extension extension = PasToBfdTransformer.transformClaimResponseToEobExtension(response);

        Assert.assertNotNull(extension);
        Assert.assertEquals(PA_STATUS_EXTENSION_URL, extension.getUrl());
    }

    @Test
    public void testTransformPendingClaimResponse() {
        ClaimResponse response = createTestClaimResponse("Pending");
        Extension extension = PasToBfdTransformer.transformClaimResponseToEobExtension(response);

        Assert.assertNotNull(extension);
    }

    @Test
    public void testTransformPartialClaimResponse() {
        ClaimResponse response = createTestClaimResponse("Partial");
        Extension extension = PasToBfdTransformer.transformClaimResponseToEobExtension(response);

        Assert.assertNotNull(extension);
    }

    @Test
    public void testTransformCancelledClaimResponse() {
        ClaimResponse response = createTestClaimResponse("Cancelled");
        Extension extension = PasToBfdTransformer.transformClaimResponseToEobExtension(response);

        Assert.assertNotNull(extension);
    }

    @Test
    public void testMapGrantedDisposition() {
        CodeableConcept adjudication = PasToBfdTransformer.mapDispositionToAdjudicationCategory(Disposition.GRANTED);

        Assert.assertNotNull(adjudication);
        Assert.assertTrue(adjudication.getCoding().size() > 0);

        // Should have X12 review action code A1
        boolean hasA1 = adjudication.getCoding().stream()
                .anyMatch(c -> X12_SYSTEM.equals(c.getSystem()) && "A1".equals(c.getCode()));
        Assert.assertTrue("GRANTED should map to X12 code A1", hasA1);
    }

    @Test
    public void testMapDeniedDisposition() {
        CodeableConcept adjudication = PasToBfdTransformer.mapDispositionToAdjudicationCategory(Disposition.DENIED);

        Assert.assertNotNull(adjudication);
        boolean hasA3 = adjudication.getCoding().stream()
                .anyMatch(c -> X12_SYSTEM.equals(c.getSystem()) && "A3".equals(c.getCode()));
        Assert.assertTrue("DENIED should map to X12 code A3", hasA3);
    }

    @Test
    public void testMapPartialDisposition() {
        CodeableConcept adjudication = PasToBfdTransformer.mapDispositionToAdjudicationCategory(Disposition.PARTIAL);

        Assert.assertNotNull(adjudication);
        boolean hasA2 = adjudication.getCoding().stream()
                .anyMatch(c -> X12_SYSTEM.equals(c.getSystem()) && "A2".equals(c.getCode()));
        Assert.assertTrue("PARTIAL should map to X12 code A2", hasA2);
    }

    @Test
    public void testMapPendingDisposition() {
        CodeableConcept adjudication = PasToBfdTransformer.mapDispositionToAdjudicationCategory(Disposition.PENDING);

        Assert.assertNotNull(adjudication);
        boolean hasA4 = adjudication.getCoding().stream()
                .anyMatch(c -> X12_SYSTEM.equals(c.getSystem()) && "A4".equals(c.getCode()));
        Assert.assertTrue("PENDING should map to X12 code A4", hasA4);
    }

    @Test
    public void testMapCancelledDisposition() {
        CodeableConcept adjudication = PasToBfdTransformer.mapDispositionToAdjudicationCategory(Disposition.CANCELLED);

        Assert.assertNotNull(adjudication);
        boolean hasA6 = adjudication.getCoding().stream()
                .anyMatch(c -> X12_SYSTEM.equals(c.getSystem()) && "A6".equals(c.getCode()));
        Assert.assertTrue("CANCELLED should map to X12 code A6", hasA6);
    }

    @Test
    public void testMapNullDisposition() {
        CodeableConcept adjudication = PasToBfdTransformer.mapDispositionToAdjudicationCategory(null);

        Assert.assertNotNull(adjudication);
        // Should return unknown/default coding
        Assert.assertTrue(adjudication.getCoding().size() > 0);
    }

    @Test
    public void testAdjudicationIncludesCarinCoding() {
        CodeableConcept adjudication = PasToBfdTransformer.mapDispositionToAdjudicationCategory(Disposition.GRANTED);

        // Should include CARIN IG coding
        String carinSystem = "http://hl7.org/fhir/us/carin-bb/CodeSystem/C4BBAdjudicationDiscriminator";
        boolean hasCarinCoding = adjudication.getCoding().stream()
                .anyMatch(c -> carinSystem.equals(c.getSystem()));
        Assert.assertTrue("Adjudication should include CARIN IG coding", hasCarinCoding);
    }
}
