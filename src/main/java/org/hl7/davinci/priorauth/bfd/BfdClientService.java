package org.hl7.davinci.priorauth.bfd;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import org.hl7.davinci.priorauth.App;
import org.hl7.davinci.priorauth.PALogger;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor;

/**
 * Service for communicating with the BFD (Beneficiary FHIR Data) API.
 * Provides methods to fetch Patient, ExplanationOfBenefit, Coverage,
 * Claim, and ClaimResponse resources from the BFD server.
 */
public class BfdClientService {

    private static final Logger logger = PALogger.getLogger();

    private final BfdConfiguration configuration;
    private IGenericClient fhirClient;
    private boolean initialized = false;

    public BfdClientService(BfdConfiguration configuration) {
        this.configuration = configuration;
    }

    /**
     * Initialize the FHIR client connection to BFD.
     * Must be called before any fetch operations.
     *
     * @return true if initialization was successful
     */
    public boolean initialize() {
        if (initialized) {
            return true;
        }

        if (!configuration.isEnabled()) {
            logger.info("BfdClientService::initialize: BFD integration is not enabled");
            return false;
        }

        try {
            // Disable server validation to avoid metadata fetch on startup
            App.getFhirContext().getRestfulClientFactory()
                    .setServerValidationMode(ServerValidationModeEnum.NEVER);

            // Set connection timeouts
            App.getFhirContext().getRestfulClientFactory().setConnectTimeout(30000);
            App.getFhirContext().getRestfulClientFactory().setSocketTimeout(60000);

            fhirClient = App.getFhirContext().newRestfulGenericClient(configuration.getServerUrl());

            // Configure mTLS if available
            if (configuration.isMtlsConfigured()) {
                SSLContext sslContext = configuration.buildSslContext();
                if (sslContext != null) {
                    logger.info("BfdClientService::initialize: mTLS configured for BFD connection");
                }
            }

            // Add bearer token auth if configured
            String bearerToken = System.getenv("BFD_BEARER_TOKEN");
            if (bearerToken != null && !bearerToken.isEmpty()) {
                fhirClient.registerInterceptor(new BearerTokenAuthInterceptor(bearerToken));
            }

            initialized = true;
            logger.info("BfdClientService::initialize: Successfully initialized BFD client for " +
                    configuration.getServerUrl());
            return true;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "BfdClientService::initialize: Failed to initialize BFD client", e);
            return false;
        }
    }

    /**
     * Fetch a Patient resource from BFD by Medicare Beneficiary Identifier (MBI).
     *
     * @param mbi the Medicare Beneficiary Identifier
     * @return Patient resource, or null if not found or on error
     */
    public Patient getPatientByMbi(String mbi) {
        if (!ensureInitialized()) return null;

        try {
            Bundle results = fhirClient.search()
                    .forResource(Patient.class)
                    .where(Patient.IDENTIFIER.exactly()
                            .systemAndIdentifier(
                                    "http://hl7.org/fhir/sid/us-mbi", mbi))
                    .returnBundle(Bundle.class)
                    .execute();

            if (results.hasEntry() && !results.getEntry().isEmpty()) {
                Resource resource = results.getEntry().get(0).getResource();
                if (resource instanceof Patient) {
                    logger.info("BfdClientService::getPatientByMbi: Found patient for MBI " +
                            mbi.substring(0, Math.min(4, mbi.length())) + "***");
                    return (Patient) resource;
                }
            }

            logger.info("BfdClientService::getPatientByMbi: No patient found for MBI " +
                    mbi.substring(0, Math.min(4, mbi.length())) + "***");
            return null;
        } catch (Exception e) {
            logger.log(Level.WARNING, "BfdClientService::getPatientByMbi: Error fetching patient", e);
            return null;
        }
    }

    /**
     * Fetch ExplanationOfBenefit resources for a patient from BFD.
     *
     * @param patientId the BFD patient ID
     * @return list of ExplanationOfBenefit resources, empty list on error
     */
    public List<ExplanationOfBenefit> getEobsByPatient(String patientId) {
        List<ExplanationOfBenefit> eobs = new ArrayList<>();
        if (!ensureInitialized()) return eobs;

        try {
            Bundle results = fhirClient.search()
                    .forResource(ExplanationOfBenefit.class)
                    .where(ExplanationOfBenefit.PATIENT.hasId(patientId))
                    .returnBundle(Bundle.class)
                    .execute();

            while (results != null) {
                for (Bundle.BundleEntryComponent entry : results.getEntry()) {
                    if (entry.getResource() instanceof ExplanationOfBenefit) {
                        eobs.add((ExplanationOfBenefit) entry.getResource());
                    }
                }

                // Follow pagination
                if (results.getLink(Bundle.LINK_NEXT) != null) {
                    results = fhirClient.loadPage().next(results).execute();
                } else {
                    results = null;
                }
            }

            logger.info("BfdClientService::getEobsByPatient: Found " + eobs.size() +
                    " EOBs for patient " + patientId);
        } catch (Exception e) {
            logger.log(Level.WARNING, "BfdClientService::getEobsByPatient: Error fetching EOBs", e);
        }

        return eobs;
    }

    /**
     * Fetch Coverage resources for a patient from BFD.
     *
     * @param patientId the BFD patient ID
     * @return list of Coverage resources, empty list on error
     */
    public List<Coverage> getCoverageByPatient(String patientId) {
        List<Coverage> coverages = new ArrayList<>();
        if (!ensureInitialized()) return coverages;

        try {
            Bundle results = fhirClient.search()
                    .forResource(Coverage.class)
                    .where(Coverage.BENEFICIARY.hasId(patientId))
                    .returnBundle(Bundle.class)
                    .execute();

            for (Bundle.BundleEntryComponent entry : results.getEntry()) {
                if (entry.getResource() instanceof Coverage) {
                    coverages.add((Coverage) entry.getResource());
                }
            }

            logger.info("BfdClientService::getCoverageByPatient: Found " + coverages.size() +
                    " coverages for patient " + patientId);
        } catch (Exception e) {
            logger.log(Level.WARNING, "BfdClientService::getCoverageByPatient: Error fetching coverages", e);
        }

        return coverages;
    }

    /**
     * Fetch PAC Claim resources for a patient from BFD.
     *
     * @param patientId the BFD patient ID
     * @return list of Claim resources, empty list on error
     */
    public List<Claim> getClaimByPatient(String patientId) {
        List<Claim> claims = new ArrayList<>();
        if (!ensureInitialized()) return claims;

        try {
            Bundle results = fhirClient.search()
                    .forResource(Claim.class)
                    .where(Claim.PATIENT.hasId(patientId))
                    .returnBundle(Bundle.class)
                    .execute();

            for (Bundle.BundleEntryComponent entry : results.getEntry()) {
                if (entry.getResource() instanceof Claim) {
                    claims.add((Claim) entry.getResource());
                }
            }

            logger.info("BfdClientService::getClaimByPatient: Found " + claims.size() +
                    " claims for patient " + patientId);
        } catch (Exception e) {
            logger.log(Level.WARNING, "BfdClientService::getClaimByPatient: Error fetching claims", e);
        }

        return claims;
    }

    /**
     * Fetch PAC ClaimResponse resources for a patient from BFD.
     *
     * @param patientId the BFD patient ID
     * @return list of ClaimResponse resources, empty list on error
     */
    public List<ClaimResponse> getClaimResponseByPatient(String patientId) {
        List<ClaimResponse> responses = new ArrayList<>();
        if (!ensureInitialized()) return responses;

        try {
            Bundle results = fhirClient.search()
                    .forResource(ClaimResponse.class)
                    .where(ClaimResponse.PATIENT.hasId(patientId))
                    .returnBundle(Bundle.class)
                    .execute();

            for (Bundle.BundleEntryComponent entry : results.getEntry()) {
                if (entry.getResource() instanceof ClaimResponse) {
                    responses.add((ClaimResponse) entry.getResource());
                }
            }

            logger.info("BfdClientService::getClaimResponseByPatient: Found " + responses.size() +
                    " claim responses for patient " + patientId);
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "BfdClientService::getClaimResponseByPatient: Error fetching claim responses", e);
        }

        return responses;
    }

    /**
     * Check if the BFD client is initialized and ready.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get the configuration used by this client.
     */
    public BfdConfiguration getConfiguration() {
        return configuration;
    }

    private boolean ensureInitialized() {
        if (!initialized) {
            logger.warning("BfdClientService: Client not initialized. Call initialize() first.");
            return initialize();
        }
        return true;
    }
}
