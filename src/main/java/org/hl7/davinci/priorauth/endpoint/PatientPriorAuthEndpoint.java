package org.hl7.davinci.priorauth.endpoint;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;

import org.hl7.davinci.priorauth.App;
import org.hl7.davinci.priorauth.Audit;
import org.hl7.davinci.priorauth.FhirUtils;
import org.hl7.davinci.priorauth.PALogger;
import org.hl7.davinci.priorauth.Audit.AuditEventOutcome;
import org.hl7.davinci.priorauth.Audit.AuditEventType;
import org.hl7.davinci.priorauth.Database.Table;
import org.hl7.davinci.priorauth.authorization.AuthUtils;
import org.hl7.davinci.priorauth.bfd.BfdClientService;
import org.hl7.davinci.priorauth.bfd.BfdToPasTransformer;
import org.hl7.davinci.priorauth.endpoint.Endpoint.RequestType;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.AuditEvent.AuditEventAction;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Patient Prior Authorization endpoint for CMS-0057-F compliance.
 * Provides GET /PriorAuthorization?patient={mbi} to return all PA decisions
 * for a patient, bridging PAS decisions with BFD patient demographics.
 */
@CrossOrigin
@RestController
@RequestMapping("/PriorAuthorization")
public class PatientPriorAuthEndpoint {

    static final Logger logger = PALogger.getLogger();

    @GetMapping(value = "", produces = { MediaType.APPLICATION_JSON_VALUE, "application/fhir+json" })
    public ResponseEntity<String> getPriorAuthorizationsJson(HttpServletRequest request,
            @RequestParam(name = "patient") String patientMbi) {
        return getPriorAuthorizations(patientMbi, request, RequestType.JSON);
    }

    @GetMapping(value = "", produces = { MediaType.APPLICATION_XML_VALUE, "application/fhir+xml" })
    public ResponseEntity<String> getPriorAuthorizationsXml(HttpServletRequest request,
            @RequestParam(name = "patient") String patientMbi) {
        return getPriorAuthorizations(patientMbi, request, RequestType.XML);
    }

