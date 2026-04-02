# BFD FHIR Data Analysis

## Overview

The Beneficiary FHIR Data (BFD) server is a CMS-managed FHIR R4 API that exposes Medicare beneficiary data including demographics, claims, coverage, and adjudication results. This document catalogs the FHIR resource types, identifiers, claim taxonomies, search parameters, and data elements relevant to the Prior Authorization Support (PAS) integration.

---

## 1. FHIR Resource Types and Key Fields

### 1.1 Patient (`R4PatientResourceProvider`)

| Field | Description |
|-------|-------------|
| `id` | Internal BFD beneficiary ID (numeric) |
| `identifier[MBI]` | Medicare Beneficiary Identifier (unhashed), system: `http://hl7.org/fhir/sid/us-mbi` |
| `identifier[MBI_HASH]` | Hashed MBI for secure lookups |
| `identifier[HICN_HASH]` | Hashed Health Insurance Claim Number (legacy) |
| `name` | Beneficiary name (family, given) |
| `gender` | Administrative gender |
| `birthDate` | Date of birth |
| `address` | Beneficiary address (state, zip, county) |
| `extension[race]` | US Core Race extension |
| `extension[ethnicity]` | US Core Ethnicity extension |

**Supported Operations:**
- `read(id)` - Read by internal beneficiary ID
- `searchByIdentifier(identifier)` - Search by MBI hash or HICN hash
- `searchByLogicalId(_id)` - Search by internal ID
- `searchByCoverageContract(contractId)` - Search by coverage contract

**Transformer:** `BeneficiaryTransformerV2` converts JPA `Beneficiary` entities to FHIR `Patient` resources.

### 1.2 ExplanationOfBenefit (`R4ExplanationOfBenefitResourceProvider`)

| Field | Description |
|-------|-------------|
| `id` | EOB resource ID (format: `{claimType}-{claimId}`) |
| `patient` | Reference to Patient resource |
| `type` | Claim type coding (see Section 3) |
| `status` | EOB status (active, cancelled) |
| `use` | Use code (claim) |
| `created` | Date created |
| `insurer` | Reference to insurer Organization |
| `provider` | Reference to provider (NPI) |
| `facility` | Reference to facility |
| `diagnosis[]` | Diagnosis codes (ICD-10-CM) |
| `procedure[]` | Procedure codes (ICD-10-PCS, CPT, HCPCS) |
| `item[]` | Line items with service codes, dates, quantities |
| `adjudication[]` | Payment amounts, allowed amounts, deductibles |
| `total[]` | Total amounts |
| `payment` | Payment information |
| `careTeam[]` | Care team members with NPIs and roles |

**Supported Operations:**
- `read(id)` - Read by EOB ID
- `findByPatient(patient, type, _lastUpdated, service-date, excludeSAMHSA)` - Search by patient

**Eight Transformer Classes:**
1. `CarrierClaimTransformerV2` - Professional/physician claims
2. `InpatientClaimTransformerV2` - Inpatient hospital claims
3. `OutpatientClaimTransformerV2` - Outpatient hospital claims
4. `SNFClaimTransformerV2` - Skilled Nursing Facility claims
5. `HHAClaimTransformerV2` - Home Health Agency claims
6. `HospiceClaimTransformerV2` - Hospice claims
7. `DMEClaimTransformerV2` - Durable Medical Equipment claims
8. `PartDEventTransformerV2` - Part D (prescription drug) claims

### 1.3 Coverage (`R4CoverageResourceProvider`)

| Field | Description |
|-------|-------------|
| `id` | Coverage ID (format: `{segment}-{beneficiaryId}`, e.g., `part-a-1234`) |
| `status` | Coverage status |
| `type` | Coverage type (Part A, Part B, Part C, Part D) |
| `beneficiary` | Reference to Patient |
| `payor[]` | Payor organization references |
| `period` | Coverage period (start, end) |
| `class[]` | Coverage class (group, plan) |
| `relationship` | Subscriber relationship |

**Supported Operations:**
- `read(id)` - Read by coverage ID
- `searchByBeneficiary(beneficiary)` - Search by beneficiary reference

**Transformer:** `CoverageTransformerV2` converts beneficiary enrollment data to FHIR `Coverage` resources. Supports C4DIC (CARIN Digital Insurance Card) profiles.

### 1.4 PAC Claim (`R4ClaimResourceProvider`)

Pre-Adjudication Claims from the RDA (Risk Data Adjustment) pipeline.

