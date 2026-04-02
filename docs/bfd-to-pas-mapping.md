# BFD to Da Vinci PAS Data Mapping

## Overview

This document provides field-level mappings between BFD FHIR resources and Da Vinci PAS resource requirements. It identifies how BFD data satisfies PAS input/output requirements and documents gaps where BFD data alone cannot fulfill PAS needs.

---

## 1. BFD Patient → PAS Claim.patient

| BFD Patient Field | PAS Claim Field | Mapping Notes |
|-------------------|----------------|---------------|
| `Patient.identifier[MBI]` | `Claim.patient.identifier` | MBI as primary linkage key; system `http://hl7.org/fhir/sid/us-mbi` |
| `Patient.id` (beneficiary ID) | `Claim.patient.reference` | Internal reference `Patient/{beneId}` |
| `Patient.name` | Bundle: `Patient.name` | Included in PAS request bundle |
| `Patient.birthDate` | Bundle: `Patient.birthDate` | Included for patient matching |
| `Patient.gender` | Bundle: `Patient.gender` | Included for patient matching |
| `Patient.address` | Bundle: `Patient.address` | Service location context |

**MBI as Primary Identifier:**
- PAS uses `Claim.patient` as a reference to a Patient resource in the bundle
- The Patient resource must carry the MBI identifier for BFD cross-referencing
- `FhirUtils.getPatientIdentifierFromBundle()` in PAS extracts the patient identifier from the first Claim entry

---

## 2. BFD EOB Claim Types → PAS Claim.type

| BFD EOB Claim Type | BFD Code | PAS Claim.type Code | PAS Category |
|--------------------|----------|---------------------|--------------|
| Carrier | `71` | `professional` | Professional services |
| Inpatient | `60` | `institutional` | Institutional - inpatient |
| Outpatient | `40` | `institutional` | Institutional - outpatient |
| SNF | `20` | `institutional` | Institutional - SNF |
| HHA | `10` | `institutional` | Institutional - home health |
| Hospice | `50` | `institutional` | Institutional - hospice |
| DME | `82` | `professional` | Professional - DME |
| Part D | N/A | `pharmacy` | Pharmacy (limited PA applicability) |

**PAS Claim.type System:** `http://terminology.hl7.org/CodeSystem/claim-type`

**Mapping Logic:**
- FISS claims (institutional: Inpatient, Outpatient, SNF, HHA, Hospice) → `institutional`
- MCS claims (professional: Carrier, DME) → `professional`
- Part D claims → `pharmacy` (rarely subject to PA in current scope)

---

## 3. BFD Diagnosis/Procedure Codes → PAS Claim.item.productOrService

### 3.1 Diagnosis Code Mapping

| BFD EOB Field | PAS Claim Field | System |
|---------------|----------------|--------|
| `EOB.diagnosis[].diagnosisCodeableConcept` | `Claim.diagnosis[].diagnosisCodeableConcept` | `http://hl7.org/fhir/sid/icd-10-cm` |
| `EOB.diagnosis[].sequence` | `Claim.diagnosis[].sequence` | Integer sequence |
| `EOB.diagnosis[].type` | `Claim.diagnosis[].type` | Principal, admitting, etc. |

### 3.2 Procedure/Service Code Mapping

| BFD EOB Field | PAS Claim.item Field | System |
|---------------|---------------------|--------|
| `EOB.item[].productOrService` | `Claim.item[].productOrService` | CPT: `http://www.ama-assn.org/go/cpt` |
| `EOB.procedure[].procedureCodeableConcept` | `Claim.procedure[].procedureCodeableConcept` | ICD-10-PCS, HCPCS |
| `EOB.item[].servicedDate` | `Claim.item[].servicedDate` | Service date |
| `EOB.item[].servicedPeriod` | `Claim.item[].servicedPeriod` | Service period |
| `EOB.item[].quantity` | `Claim.item[].quantity` | Units of service |

