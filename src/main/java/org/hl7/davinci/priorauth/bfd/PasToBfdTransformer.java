package org.hl7.davinci.priorauth.bfd;

import java.util.logging.Logger;

import org.hl7.davinci.priorauth.FhirUtils.Disposition;
import org.hl7.davinci.priorauth.PALogger;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.StringType;

/**
 * Transforms PAS (Prior Authorization Support) decisions back to BFD-compatible format.
 * Maps PAS ClaimResponse dispositions to BFD EOB adjudication codes and extensions
 * conforming to the CARIN IG for Blue Button.
 */
public class PasToBfdTransformer {

    private static final Logger logger = PALogger.getLogger();

    private static final String CARIN_ADJUDICATION_SYSTEM = "http://hl7.org/fhir/us/carin-bb/CodeSystem/C4BBAdjudicationDiscriminator";
    private static final String PA_STATUS_EXTENSION_URL = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-paStatus";
    private static final String PA_REVIEW_ACTION_SYSTEM = "https://codesystem.x12.org/005010/306";

    /**
     * Transform a PAS ClaimResponse into an extension suitable for a BFD EOB resource.
     * Creates an extension containing the prior authorization status that can be
     * added to an EOB to indicate the PA decision.
     *
     * @param pasResponse the PAS ClaimResponse containing the PA decision
     * @return Extension for BFD EOB with PA status, or null if input is invalid
     */
    public static Extension transformClaimResponseToEobExtension(ClaimResponse pasResponse) {
        if (pasResponse == null) {
            logger.warning("PasToBfdTransformer::transformClaimResponseToEobExtension: ClaimResponse is null");
            return null;
        }

        Extension paExtension = new Extension();
        paExtension.setUrl(PA_STATUS_EXTENSION_URL);

        // Add disposition as sub-extension
        if (pasResponse.hasDisposition()) {
            Extension dispositionExt = new Extension();
            dispositionExt.setUrl("disposition");
            dispositionExt.setValue(new StringType(pasResponse.getDisposition()));
            paExtension.addExtension(dispositionExt);
        }

        // Add preAuthRef as sub-extension
        if (pasResponse.hasPreAuthRef()) {
            Extension preAuthRefExt = new Extension();
            preAuthRefExt.setUrl("preAuthRef");
            preAuthRefExt.setValue(new StringType(pasResponse.getPreAuthRef()));
            paExtension.addExtension(preAuthRefExt);
        }

        // Add outcome as sub-extension
        if (pasResponse.hasOutcome()) {
            Extension outcomeExt = new Extension();
            outcomeExt.setUrl("outcome");
            outcomeExt.setValue(new StringType(pasResponse.getOutcome().toCode()));
            paExtension.addExtension(outcomeExt);
        }

        // Add review action code based on disposition
        Disposition disposition = Disposition.fromString(pasResponse.getDisposition());
        if (disposition != null) {
            CodeableConcept reviewAction = mapDispositionToAdjudicationCategory(disposition);
            if (reviewAction != null) {
                Extension reviewActionExt = new Extension();
                reviewActionExt.setUrl("reviewAction");
                reviewActionExt.setValue(reviewAction);
                paExtension.addExtension(reviewActionExt);
            }
        }

        logger.info("PasToBfdTransformer::transformClaimResponseToEobExtension: Created EOB extension for " +
                pasResponse.getDisposition());
        return paExtension;
    }

    /**
     * Map a PAS Disposition to a BFD EOB adjudication category CodeableConcept.
     * Maps PAS dispositions (GRANTED, DENIED, PARTIAL, PENDING, CANCELLED) to
     * X12 review action codes and CARIN adjudication categories.
     *
     * @param disposition the PAS disposition value
     * @return CodeableConcept with the adjudication category, or null for unknown
     */
    public static CodeableConcept mapDispositionToAdjudicationCategory(Disposition disposition) {
        if (disposition == null) {
            return null;
        }

        CodeableConcept adjudication = new CodeableConcept();

        // Map to X12 Review Action Code
        String x12Code;
        String x12Display;
        switch (disposition) {
            case GRANTED:
                x12Code = "A1";
                x12Display = "Certified in total";
                break;
            case DENIED:
                x12Code = "A3";
                x12Display = "Not Certified";
                break;
            case PARTIAL:
                x12Code = "A2";
                x12Display = "Certified - partial";
                break;
            case PENDING:
                x12Code = "A4";
                x12Display = "Pended";
                break;
            case CANCELLED:
                x12Code = "A6";
                x12Display = "Modified";
                break;
            default:
                logger.warning("PasToBfdTransformer::mapDispositionToAdjudicationCategory: Unknown disposition " +
                        disposition.value());
                return null;
        }

        // Add X12 coding
        adjudication.addCoding(new Coding()
                .setSystem(PA_REVIEW_ACTION_SYSTEM)
                .setCode(x12Code)
                .setDisplay(x12Display));

        // Add CARIN adjudication discriminator
        String carinCode = mapDispositionToCarinCode(disposition);
        if (carinCode != null) {
            adjudication.addCoding(new Coding()
                    .setSystem(CARIN_ADJUDICATION_SYSTEM)
                    .setCode(carinCode)
                    .setDisplay(disposition.value()));
        }

        adjudication.setText(disposition.value());

        return adjudication;
    }

    /**
     * Map a Disposition to a CARIN IG adjudication code.
     */
    private static String mapDispositionToCarinCode(Disposition disposition) {
        switch (disposition) {
            case GRANTED:
                return "priorauth-approved";
            case DENIED:
                return "priorauth-denied";
            case PARTIAL:
                return "priorauth-partial";
            case PENDING:
                return "priorauth-pended";
            case CANCELLED:
                return "priorauth-cancelled";
            default:
                return null;
        }
    }
}