| Field | Description |
|-------|-------------|
| `id` | Claim ID (format: `{type}-{claimId}`, e.g., `f-ABC123` for FISS, `m-XYZ789` for MCS) |
| `status` | Claim status |
| `type` | Claim type (institutional/professional) |
| `patient` | Reference to Patient (via MBI) |
| `provider` | Provider reference |
| `diagnosis[]` | Diagnosis codes |
| `procedure[]` | Procedure codes |
| `item[]` | Line items |

**Claim Types:**
- `ClaimTypeV2.F` - FISS (Fiscal Intermediary Shared System) claims - institutional
- `ClaimTypeV2.M` - MCS (Multi-Carrier System) claims - professional

**Transformers:**
- `FissClaimTransformerV2` - Transforms `RdaFissClaim` entities
- `McsClaimTransformerV2` - Transforms `RdaMcsClaim` entities

### 1.5 PAC ClaimResponse (`R4ClaimResponseResourceProvider`)

Adjudication results for PAC claims.

| Field | Description |
|-------|-------------|
| `id` | ClaimResponse ID (same format as PAC Claim) |
| `status` | Response status |
| `type` | Claim type |
| `patient` | Reference to Patient |
| `outcome` | Adjudication outcome |
| `item[]` | Adjudicated line items |
| `adjudication[]` | Adjudication details |

**Transformers:**
- `FissClaimResponseTransformerV2` - Transforms FISS adjudication results
- `McsClaimResponseTransformerV2` - Transforms MCS adjudication results

---

## 2. Patient Identifier Formats

| Identifier | System URI | Format | Usage |
|-----------|-----------|--------|-------|
| MBI (Unhashed) | `http://hl7.org/fhir/sid/us-mbi` | 11-char alphanumeric (e.g., `1S00A00AA00`) | Primary identifier for patient lookup |
| MBI Hash | `https://bluebutton.cms.gov/resources/identifier/mbi-hash` | SHA-256 hash | Secure lookup without exposing MBI |
| HICN Hash | `https://bluebutton.cms.gov/resources/identifier/hicn-hash` | SHA-256 hash | Legacy identifier (deprecated) |
| Beneficiary ID | `https://bluebutton.cms.gov/resources/variables/bene_id` | Numeric | Internal BFD identifier |

**Key Finding:** MBI is the primary identifier for cross-system integration. The PAS system should use MBI as the patient linkage key when communicating with BFD.

---

## 3. Claim Type Taxonomy

### 3.1 EOB Claim Types (CCW Pipeline)

| Claim Type | Code System | Code | Description | Facility Type |
|-----------|-------------|------|-------------|---------------|
| Carrier | `https://bluebutton.cms.gov/resources/variables/nch_clm_type_cd` | `71` | Physician/supplier | Professional |
| Inpatient | Same | `60` | Inpatient hospital | Institutional |
| Outpatient | Same | `40` | Outpatient hospital | Institutional |
| SNF | Same | `20` | Skilled Nursing Facility | Institutional |
| HHA | Same | `10` | Home Health Agency | Institutional |
| Hospice | Same | `50` | Hospice | Institutional |
| DME | Same | `82` | Durable Medical Equipment | Professional |
| Part D | Same | Various | Prescription drugs | Pharmacy |

### 3.2 PAC Claim Types (RDA Pipeline)

| System | Type Label | Entity Class | Description |
|--------|-----------|-------------|-------------|
| FISS | `fiss` | `RdaFissClaim` | Institutional claims (inpatient, outpatient, SNF, HHA, hospice) |
| MCS | `mcs` | `RdaMcsClaim` | Professional claims (carrier, DME) |

### 3.3 FISS vs MCS Distinction

- **FISS (Fiscal Intermediary Shared System):** Processes institutional (Part A) claims - hospitals, SNFs, HHAs, hospice facilities. Claims are identified with prefix `f-`.
- **MCS (Multi-Carrier System):** Processes professional (Part B) claims - physicians, suppliers, DME. Claims are identified with prefix `m-`.

---

## 4. Available Search Parameters

### Patient Search Parameters
| Parameter | Type | Description |
|-----------|------|-------------|
| `_id` | token | Logical beneficiary ID |
| `identifier` | token | MBI hash, HICN hash, or unhashed MBI |
| `_has:Coverage.extension` | token | Coverage contract number |
| `_lastUpdated` | date | Last update timestamp |