**PAS Rules Engine Integration:**
- `ProcessClaimItemTask` evaluates each `Claim.item` against CQL rules
- `PriorAuthRule.computeDisposition()` uses `item.productOrService` codes to determine PA outcome
- `requestMappingTable.json` maps specific CPT codes to content modifiers and trace numbers

---

## 4. BFD Provider Data → PAS Claim.provider

| BFD EOB Field | PAS Claim Field | Notes |
|---------------|----------------|-------|
| `EOB.provider` (NPI reference) | `Claim.provider` | Requesting provider reference |
| `EOB.careTeam[].provider` (NPI) | Bundle: `PractitionerRole` / `Practitioner` | Individual provider in bundle |
| `EOB.careTeam[].role` | Bundle: `PractitionerRole.code` | Rendering, referring, attending |
| `EOB.facility` | `Claim.facility` | Service facility reference |
| `EOB.organization` | Bundle: `Organization` | Provider organization in bundle |

**PAS Provider Requirements:**
- PAS `ClaimEndpoint.submitOperation()` extracts the requestor from `Claim.provider`
- The requestor reference must resolve to an `Organization` or `PractitionerRole` in the bundle
- `UpdateClaimTask` uses the requestor's identifier (system|value) to match subscriptions

---

## 5. BFD Coverage → PAS Claim.insurance

| BFD Coverage Field | PAS Claim.insurance Field | Notes |
|-------------------|--------------------------|-------|
| `Coverage.id` | `Claim.insurance[].coverage` | Reference to Coverage resource |
| `Coverage.status` | `Coverage.status` | Active/cancelled |
| `Coverage.type` | `Coverage.type` | Part A, B, C, D |
| `Coverage.beneficiary` | `Coverage.beneficiary` | Patient reference |
| `Coverage.payor[]` | `Coverage.payor[]` | CMS as payor |
| `Coverage.period` | `Coverage.period` | Coverage dates |
| `Coverage.class[]` | `Coverage.class[]` | Group/plan information |

**PAS Insurance Requirements:**
- At least one `Claim.insurance` entry required
- Coverage resource must be included in the PAS request bundle
- `focal = true` for the primary insurance

---

## 6. PAC Claim/ClaimResponse → PAS ClaimResponse Mapping

### 6.1 FISS/MCS Status → PAS Disposition

| FISS/MCS Status | PAS Disposition | PAS ReviewAction Code | Description |
|----------------|----------------|----------------------|-------------|
| Approved/Paid | `GRANTED` | `A1` | Authorization approved |
| Partially Approved | `PARTIAL` | `A2` | Partially authorized |
| Denied | `DENIED` | `A3` | Authorization denied |
| Pending/In Review | `PENDING` | `A4` | Awaiting decision |
| Cancelled | `CANCELLED` | `A6` | Authorization cancelled |
| Follow-up Required | `PENDING` | `86` | Pended for follow-up |

### 6.2 PAS ClaimResponse Fields

| PAS ClaimResponse Field | Source | Notes |
|------------------------|--------|-------|
| `ClaimResponse.id` | Generated UUID | Unique response ID |
| `ClaimResponse.status` | Disposition → Status | `active`, `cancelled` |
| `ClaimResponse.type` | From Claim.type | Mirrors request type |
| `ClaimResponse.use` | `preauthorization` | Fixed for PA |
| `ClaimResponse.patient` | From Claim.patient | Patient reference |
| `ClaimResponse.created` | Current timestamp | Creation date |
| `ClaimResponse.insurer` | PAS configuration | Insurer organization |
| `ClaimResponse.requestor` | From Claim.provider | Requesting provider |
| `ClaimResponse.outcome` | Disposition mapping | `complete`, `partial`, `error` |
| `ClaimResponse.preAuthRef` | Generated | PA reference number |
| `ClaimResponse.item[]` | Per-item adjudication | Line item outcomes |
| `ClaimResponse.extension[reviewAction]` | ReviewAction code | X12 278 review action |

### 6.3 ClaimResponse Generation Flow

