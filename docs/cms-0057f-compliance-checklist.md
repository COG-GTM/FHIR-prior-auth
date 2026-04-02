# CMS-0057-F Compliance Checklist

## Prior Authorization API Requirements

### Patient Access API (CMS-9115-F / CMS-0057-F)

- [x] Patient Access API exposes prior authorization decisions (status, reasons, timelines)
  - **Implementation**: `PatientPriorAuthEndpoint.java` - `GET /PriorAuthorization?patient={mbi}`
  - Returns Bundle of ClaimResponse resources with disposition, status, and timeline data

- [x] PA data linked to BFD beneficiary identifiers (MBI)
  - **Implementation**: `BfdToPasTransformer.java` - MBI extracted and preserved across transformations
  - ClaimEndpoint enhanced to add BFD beneficiary ID as cross-reference identifier

- [x] PA status includes disposition (approved, denied, pending, partial, cancelled)
  - **Implementation**: `FhirUtils.Disposition` enum with X12 review action mapping
  - `PasToBfdTransformer.java` maps dispositions to adjudication categories

### Prior Authorization Support (Da Vinci PAS IG)

- [x] Prior Auth API supports `$submit` operation per Da Vinci PAS IG
  - **Implementation**: `ClaimEndpoint.java` - `POST /Claim/$submit`
  - Accepts Bundle with Claim, Patient, and supporting resources

- [x] Prior Auth API supports `$inquire` operation per Da Vinci PAS IG
  - **Implementation**: `ClaimInquiryEndpoint.java` - `POST /Claim/$inquire`
  - Returns current status of previously submitted claims

- [x] Subscription notifications work for pended claims
  - **Implementation**: `SubscriptionEndpoint.java` + `UpdateClaimTask.java`
  - Supports REST-Hook and WebSocket notification channels

- [x] ClaimResponse includes review action codes (X12 HCR01)
  - **Implementation**: `ClaimResponseFactory.java` + `FhirUtils.ReviewAction`
  - Maps: APPROVED=A1, PARTIAL=A2, DENIED=A3, PENDED=A4, CANCELLED=A6

### BFD Integration

- [x] BFD client service with mTLS authentication support
  - **Implementation**: `BfdClientService.java` + `BfdConfiguration.java`
  - Configurable via environment variables: BFD_SERVER_URL, BFD_CLIENT_CERT_PATH, etc.

- [x] BFD Patient → PAS Claim transformation
  - **Implementation**: `BfdToPasTransformer.transformPatientToPasClaim()`
  - Maps MBI identifiers, demographics, and EOB data to PAS Claim format

- [x] BFD EOB → PAS Claim.item transformation
  - **Implementation**: `BfdToPasTransformer.mapBfdEobToPasClaimItem()`
  - Maps diagnosis codes, procedure codes, and service types

- [x] BFD Coverage → PAS Claim.insurance transformation
  - **Implementation**: `BfdToPasTransformer.mapBfdCoverageToPasInsurance()`
  - Maps coverage status, type, and payor information

- [x] PAS ClaimResponse → BFD EOB extension transformation
  - **Implementation**: `PasToBfdTransformer.transformClaimResponseToEobExtension()`
  - Creates PA status extensions compatible with CARIN IG

- [x] BFD patient validation in claim submission workflow
  - **Implementation**: `ClaimEndpoint.validateAndEnrichWithBfd()`
  - Non-blocking validation: continues if BFD is unavailable

### Authorization and Security

- [x] SMART on FHIR Backend Services authorization
  - **Implementation**: `AuthUtils.java` + OAuth2 token endpoints
  - CapabilityStatement declares token and authorize endpoints

- [ ] SAMHSA filtering applied to PA data (42 CFR Part 2)
  - **Status**: Identified as gap — BFD applies SAMHSA filtering, but PAS RI does not yet
  - **Action Required**: Implement SAMHSA-sensitive data filtering in PAS responses

### Infrastructure

- [x] Production database (PostgreSQL) support
  - **Implementation**: `Database.java` modified to support PostgreSQL via environment variables
  - Configurable: DB_TYPE, DB_URL, DB_USER, DB_PASSWORD
  - H2 retained as default for development/testing

- [x] Request mapping table cached for performance
  - **Implementation**: `ClaimResponseFactory.initializeRequestMapping()`
  - Loaded once at startup instead of on every request

- [x] CapabilityStatement updated with PAS and CARIN IG conformance
  - **Implementation**: `Metadata.java` updated with PriorAuthorization endpoint
  - Declares conformance to Da Vinci PAS IG and CARIN IG for Blue Button

### Testing and Validation

- [x] Unit tests for BFD client service
  - **Implementation**: `BfdClientServiceTest.java`
  - Tests instantiation, configuration, and graceful failure handling

- [x] Unit tests for BFD→PAS transformer
  - **Implementation**: `BfdToPasTransformerTest.java`
  - Tests Patient→Claim, EOB→item, Coverage→insurance mappings

- [x] Unit tests for PAS→BFD transformer
  - **Implementation**: `PasToBfdTransformerTest.java`
  - Tests disposition→adjudication mapping for all 5 disposition types

- [x] Integration tests for PriorAuthorization endpoint
  - **Implementation**: `PatientPriorAuthEndpointTest.java`
  - Tests endpoint responses, error handling, and data format

- [x] Validation script for end-to-end testing
  - **Implementation**: `scripts/validate-integration.sh`
  - Automated verification of server health, operations, and error handling

## Future Phase Requirements

### Provider Access API (Future)

- [ ] Provider-facing API for PA status queries
  - **Status**: Not yet implemented — requires provider authentication model
  - **Target**: Phase 2

### Payer-to-Payer API (Future)

- [ ] Payer-to-payer PA data exchange
  - **Status**: Not yet implemented — requires payer federation model
  - **Target**: Phase 3

### Additional Enhancements

- [ ] Database migration scripts (Flyway)
  - **Status**: PostgreSQL support added, but no automated migration tooling
  - **Action Required**: Add Flyway dependency and migration scripts

- [ ] FHIR profile validation against PAS IG StructureDefinitions
  - **Status**: Basic FHIR validation available, profile-specific validation not enforced
  - **Action Required**: Integrate IG-specific validator

- [ ] Audit logging for PA data access (HIPAA compliance)
  - **Status**: Basic audit events via `Audit.java`, needs enhancement for HIPAA
  - **Action Required**: Add detailed access logging with PHI tracking

## Compliance Summary

| Requirement | Status | Notes |
|---|---|---|
| Patient Access API for PA | Implemented | GET /PriorAuthorization?patient={mbi} |
| $submit operation | Implemented | POST /Claim/$submit |
| $inquire operation | Implemented | POST /Claim/$inquire |
| Subscription notifications | Implemented | REST-Hook + WebSocket |
| BFD beneficiary linking | Implemented | MBI-based cross-referencing |
| SMART on FHIR auth | Implemented | OAuth2 + Backend Services |
| PostgreSQL support | Implemented | Configurable via env vars |
| SAMHSA filtering | Not Yet | Needs implementation |
| Provider Access API | Future Phase | Phase 2 |
| Payer-to-Payer API | Future Phase | Phase 3 |
