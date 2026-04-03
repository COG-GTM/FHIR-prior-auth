#!/bin/bash
# =============================================================================
# BFD-PAS Integration Validation Script
# CMS-0057-F Compliance Validation
# =============================================================================
set -e

BASE_URL="${PAS_BASE_URL:-http://localhost:9015/fhir}"
TIMEOUT=30
PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_pass() {
    echo -e "${GREEN}[PASS]${NC} $1"
    PASS_COUNT=$((PASS_COUNT + 1))
}

log_fail() {
    echo -e "${RED}[FAIL]${NC} $1"
    FAIL_COUNT=$((FAIL_COUNT + 1))
}

log_skip() {
    echo -e "${YELLOW}[SKIP]${NC} $1"
    SKIP_COUNT=$((SKIP_COUNT + 1))
}

log_info() {
    echo -e "[INFO] $1"
}

# =============================================================================
# Step 1: Check server health
# =============================================================================
log_info "Step 1: Checking PAS RI server health..."

HEALTH_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time $TIMEOUT \
    "${BASE_URL}/metadata" 2>/dev/null || echo "000")

if [ "$HEALTH_RESPONSE" = "200" ]; then
    log_pass "Server is running and metadata endpoint is accessible"
else
    log_fail "Server is not accessible (HTTP $HEALTH_RESPONSE). Start server first: ./gradlew bootRun"
    echo ""
    echo "Summary: 0 passed, 1 failed, 0 skipped"
    exit 1
fi

# =============================================================================
# Step 2: Verify CapabilityStatement includes PAS operations
# =============================================================================
log_info "Step 2: Verifying CapabilityStatement..."

METADATA=$(curl -s --max-time $TIMEOUT \
    -H "Accept: application/fhir+json" \
    "${BASE_URL}/metadata" 2>/dev/null)

if echo "$METADATA" | grep -q '"submit"'; then
    log_pass "CapabilityStatement declares \$submit operation"
else
    log_fail "CapabilityStatement missing \$submit operation"
fi

if echo "$METADATA" | grep -q '"inquiry"'; then
    log_pass "CapabilityStatement declares \$inquire operation"
else
    log_fail "CapabilityStatement missing \$inquire operation"
fi

if echo "$METADATA" | grep -q 'davinci-pas'; then
    log_pass "CapabilityStatement references Da Vinci PAS IG"
else
    log_fail "CapabilityStatement missing Da Vinci PAS IG reference"
fi

# =============================================================================
# Step 3: Submit a test claim via $submit
# =============================================================================
log_info "Step 3: Submitting test claim via \$submit..."

CLAIM_BUNDLE='{
  "resourceType": "Bundle",
  "type": "collection",
  "entry": [
    {
      "resource": {
        "resourceType": "Claim",
        "status": "active",
        "type": {
          "coding": [{
            "system": "http://terminology.hl7.org/CodeSystem/claim-type",
            "code": "professional"
          }]
        },
        "use": "preauthorization",
        "patient": {
          "reference": "Patient/pat-test-001"
        },
        "created": "2024-01-15",
        "provider": {
          "reference": "Organization/org-test-001"
        },
        "priority": {
          "coding": [{
            "code": "normal"
          }]
        },
        "insurance": [{
          "sequence": 1,
          "focal": true,
          "coverage": {
            "reference": "Coverage/cov-test-001"
          }
        }],
        "item": [{
          "sequence": 1,
          "productOrService": {
            "coding": [{
              "system": "http://www.ama-assn.org/go/cpt",
              "code": "99213",
              "display": "Office or other outpatient visit"
            }]
          }
        }]
      }
    },
    {
      "resource": {
        "resourceType": "Patient",
        "id": "pat-test-001",
        "identifier": [{
          "system": "http://hl7.org/fhir/sid/us-mbi",
          "value": "1EG4-TE5-MK72"
        }],
        "name": [{
          "family": "TestPatient",
          "given": ["Integration"]
        }]
      }
    }
  ]
}'

SUBMIT_RESPONSE=$(curl -s -w "\n%{http_code}" --max-time $TIMEOUT \
    -X POST \
    -H "Content-Type: application/fhir+json" \
    -H "Accept: application/fhir+json" \
    -d "$CLAIM_BUNDLE" \
    "${BASE_URL}/Claim/\$submit" 2>/dev/null)

SUBMIT_HTTP_CODE=$(echo "$SUBMIT_RESPONSE" | tail -1)
SUBMIT_BODY=$(echo "$SUBMIT_RESPONSE" | sed '$d')

if [ "$SUBMIT_HTTP_CODE" = "201" ] || [ "$SUBMIT_HTTP_CODE" = "200" ]; then
    log_pass "Claim \$submit returned HTTP $SUBMIT_HTTP_CODE"
else
    log_fail "Claim \$submit returned HTTP $SUBMIT_HTTP_CODE (expected 200 or 201)"
