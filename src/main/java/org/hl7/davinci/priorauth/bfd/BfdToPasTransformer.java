package org.hl7.davinci.priorauth.bfd;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.hl7.davinci.priorauth.PALogger;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Claim.ItemComponent;

/**
 * Transforms BFD (Beneficiary FHIR Data) resources into PAS-compatible resources.
 * Maps BFD Patient, ExplanationOfBenefit, and Coverage data to Da Vinci PAS
 * Claim and ClaimResponse structures.
 */
public class BfdToPasTransformer {

    private static final Logger logger = PALogger.getLogger();

    private static final String PAS_CLAIM_PROFILE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-claim";
    private static final String PAS_BUNDLE_PROFILE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-request-bundle";
    private static final String MBI_SYSTEM = "http://hl7.org/fhir/sid/us-mbi";

    /**
     * Transform a BFD Patient and their EOBs into a PAS Claim Bundle.
     *
     * @param bfdPatient the BFD Patient resource
     * @param eobs       list of ExplanationOfBenefit resources from BFD
     * @return a PAS-compliant Claim Bundle, or null on error
     */
    public static Bundle transformPatientToPasClaim(Patient bfdPatient, List<ExplanationOfBenefit> eobs) {
        if (bfdPatient == null) {
            logger.warning("BfdToPasTransformer::transformPatientToPasClaim: Patient is null");
            return null;
        }

        String bundleId = UUID.randomUUID().toString();

        Bundle pasBundle = new Bundle();
        pasBundle.setId(bundleId);
        pasBundle.setType(BundleType.COLLECTION);
        pasBundle.setTimestamp(new Date());

        Meta bundleMeta = new Meta();
        bundleMeta.addProfile(PAS_BUNDLE_PROFILE);
        pasBundle.setMeta(bundleMeta);

        // Create the PAS Claim
        Claim pasClaim = new Claim();
        pasClaim.setId(bundleId);
        pasClaim.setStatus(Claim.ClaimStatus.ACTIVE);
        pasClaim.setUse(Claim.Use.PREAUTHORIZATION);

        Meta claimMeta = new Meta();
        claimMeta.addProfile(PAS_CLAIM_PROFILE);
        pasClaim.setMeta(claimMeta);

        // Set patient reference
        String patientId = bfdPatient.getIdElement().getIdPart();
        pasClaim.setPatient(new Reference("Patient/" + patientId));

        // Set claim type from first EOB or default
        if (eobs != null && !eobs.isEmpty()) {
            pasClaim.setType(mapEobTypeToPasClaimType(eobs.get(0)));
        } else {
            CodeableConcept defaultType = new CodeableConcept();
            defaultType.addCoding(new Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/claim-type")
                    .setCode("professional")
                    .setDisplay("Professional"));
            pasClaim.setType(defaultType);
        }

        // Set created date
        pasClaim.setCreated(new Date());

        // Add MBI as identifier
        String mbi = extractMbi(bfdPatient);
        if (mbi != null) {
            Identifier mbiIdentifier = new Identifier();
            mbiIdentifier.setSystem(MBI_SYSTEM);
            mbiIdentifier.setValue(mbi);
            pasClaim.addIdentifier(mbiIdentifier);
        }

        // Map EOBs to Claim items
        if (eobs != null) {
            int sequence = 1;
            for (ExplanationOfBenefit eob : eobs) {
                List<ItemComponent> items = mapBfdEobToPasClaimItem(eob, sequence);
                for (ItemComponent item : items) {
                    pasClaim.addItem(item);
                    sequence++;
                }
            }
        }

        // Add Claim as first entry
        BundleEntryComponent claimEntry = pasBundle.addEntry();
        claimEntry.setResource(pasClaim);
        claimEntry.setFullUrl("Claim/" + bundleId);

        // Add Patient as second entry
        BundleEntryComponent patientEntry = pasBundle.addEntry();
        patientEntry.setResource(bfdPatient);
        patientEntry.setFullUrl("Patient/" + patientId);

        logger.info("BfdToPasTransformer::transformPatientToPasClaim: Created PAS Bundle " + bundleId +
                " with " + (eobs != null ? eobs.size() : 0) + " EOBs");

        return pasBundle;
    }

    /**
     * Map a BFD ExplanationOfBenefit to PAS Claim.item entries.
     *
     * @param eob      the BFD EOB resource
     * @param startSeq the starting sequence number
     * @return list of PAS Claim.item components
     */
    public static List<ItemComponent> mapBfdEobToPasClaimItem(ExplanationOfBenefit eob, int startSeq) {
        List<ItemComponent> items = new ArrayList<>();

        if (eob == null) {
            return items;
        }

        if (eob.hasItem()) {
            for (ExplanationOfBenefit.ItemComponent eobItem : eob.getItem()) {
                ItemComponent pasItem = new ItemComponent();
                pasItem.setSequence(startSeq++);

                // Map productOrService from EOB item
                if (eobItem.hasProductOrService()) {
                    pasItem.setProductOrService(eobItem.getProductOrService());
                }

                // Map service date
                if (eobItem.hasServiced()) {
                    pasItem.setServiced(eobItem.getServiced());
                }

                // Map quantity
                if (eobItem.hasQuantity()) {
                    pasItem.setQuantity(eobItem.getQuantity());
                }

                items.add(pasItem);
            }
        } else {
            // Create a single item from EOB-level data
            ItemComponent pasItem = new ItemComponent();
            pasItem.setSequence(startSeq);

            // Use EOB type as productOrService
            if (eob.hasType()) {
                pasItem.setProductOrService(eob.getType());
            }

            // Use EOB billable period as service date
            if (eob.hasBillablePeriod()) {
                pasItem.setServiced(eob.getBillablePeriod());
            }

            items.add(pasItem);
        }

        return items;
    }

