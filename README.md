# SIE Definition Manager

SIE (Systemic Intelligence Engine) is an **AI Context Platform**: it turns "context" into a governed, typed, provenance-backed asset (observations + definitions + evaluations) that can be assembled for AI reasoning, deterministic checks, and remediation decisions.

This module hosts the **Definition Manager** service managing the core conceptual model, primarily the **GSM (Generative System Model)** — SIE's generative/definitional system model.

## MVP TODOs

- Logging
- Authn/authz & Authorship/Governance

## DM v1 service scaffold

This module now also includes a runnable **Definition Manager API scaffold**:

- `Java 21` + `Spring Boot` + `Maven`
- REST API with both `application/hal+json` and `application/json`
- Dynamic OpenAPI endpoint: `/api/v1/openapi`
- RFC 7807 error model (`application/problem+json`)
- PostgreSQL + Flyway integration
- Kafka + Schema Registry integration hooks
- OAuth2/OIDC baseline (resource server + social client registrations)
- OTel baseline (`OTLP` endpoint wiring)
- GitHub Actions CI/CD baseline
- Helm chart for AKS and on-prem deployment profiles (`ops/helm`)

### Dev development

Use the simple dev flow:

- Validate local tooling and cluster connectivity:
  - `cd sie/sie-definition-manager && make dev-check`
- Deploy dependencies and expose them on localhost:
  - `cd sie/sie-definition-manager && make dev-up`
- Run DM locally:
  - `cd sie/sie-definition-manager && make run-api`
- Teardown dependencies and port-forwards:
  - `cd sie/sie-definition-manager && make dev-down`

`make dev-up` deploys dependencies using each dependency repo's own Helm
chart:

- Event bus: `sie/sie-event-bus/ops/helm`
- Schema Registry: `sie/sie-schema-registry/ops/helm`
- Definition DB: `sie/sie-definition-database`

Environment files:

- Use `.env` for checked-in non-secret defaults.
- Use `.env.dev` for machine-local dev secrets and overrides.
- To enable social OAuth providers, set `DM_OAUTH2_LOGIN_ENABLED=true` in `.env.dev`
  and supply the registration env vars (Spring Boot relaxed binding):

  ```env
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID=...
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_SECRET=...
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID=...
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET=...
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_MICROSOFT_CLIENT_ID=...
  SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_MICROSOFT_CLIENT_SECRET=...
  ```

Deployment values structure:

- Environment values:
  - `ops/helm/environments/dev/values.yaml`
  - `ops/helm/environments/preprod/values.yaml`
  - `ops/helm/environments/prod/values.yaml`

Each environment file is self-contained and carries the chart defaults for that
target environment.

Secrets are not stored in these files for real environments. Inject
`secrets.DB_PASSWORD` at deploy time via CI/CD secret store.

### Schema registration policy

DM is configured for an explicit **registration pipeline** (no auto-registration at runtime):

- `DM_SCHEMA_AUTO_REGISTER=false` (default)

Schemas should be registered by CI/CD or dedicated governance pipelines before DM runtime operations rely on them.

### Reliability profile

Default profile is:

- `DM_RELIABILITY_MODE=alo-idempotent`

This keeps v1 cost-effective while preserving a path to stricter producer transaction settings when needed.

### POST endpoint idempotency

Neither POST endpoint is idempotent — by design:

- **`POST /ascriptions`**: not idempotent. Submitting the same payload twice
  creates two distinct Ascription rows (each with its own UUIDv7 `id`). This
  is intentional: GSM's governance convergence pattern handles duplicates —
  approving one Ascription auto-terminates siblings (DRAFT → ABANDONED,
  PROPOSED → REJECTED), so duplicate drafts are harmless and self-cleaning.
- **`POST /ascriptions/{id}/transitions`**: not idempotent, but naturally
  guarded by the state machine. The first call succeeds; a duplicate call with
  the same `postStatus` fails because the Ascription's `status` has already
  advanced past the `preStatus` — the transition path is no longer valid
  (409 Conflict).

Clients should treat both endpoints as non-idempotent and avoid blind retries.
If at-least-once delivery introduces duplicate drafts, governance convergence
resolves them without manual intervention.

## Documentation

Conceptual and user-facing documentation lives on the docs site:

- **SIE Definition** — https://docs.poesis.cloud/sie-definition/
- **GSM, the open standard** — https://docs.poesis.cloud/gsm/

Design-time models in this repo:

- GSM model: `def/gsm.puml`
- Ascription lifecycle: `def/gsm-ascription-lifecycle.puml`
- SIE overview: `../README.md`

> The full pre-simplification README (GSM theory, the DM-as-compiler concept
> layers, the explicit-fetch design notes, the Directive → Norm governance
> examples, and ADR-001) is preserved at `def/archive/README.md`.