    /**
     * Query all prior authorization decisions for a patient by MBI.
     * Returns a Bundle of ClaimResponse resources enriched with BFD patient data.
     *
     * @param patientMbi the Medicare Beneficiary Identifier
     * @param request    the HTTP request
     * @return ResponseEntity with the search result Bundle
     */
    private ResponseEntity<String> getPriorAuthorizations(String patientMbi, HttpServletRequest request, RequestType requestType) {
        logger.info("GET /PriorAuthorization?patient=" + maskMbi(patientMbi));
        App.setBaseUrl(Endpoint.getServiceBaseUrl(request));

        if (!AuthUtils.validateAccessToken(request)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{ error: \"Invalid access token. Make sure to use Authorization: Bearer (token)\" }");
        }

        AuditEventOutcome auditOutcome = AuditEventOutcome.SUCCESS;
        try {
            if (patientMbi == null || patientMbi.isEmpty()) {
                OperationOutcome error = FhirUtils.buildOutcome(IssueSeverity.ERROR, IssueType.REQUIRED,
                        "Patient MBI is required");
                String formattedData = FhirUtils.getFormattedData(error, requestType);
                auditOutcome = AuditEventOutcome.MINOR_FAILURE;
                Audit.createAuditEvent(AuditEventType.REST, AuditEventAction.R, auditOutcome, null, request,
                        "GET /PriorAuthorization?patient=" + maskMbi(patientMbi));
                MediaType errorContentType = requestType == RequestType.JSON ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_XML;
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .contentType(errorContentType)
                        .body(formattedData);
            }

            // Search for ClaimResponses by patient identifier
            Map<String, Object> constraintMap = new HashMap<>();
            constraintMap.put("patient", patientMbi);
            Bundle searchResults = App.getDB().search(Table.CLAIM_RESPONSE, constraintMap);

            // Build response bundle
            Bundle responseBundle = new Bundle();
            responseBundle.setType(BundleType.SEARCHSET);
            responseBundle.setTimestamp(new Date());
            Meta meta = new Meta();
            meta.addProfile("http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-response-bundle");
            responseBundle.setMeta(meta);

            // Try to enrich with BFD patient data if available
            Patient bfdPatient = fetchBfdPatient(patientMbi);

            int total = 0;
            if (searchResults.hasEntry()) {
                for (BundleEntryComponent entry : searchResults.getEntry()) {
                    if (entry.hasResource() && entry.getResource() instanceof Bundle) {
                        // Each stored entry is a response bundle; extract ClaimResponse
                        Bundle storedBundle = (Bundle) entry.getResource();
                        ClaimResponse claimResponse = FhirUtils.getClaimResponseFromResponseBundle(storedBundle);
                        if (claimResponse != null) {
                            // Enrich with BFD data if available
                            if (bfdPatient != null) {
                                claimResponse = BfdToPasTransformer.enrichClaimResponseWithBfdData(
                                        claimResponse, bfdPatient);
                            }
                            BundleEntryComponent responseEntry = new BundleEntryComponent();
                            responseEntry.setResource(claimResponse);
                            responseEntry.setFullUrl(App.getBaseUrl() + "/ClaimResponse/" +
                                    FhirUtils.getIdFromResource(claimResponse));
                            responseBundle.addEntry(responseEntry);
                            total++;
                        }
                    } else if (entry.hasResource() && entry.getResource() instanceof ClaimResponse) {
                        ClaimResponse claimResponse = (ClaimResponse) entry.getResource();
                        if (bfdPatient != null) {
                            claimResponse = BfdToPasTransformer.enrichClaimResponseWithBfdData(
                                    claimResponse, bfdPatient);
                        }
                        BundleEntryComponent responseEntry = new BundleEntryComponent();
                        responseEntry.setResource(claimResponse);
                        responseEntry.setFullUrl(App.getBaseUrl() + "/ClaimResponse/" +
                                FhirUtils.getIdFromResource(claimResponse));
                        responseBundle.addEntry(responseEntry);
                        total++;
                    }
                }
            }
            responseBundle.setTotal(total);

            // Add BFD patient to response if available
            if (bfdPatient != null) {
                BundleEntryComponent patientEntry = new BundleEntryComponent();
                patientEntry.setResource(bfdPatient);
                patientEntry.setFullUrl(App.getBaseUrl() + "/Patient/" +
                        FhirUtils.getIdFromResource(bfdPatient));
                responseBundle.addEntry(patientEntry);
            }

            String formattedData = FhirUtils.getFormattedData(responseBundle, requestType);
            MediaType contentType = requestType == RequestType.JSON ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_XML;
            Audit.createAuditEvent(AuditEventType.REST, AuditEventAction.R, auditOutcome, null, request,
                    "GET /PriorAuthorization?patient=" + maskMbi(patientMbi));
            return ResponseEntity.status(HttpStatus.OK)
                    .contentType(contentType)
                    .body(formattedData);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "PatientPriorAuthEndpoint::getPriorAuthorizations:Exception", e);
            OperationOutcome error = FhirUtils.buildOutcome(IssueSeverity.FATAL, IssueType.EXCEPTION,
                    "Internal server error: " + e.getMessage());
            auditOutcome = AuditEventOutcome.SERIOUS_FAILURE;
            Audit.createAuditEvent(AuditEventType.REST, AuditEventAction.R, auditOutcome, null, request,
                    "GET /PriorAuthorization?patient=" + maskMbi(patientMbi));
            MediaType errorContentType = requestType == RequestType.JSON ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_XML;
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(errorContentType)
                    .body(FhirUtils.getFormattedData(error, requestType));
        }
    }

    /**
     * Fetch patient from BFD by MBI, if BFD integration is enabled.
     */
    private Patient fetchBfdPatient(String mbi) {
        try {
            BfdClientService bfdClient = App.getBfdClientService();
            if (bfdClient == null) {
                logger.info("PatientPriorAuthEndpoint::BFD integration not enabled, skipping patient enrichment");
                return null;
            }

            return bfdClient.getPatientByMbi(mbi);
        } catch (Exception e) {
            logger.log(Level.WARNING, "PatientPriorAuthEndpoint::Failed to fetch BFD patient", e);
            return null;
        }
    }

    /**
     * Mask MBI for logging (show only first 4 characters).
     */
    private String maskMbi(String mbi) {
        if (mbi == null || mbi.length() <= 4) {
            return "****";
        }
        return mbi.substring(0, 4) + "****";
    }
}
