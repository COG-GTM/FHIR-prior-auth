package org.hl7.davinci.priorauth.bfd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.hl7.davinci.priorauth.App;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * Tests for BfdToPasTransformer.
 * Validates that BFD resources are correctly transformed to PAS-compatible format.
 */
@RunWith(SpringRunner.class)
@TestPropertySource(properties = "server.servlet.contextPath=/fhir")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class BfdToPasTransformerTest {

    private static final String MBI_SYSTEM = "http://hl7.org/fhir/sid/us-mbi";
    private static final String PAS_CLAIM_PROFILE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-claim";

    @BeforeClass
    public static void setup() {
        App.initializeAppDB();
    }

    private Patient createTestPatient(String mbi) {
        Patient patient = new Patient();
        patient.setId("test-patient-1");
        Identifier mbiIdentifier = new Identifier();
        mbiIdentifier.setSystem(MBI_SYSTEM);
        mbiIdentifier.setValue(mbi);
        patient.addIdentifier(mbiIdentifier);
        patient.addName().setFamily("TestFamily").addGiven("TestGiven");
        return patient;
    }

    private ExplanationOfBenefit createTestEob(String claimType) {
        ExplanationOfBenefit eob = new ExplanationOfBenefit();
        eob.setId("test-eob-1");
        eob.setPatient(new Reference("Patient/test-patient-1"));

        // Set claim type
        CodeableConcept type = new CodeableConcept();
        type.addCoding().setSystem("http://terminology.hl7.org/CodeSystem/claim-type").setCode(claimType);
        eob.setType(type);

        // Add a line item
        ExplanationOfBenefit.ItemComponent item = eob.addItem();
        item.setSequence(1);
        CodeableConcept productOrService = new CodeableConcept();
        productOrService.addCoding()
                .setSystem("http://www.ama-assn.org/go/cpt")
                .setCode("99213")
                .setDisplay("Office visit");
        item.setProductOrService(productOrService);

        // Add diagnosis
        ExplanationOfBenefit.DiagnosisComponent diagnosis = eob.addDiagnosis();
        diagnosis.setSequence(1);
        CodeableConcept diagnosisCode = new CodeableConcept();
        diagnosisCode.addCoding()
                .setSystem("http://hl7.org/fhir/sid/icd-10-cm")
                .setCode("J06.9")
                .setDisplay("Acute upper respiratory infection");
        diagnosis.setDiagnosis(diagnosisCode);

        return eob;
    }

    private Coverage createTestCoverage() {
        Coverage coverage = new Coverage();
        coverage.setId("test-coverage-1");
        coverage.setBeneficiary(new Reference("Patient/test-patient-1"));
        coverage.setStatus(Coverage.CoverageStatus.ACTIVE);

        CodeableConcept type = new CodeableConcept();
        type.addCoding().setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode").setCode("SUBSIDIZ");
        coverage.setType(type);

        coverage.addPayor(new Reference("Organization/cms"));
        return coverage;
    }

    @Test
    public void testTransformPatientToPasClaim() {
        Patient patient = createTestPatient("1EG4-TE5-MK72");
        List<ExplanationOfBenefit> eobs = new ArrayList<>();
        eobs.add(createTestEob("professional"));

        Bundle result = BfdToPasTransformer.transformPatientToPasClaim(patient, eobs);

        Assert.assertNotNull(result);
        Assert.assertEquals(Bundle.BundleType.COLLECTION, result.getType());
        Assert.assertTrue(result.getEntry().size() > 0);

        // First entry should be a Claim
        Claim claim = (Claim) result.getEntry().get(0).getResource();
        Assert.assertNotNull(claim);

        // Verify PAS profile
        Assert.assertTrue(claim.getMeta().getProfile().stream()
                .anyMatch(p -> p.getValue().equals(PAS_CLAIM_PROFILE)));
    }

    @Test
    public void testTransformPatientToPasClaimPreservesMbi() {
        String testMbi = "1EG4-TE5-MK72";
        Patient patient = createTestPatient(testMbi);
        List<ExplanationOfBenefit> eobs = new ArrayList<>();
        eobs.add(createTestEob("professional"));

        Bundle result = BfdToPasTransformer.transformPatientToPasClaim(patient, eobs);
        Claim claim = (Claim) result.getEntry().get(0).getResource();

        // Verify MBI is preserved as identifier
        boolean hasMbi = claim.getIdentifier().stream()
                .anyMatch(id -> MBI_SYSTEM.equals(id.getSystem()) && testMbi.equals(id.getValue()));
        Assert.assertTrue("Claim should have MBI identifier", hasMbi);
    }

    @Test
    public void testMapBfdEobToPasClaimItem() {
        ExplanationOfBenefit eob = createTestEob("professional");

        List<Claim.ItemComponent> items = BfdToPasTransformer.mapBfdEobToPasClaimItem(eob, 1);

        Assert.assertNotNull(items);
        Assert.assertTrue(items.size() > 0);

        // Verify the item has product or service code
        Claim.ItemComponent item = items.get(0);
        Assert.assertNotNull(item.getProductOrService());
        Assert.assertTrue(item.getProductOrService().getCoding().size() > 0);
    }

    @Test
    public void testMapBfdCoverageToPasInsurance() {
        Coverage coverage = createTestCoverage();

        Claim.InsuranceComponent insurance = BfdToPasTransformer.mapBfdCoverageToPasInsurance(coverage);

        Assert.assertNotNull(insurance);
        Assert.assertTrue(insurance.getFocal());
        Assert.assertNotNull(insurance.getCoverage());
    }

    @Test
    public void testEnrichClaimResponseWithBfdData() {
        Patient patient = createTestPatient("1EG4-TE5-MK72");
        ClaimResponse claimResponse = new ClaimResponse();
        claimResponse.setId("test-response-1");
        claimResponse.setStatus(ClaimResponse.ClaimResponseStatus.ACTIVE);

        ClaimResponse enriched = BfdToPasTransformer.enrichClaimResponseWithBfdData(claimResponse, patient);

        Assert.assertNotNull(enriched);
        // Should have patient reference
        Assert.assertNotNull(enriched.getPatient());
    }

    @Test
    public void testTransformWithEmptyEobs() {
        Patient patient = createTestPatient("1EG4-TE5-MK72");
        List<ExplanationOfBenefit> eobs = Collections.emptyList();

        Bundle result = BfdToPasTransformer.transformPatientToPasClaim(patient, eobs);

        Assert.assertNotNull(result);
        // Should still create a claim even with no EOBs
        Assert.assertTrue(result.getEntry().size() > 0);
    }

    @Test
    public void testMapInstitutionalClaimType() {
        ExplanationOfBenefit eob = createTestEob("institutional");
        Patient patient = createTestPatient("1EG4-TE5-MK72");
        List<ExplanationOfBenefit> eobs = new ArrayList<>();
        eobs.add(eob);

        Bundle result = BfdToPasTransformer.transformPatientToPasClaim(patient, eobs);
        Assert.assertNotNull(result);

        Claim claim = (Claim) result.getEntry().get(0).getResource();
        Assert.assertNotNull(claim.getType());
    }

    @Test
    public void testMapPharmacyClaimType() {
        ExplanationOfBenefit eob = createTestEob("pharmacy");
        Patient patient = createTestPatient("1EG4-TE5-MK72");
        List<ExplanationOfBenefit> eobs = new ArrayList<>();
        eobs.add(eob);

        Bundle result = BfdToPasTransformer.transformPatientToPasClaim(patient, eobs);
        Assert.assertNotNull(result);
    }

    @Test
    public void testExtractMbi() {
        Patient patient = createTestPatient("1EG4-TE5-MK72");
        String mbi = BfdToPasTransformer.extractMbi(patient);
        Assert.assertEquals("1EG4-TE5-MK72", mbi);
    }

    @Test
    public void testExtractMbiReturnsNullWhenMissing() {
        Patient patient = new Patient();
        patient.setId("no-mbi-patient");
        String mbi = BfdToPasTransformer.extractMbi(patient);
        Assert.assertNull(mbi);
    }
}