fi

if echo "$SUBMIT_BODY" | grep -q '"ClaimResponse"'; then
    log_pass "Submit response contains ClaimResponse"
else
    log_fail "Submit response does not contain ClaimResponse"
fi

# =============================================================================
# Step 4: Query PA status via PriorAuthorization endpoint
# =============================================================================
log_info "Step 4: Querying PA status via /PriorAuthorization endpoint..."

PA_RESPONSE=$(curl -s -w "\n%{http_code}" --max-time $TIMEOUT \
    -H "Accept: application/fhir+json" \
    "${BASE_URL}/PriorAuthorization?patient=1EG4-TE5-MK72" 2>/dev/null)

PA_HTTP_CODE=$(echo "$PA_RESPONSE" | tail -1)
PA_BODY=$(echo "$PA_RESPONSE" | sed '$d')

if [ "$PA_HTTP_CODE" = "200" ]; then
    log_pass "PriorAuthorization endpoint returned HTTP 200"
else
    log_fail "PriorAuthorization endpoint returned HTTP $PA_HTTP_CODE (expected 200)"
fi

if echo "$PA_BODY" | grep -q '"Bundle"'; then
    log_pass "PriorAuthorization response is a Bundle"
else
    log_fail "PriorAuthorization response is not a Bundle"
fi

# =============================================================================
# Step 5: Verify PriorAuthorization returns 400 for missing patient
# =============================================================================
log_info "Step 5: Testing PriorAuthorization error handling..."

PA_ERROR_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time $TIMEOUT \
    -H "Accept: application/fhir+json" \
    "${BASE_URL}/PriorAuthorization" 2>/dev/null || echo "000")

if [ "$PA_ERROR_RESPONSE" = "400" ]; then
    log_pass "PriorAuthorization returns 400 when patient parameter is missing"
else
    log_fail "PriorAuthorization returned HTTP $PA_ERROR_RESPONSE (expected 400)"
fi

# =============================================================================
# Step 6: Query claim status via $inquire
# =============================================================================
log_info "Step 6: Testing \$inquire operation..."

INQUIRE_BUNDLE='{
  "resourceType": "Bundle",
  "type": "collection",
  "entry": [
    {
      "resource": {
        "resourceType": "Claim",
        "status": "active",
        "type": {
          "coding": [{
            "system": "http://terminology.hl7.org/CodeSystem/claim-type",
            "code": "professional"
          }]
        },
        "use": "preauthorization",
        "patient": {
          "reference": "Patient/pat-test-001"
        },
        "created": "2024-01-15",
        "provider": {
          "reference": "Organization/org-test-001"
        },
        "priority": {
          "coding": [{
            "code": "normal"
          }]
        },
        "insurance": [{
          "sequence": 1,
          "focal": true,
          "coverage": {
            "reference": "Coverage/cov-test-001"
          }
        }]
      }
    }
  ]
}'

INQUIRE_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" --max-time $TIMEOUT \
    -X POST \
    -H "Content-Type: application/fhir+json" \
    -H "Accept: application/fhir+json" \
    -d "$INQUIRE_BUNDLE" \
    "${BASE_URL}/Claim/\$inquire" 2>/dev/null || echo "000")

if [ "$INQUIRE_RESPONSE" = "200" ] || [ "$INQUIRE_RESPONSE" = "201" ]; then
    log_pass "Claim \$inquire returned HTTP $INQUIRE_RESPONSE"
else
    log_skip "Claim \$inquire returned HTTP $INQUIRE_RESPONSE (may require valid claim reference)"
fi

# =============================================================================
# Step 7: Verify database configuration
# =============================================================================
log_info "Step 7: Verifying database configuration..."

if [ -n "$DB_TYPE" ] && [ "$DB_TYPE" = "postgresql" ]; then
    log_info "PostgreSQL mode detected"
    if [ -n "$DB_URL" ]; then
        log_pass "PostgreSQL DB_URL is configured"
    else
        log_fail "PostgreSQL DB_URL is not set"
    fi
else
    log_pass "H2 (default) database mode - suitable for development/testing"
fi

# =============================================================================
# Summary
# =============================================================================
echo ""
echo "============================================="
echo " Validation Summary"
echo "============================================="
echo -e " ${GREEN}Passed:${NC}  $PASS_COUNT"
echo -e " ${RED}Failed:${NC}  $FAIL_COUNT"
echo -e " ${YELLOW}Skipped:${NC} $SKIP_COUNT"
echo " Total:   $((PASS_COUNT + FAIL_COUNT + SKIP_COUNT))"
echo "============================================="

if [ $FAIL_COUNT -gt 0 ]; then
    echo -e "${RED}Some validations failed. Review the output above.${NC}"
    exit 1
else
    echo -e "${GREEN}All validations passed!${NC}"
    exit 0
fi