```
Claim.$submit → ClaimEndpoint.submitOperation()
  → processClaimItems() [multi-threaded per item]
    → ProcessClaimItemTask.process()
      → PriorAuthRule.computeDisposition() [CQL engine]
      → Write item outcome to CLAIM_ITEM table
  → ClaimResponseFactory.generateAndStoreClaimResponse()
    → determineDisposition() [aggregate item outcomes]
    → createClaimResponse() [build FHIR resource]
    → createClaimResponseBundle() [wrap in bundle]
    → Store in CLAIM_RESPONSE table
```

---

## 7. Gaps Where BFD Data Does Not Satisfy PAS Requirements

### 7.1 Clinical Attachments and Supporting Documentation
- **Gap:** PAS `$submit` bundles require clinical supporting information (lab results, imaging reports, clinical notes) as `Claim.supportingInfo` entries
- **BFD Limitation:** BFD provides claims/billing data only, not clinical documentation
- **Mitigation:** Clinical attachments must come from the provider's EHR system, not BFD

### 7.2 Prior Authorization Request Context
- **Gap:** PAS Claim requires explicit PA request context (urgency, review reason, requested service details)
- **BFD Limitation:** BFD EOBs represent post-adjudication claims, not prospective PA requests
- **Mitigation:** PA request-specific fields must be populated by the requesting provider

### 7.3 Real-Time Adjudication
- **Gap:** PAS expects real-time or near-real-time PA decisions
- **BFD Limitation:** BFD data is batch-loaded from CCW/RDA pipelines with processing delays
- **Mitigation:** PAS rules engine provides real-time adjudication; BFD data enriches context

### 7.4 CommunicationRequest / Questionnaire Data
- **Gap:** PAS pended responses may include `CommunicationRequest` resources requesting additional information
- **BFD Limitation:** BFD has no equivalent concept
- **Mitigation:** `ClaimResponseFactory` generates `CommunicationRequest` resources independently of BFD

### 7.5 Subscription Notifications
- **Gap:** PAS supports real-time subscription notifications (REST-hook, WebSocket) for pended claim updates
- **BFD Limitation:** BFD does not support subscriptions or push notifications
- **Mitigation:** PAS `UpdateClaimTask` handles subscription notifications independently

### 7.6 Provider Directory Data
- **Gap:** Full provider demographics, specialties, and network status
- **BFD Limitation:** BFD provides NPI references but limited provider demographic data
- **Mitigation:** Provider directory data should come from NPPES or provider enrollment systems

### 7.7 Formulary and Drug Coverage Data
- **Gap:** Pharmacy PA requires formulary information
- **BFD Limitation:** Part D events show historical fills but not formulary/coverage rules
- **Mitigation:** Formulary data requires separate data source (e.g., CMS Plan Finder)

---

## 8. Integration Architecture Summary

```
                    ┌─────────────────────┐
                    │   Provider System    │
                    │  (EHR/PMS)          │
                    └────────┬────────────┘
                             │ $submit (PAS Bundle)
                             ▼
                    ┌─────────────────────┐
                    │   PAS RI Server     │
                    │  ┌───────────────┐  │
                    │  │ ClaimEndpoint │  │──── BFD Patient Validation
                    │  └───────┬───────┘  │
                    │          │           │
                    │  ┌───────▼───────┐  │
                    │  │ Rules Engine  │  │──── BFD Claims History
                    │  │ (CQL/ELM)    │  │     (enrichment)
                    │  └───────┬───────┘  │
                    │          │           │
                    │  ┌───────▼───────┐  │
                    │  │ClaimResponse  │  │
                    │  │  Factory      │  │
                    │  └───────┬───────┘  │
                    │          │           │
                    │  ┌───────▼───────┐  │
                    │  │  Database     │  │──── PostgreSQL (production)
                    │  │  (H2/PG)     │  │     H2 (development)
                    │  └───────────────┘  │
                    └─────────────────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
     Patient Access    Provider Access   Payer-to-Payer
     API (CMS-0057-F)  API (future)     API (future)
              │
              ▼
     ┌─────────────────┐
     │ BFD FHIR Server │ ◄── mTLS
     │ (read-only)     │
     └─────────────────┘
```