    /**
     * Map a BFD Coverage to PAS Claim.insurance.
     *
     * @param coverage the BFD Coverage resource
     * @return a PAS-compatible Claim.InsuranceComponent
     */
    public static Claim.InsuranceComponent mapBfdCoverageToPasInsurance(Coverage coverage) {
        if (coverage == null) {
            return null;
        }

        Claim.InsuranceComponent insurance = new Claim.InsuranceComponent();
        insurance.setSequence(1);
        insurance.setFocal(true);

        // Reference the Coverage
        String coverageId = coverage.getIdElement().getIdPart();
        insurance.setCoverage(new Reference("Coverage/" + coverageId));

        return insurance;
    }

    /**
     * Enrich a PAS ClaimResponse with BFD patient demographics.
     *
     * @param pasResponse the PAS ClaimResponse to enrich
     * @param bfdPatient  the BFD Patient with demographics
     * @return the enriched ClaimResponse
     */
    public static ClaimResponse enrichClaimResponseWithBfdData(ClaimResponse pasResponse, Patient bfdPatient) {
        if (pasResponse == null || bfdPatient == null) {
            return pasResponse;
        }

        // Add BFD beneficiary ID as extension
        String mbi = extractMbi(bfdPatient);
        if (mbi != null) {
            Extension bfdExtension = new Extension();
            bfdExtension.setUrl("http://cms.gov/fhir/StructureDefinition/bfd-beneficiary-mbi");
            bfdExtension.setValue(new StringType(mbi));
            pasResponse.addExtension(bfdExtension);
        }

        // Add BFD patient reference
        String patientId = bfdPatient.getIdElement().getIdPart();
        if (patientId != null) {
            Extension bfdPatientRef = new Extension();
            bfdPatientRef.setUrl("http://cms.gov/fhir/StructureDefinition/bfd-patient-reference");
            bfdPatientRef.setValue(new Reference("Patient/" + patientId));
            pasResponse.addExtension(bfdPatientRef);
        }

        logger.info("BfdToPasTransformer::enrichClaimResponseWithBfdData: Enriched ClaimResponse with BFD data");
        return pasResponse;
    }

    /**
     * Extract the Medicare Beneficiary Identifier (MBI) from a BFD Patient.
     */
    public static String extractMbi(Patient patient) {
        if (patient == null || !patient.hasIdentifier()) {
            return null;
        }

        for (Identifier id : patient.getIdentifier()) {
            if (MBI_SYSTEM.equals(id.getSystem())) {
                return id.getValue();
            }
            // Also check for BFD-specific MBI system
            if ("https://bluebutton.cms.gov/resources/variables/bene_id".equals(id.getSystem())) {
                return id.getValue();
            }
        }

        // Fall back to first identifier
        if (!patient.getIdentifier().isEmpty()) {
            return patient.getIdentifierFirstRep().getValue();
        }

        return null;
    }

    /**
     * Map an EOB type to a PAS Claim type.
     */
    private static CodeableConcept mapEobTypeToPasClaimType(ExplanationOfBenefit eob) {
        CodeableConcept claimType = new CodeableConcept();

        if (eob.hasType()) {
            for (Coding coding : eob.getType().getCoding()) {
                String code = coding.getCode();
                if (code != null) {
                    switch (code) {
                        case "60":  // Inpatient
                        case "40":  // Outpatient
                        case "20":  // SNF
                        case "10":  // HHA
                        case "50":  // Hospice
                            claimType.addCoding(new Coding()
                                    .setSystem("http://terminology.hl7.org/CodeSystem/claim-type")
                                    .setCode("institutional")
                                    .setDisplay("Institutional"));
                            break;
                        case "71":  // Carrier
                        case "82":  // DME
                            claimType.addCoding(new Coding()
                                    .setSystem("http://terminology.hl7.org/CodeSystem/claim-type")
                                    .setCode("professional")
                                    .setDisplay("Professional"));
                            break;
                        case "PDE": // Part D
                            claimType.addCoding(new Coding()
                                    .setSystem("http://terminology.hl7.org/CodeSystem/claim-type")
                                    .setCode("pharmacy")
                                    .setDisplay("Pharmacy"));
                            break;
                        default:
                            claimType.addCoding(new Coding()
                                    .setSystem("http://terminology.hl7.org/CodeSystem/claim-type")
                                    .setCode("professional")
                                    .setDisplay("Professional"));
                            break;
                    }
                    break; // Use first coding
                }
            }
        }

        if (claimType.getCoding().isEmpty()) {
            claimType.addCoding(new Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/claim-type")
                    .setCode("professional")
                    .setDisplay("Professional"));
        }

        return claimType;
    }
}