### EOB Search Parameters
| Parameter | Type | Description |
|-----------|------|-------------|
| `patient` | reference | Patient reference (beneficiary ID) |
| `type` | token | Claim type code |
| `_lastUpdated` | date | Last update timestamp |
| `service-date` | date | Service date range |
| `excludeSAMHSA` | token | Exclude SAMHSA-sensitive data |

### Coverage Search Parameters
| Parameter | Type | Description |
|-----------|------|-------------|
| `beneficiary` | reference | Patient reference |

### PAC Claim/ClaimResponse Search Parameters
| Parameter | Type | Description |
|-----------|------|-------------|
| `mbi` | token | MBI identifier (hashed or unhashed) |
| `_type` | token | Claim source type (`fiss` or `mcs`) |
| `_lastUpdated` | date | Last update timestamp |
| `service-date` | date | Service date range |
| `excludeSAMHSA` | token | SAMHSA exclusion flag |

---

## 5. Data Elements Relevant to Prior Authorization

### 5.1 Diagnosis Codes
- **System:** `http://hl7.org/fhir/sid/icd-10-cm` (ICD-10-CM)
- **Location:** `ExplanationOfBenefit.diagnosis[].diagnosisCodeableConcept`
- **Attributes:** sequence, type (principal, admitting, external cause), onAdmission
- **PA Relevance:** Primary and secondary diagnoses drive PA requirements

### 5.2 Procedure Codes
- **Systems:**
  - `http://www.ama-assn.org/go/cpt` (CPT)
  - `https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets` (HCPCS)
  - `http://hl7.org/fhir/sid/icd-10-pcs` (ICD-10-PCS)
- **Location:** `ExplanationOfBenefit.procedure[].procedureCodeableConcept` and `ExplanationOfBenefit.item[].productOrService`
- **PA Relevance:** Specific procedures/services requiring PA

### 5.3 Service Types
- **Location:** `ExplanationOfBenefit.type` and `ExplanationOfBenefit.item[].category`
- **PA Relevance:** Service category determines PA pathway (institutional vs professional)

### 5.4 Provider NPIs
- **System:** `http://hl7.org/fhir/sid/us-npi`
- **Location:** `ExplanationOfBenefit.provider`, `ExplanationOfBenefit.careTeam[].provider`
- **Attributes:** NPI, role (rendering, referring, supervising, operating, attending)
- **PA Relevance:** Provider identity for PA request attribution

### 5.5 Service Dates
- **Location:** `ExplanationOfBenefit.billablePeriod`, `ExplanationOfBenefit.item[].servicedDate` / `servicedPeriod`
- **PA Relevance:** Date of service for PA timeliness requirements

### 5.6 Place of Service
- **Location:** `ExplanationOfBenefit.facility`, facility type extensions
- **PA Relevance:** Service location affects PA requirements

### 5.7 SAMHSA Sensitivity
- **Mechanism:** Security tags applied to claims containing substance abuse/mental health data (42 CFR Part 2)
- **Filtering:** `excludeSAMHSA=true` parameter removes SAMHSA-tagged resources
- **PA Relevance:** PA responses must respect SAMHSA filtering when returning data through Patient Access API

---

## 6. Authentication and Access

- **Method:** Mutual TLS (mTLS) client certificate authentication
- **Configuration:** Client certificate, private key, and trust store required
- **No OAuth/SMART:** BFD uses mTLS exclusively; no Bearer token authentication
- **Implication:** PAS integration layer must configure SSL context with client certificates to access BFD API

---

## 7. CapabilityStatement Summary

The BFD server declares conformance to:
- FHIR R4 (4.0.1)
- CARIN Blue Button Implementation Guide (C4BB)
- CARIN Digital Insurance Card (C4DIC)
- US Core Patient Profile

Supported resource types: Patient, ExplanationOfBenefit, Coverage, Claim, ClaimResponse

---

## 8. Key Observations for Integration

1. **MBI is the bridge identifier** between BFD and PAS - all patient lookups should use MBI
2. **Eight distinct EOB claim types** must be mapped to PAS Claim.type categories
3. **FISS/MCS split** in PAC aligns with institutional/professional distinction in PAS
4. **SAMHSA filtering** must be applied when exposing PA data through Patient Access API
5. **mTLS authentication** requires SSL configuration in the BFD client service
6. **No real-time adjudication** - BFD provides historical claims data, not live adjudication
7. **Coverage data** provides insurance information needed for PAS Claim.insurance
8. **Diagnosis and procedure codes** from EOBs can inform PA rules engine decisions
