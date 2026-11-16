# Security model — Da Vinci Prior Authorization Support RI

The threat model this service is assessed against: who can reach it, what it protects, and what is accepted risk. Security scans start from this file; keep it current when the deployment or trust boundaries change.

## System overview
Spring Boot service (`App.java`) implementing the HL7 Da Vinci Prior Authorization Support IG. Listens on port 9015 under `/fhir` (`src/main/resources/application.properties`). Stores FHIR resources (Bundle, Claim, ClaimResponse, ClaimItem, Subscription, Rules, Audit, Client) in an embedded H2 database (`Database.java`, schema `CreateDatabase.sql`). Claims are adjudicated by CQL rules from the embedded CDS library (`rules/PriorAuthRule.java`, `ClaimResponseFactory.java`); pended claims are updated asynchronously (`UpdateClaimTask.java`, `ProcessClaimItemTask.java`) and subscribers notified by rest-hook or websocket.

**Deployment:** internal / partner-facing only — not exposed to the open internet — and it holds **real patient PHI**. The in-repo Docker config (`Dockerfile`, `docker-compose*.yml`, `porter.yaml`) is the deployment; there is no separate infrastructure repository.

## Components
| Name | Code |
|---|---|
| **Auth** | `priorauth/authorization/` (`AuthEndpoint`, `AuthUtils`, `WellKnownEndpoint`) — SMART backend-services client registration, JWT client assertion, bearer tokens |
| **Claims API** | `priorauth/endpoint/ClaimEndpoint`, `ClaimInquiryEndpoint`, `ClaimResponseEndpoint`, `BundleEndpoint`, `Endpoint`, `Metadata`, `ExpungeOperation`, `FhirUtils`, `ClaimResponseFactory` |
| **Subscriptions & Notifications** | `endpoint/SubscriptionEndpoint`, `SubscribeController`, `WebSocketConfig`, `AssignPrincipalHandshakeHandler`, `UpdateClaimTask`, `ProcessClaimItemTask` |
| **Rules Engine** | `org/hl7/davinci/rules/`, `lib/RuleUtils.jar`, CDS library |
| **Data Store** | `Database.java`, `CreateDatabase.sql`, `Audit.java`, `PALogger.java` |
| **Debug & Ops** | `endpoint/DebugEndpoint`, `endpoint/LogEndpoint` |
| **Deployment** | `Dockerfile*`, `docker-compose*.yml`, `porter.yaml`, `.github/workflows/`, `.drone.yml`, `build.gradle` |

## Trust boundaries and entry points
- **Partner → Claims API**: `POST /Claim/$submit`, `POST /Claim/$inquire` require a bearer token (`AuthUtils.validateAccessToken`). `GET/DELETE /Bundle`, `/Claim`, `/ClaimResponse` require only `patient.identifier` (+ optional id) — no token check observed in code.
- **Partner → Auth**: `POST /auth/register` is open (any caller who reaches the service can register a client with `jwks` or `jwks_url`); `POST /auth/token` verifies an RS384 client assertion; `GET /.well-known/smart-configuration`.
- **Partner → Subscriptions**: `GET/POST/DELETE /Subscription`, websocket endpoint restricted to same-origin plus `priorauth.websocket.allowed-origins` patterns.
- **Service → outbound**: fetches registered `jwks_url` (`AuthEndpoint.getJwks`), POSTs to subscriber rest-hook endpoints (`UpdateClaimTask`, `ProcessClaimItemTask`) — SSRF surface into the internal network.
- **Debug & Ops**: `/debug/*` table dumps and test-data loaders gated by `App.isDebugModeEnabled()`; `/Log` returns the log file. Docker images run `bootRun -Pdebug`, but debug mode is never enabled in deployment.
- Base URL derived from forwarded host headers (`Endpoint.getServiceBaseUrl`) and used as JWT audience and in `fullUrl`.

## Assets
- **Critical:** patient PHI in Claims/Bundles/ClaimResponses (demographics, diagnoses, procedures).
- Prior-auth decisions (integrity: a forged/altered ClaimResponse approves care).
- Client registry and bearer tokens (`Client` table).
- Audit trail (`Audit` table) and logs (Authorization headers logged at FINE).

## Attacker profiles (prioritized)
1. **Registered provider/EHR client** reaching other patients' data or other clients' claims (cross-patient / cross-tenant access, IDOR).
2. **Attacker-controlled subscription rest-hook or JWKS endpoint** — SSRF via callbacks and JWKS fetches, response injection.

Lower priority: unauthenticated open-internet callers (the service is not internet-facing) and insiders with host-network access.

## Existing controls
- JWT client assertion verified with RS384 against registered keys; 5-minute bearer token lifetime.
- Debug endpoints gated by debug mode.
- Prepared statements in `Database`, though clauses are assembled by `generateClause`.
- Audit events for most REST actions.

## Out of scope / accepted risk
- **Debug mode** (`/debug/*` endpoints, `App.isDebugModeEnabled()`, `BYPASS_AUTH`) is never enabled in deployment — findings that only apply with debug mode or `BYPASS_AUTH` on are accepted risk.
- **Hardcoded demo credentials** (the admin token in `AuthUtils`, default clients seeded by `populateClientTable`, Keycloak admin/admin in `docker-compose.yml`) are accepted risk.

## Infrastructure
No separate infrastructure repository; the in-repo Docker/compose/porter files are the deployment.

## Open questions
- `/Log` (`LogEndpoint`) has no auth or debug gate; should it be removed or gated in deployment?
- Does a network control (proxy/egress filter) limit outbound callbacks?
