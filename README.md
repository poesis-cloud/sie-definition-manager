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

## GSM — the Generative System Model

GSM is a fundamental departure from classical systemic models such as Beer's Viable System Model (VSM) or von Bertalanffy's General System Theory (GST). Those models are _descriptive_: they provide analytical frameworks for observing and reasoning about systems that already exist. GSM, by contrast, is _generative_ (equivalently: _definitional_). Its primitives are not passive descriptions of an observed reality; they are **active definitions** from which system structure, behavior, governance, and viability are _derived and produced_.

**Structure** is the foundational aggregate — an intentional assembly of Elements (Mechanisms, Interactions) arranged to address a governed **purpose**. GSM does not model "System" as a primitive; whether something _is_ a system is derived at inference time from reflexivity and governance scope, not asserted at definition time. A Structure becomes a System when its Mechanisms become reflexive — when it operates itself.

A Structure is fundamentally **dynamic**: it has Mechanisms — causal behavioral units with rule logic. Only dynamism is causal. Static structure in GSM is expressed through **Archetypes** (type definitions); Archetypes have no Mechanisms and no causal behavior. Dynamic Structures produce static structures through their Mechanisms' Effectors. A codebase, for example, is an Archetype (static structure) produced by a dev team's Mechanisms; the software instance it defines is a Structure (dynamic).

**DNA** (Directives / Norms / Ascriptions) is the unified governance grammar, stratified by rate of change:

| Layer | Primitive      | Tempo                | What it governs                                                                                                                                                                                                       |
| ----- | -------------- | -------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **D** | **Directive**  | Slow (identity)      | What the system fundamentally is. Grammar: **Modal × Verb × Qualifier × Purpose** (e.g., _"order-processing MUST ENSURE SecurityProperties OF payment-service"_).                                                     |
| **N** | **Norm**       | Medium (constraints) | Measurable constraint predicates operationalizing Directives. **Guard** (applicability-guard CEL profile) + **Predicate** (property-assertion CEL profile) + tolerance mode (INSTANTANEOUS / AGGREGATED / SUSTAINED). |
| **A** | **Ascription** | Fast (definition)    | Concrete, version-specific definition of each Element, typed by an **Archetype** (JSON Schema). All GSM primitives — Structure, Mechanism, Directive, Norm, Archetype itself — extend Ascription.                     |

Each layer can evolve at its natural rate without destabilizing the others.

**Teleological decomposition** connects intent to implementation:

- **Purpose** (Structure) → _why_ the aggregate exists.
- **Function** (Mechanism) → _what capacity_ each causal unit contributes.
- **Rule** (Mechanism, Starlark) → _how_ that capacity is realized. The definition-manager auto-derives **Effectors** (output ports) and **Receptors** (input ports) from the rule's AST — closed-loop vs. open-loop pattern.

**Archetype** is the sole typed deliverable in the definition plane — the domain model / vocabulary and GSM's vehicle for static structure. Every Ascription references one Archetype; every Archetype references a JSON Schema (`schemaUri`) that validates its instances. The type system bootstraps from a single axiomatic seed (the Archetype that types itself).

**Governance chain example**: Product team (Structure) governs → Dev team (Structure) governs → Codebase (Archetype — evaluated deliverable) defines → Software instance (Structure). The codebase is a static structure (Archetype); the running software is a dynamic Structure with causal Mechanisms whose rules are sourced from the codebase.

**Systemic recursion** closes the loop: Mechanisms operate on Ascriptions; all definitions are Ascriptions; therefore Mechanisms can operate on Mechanisms — including producing governance DNA (Directives and Norms). The system defines itself through itself.

Classical models _describe_ systems; GSM _defines_ them. Description tells you what a system is; definition tells the system what it must become — and provides the governance machinery to get there.

## DM as compiler — concept layers

The Definition Manager is not a passive store — it is a **compiler**. The API request is the _authored input_; the Ascription's `compilation` payload is the _compiled output_. Archetype schemas define the **output contract** (what the compiled artifact looks like), not the input shape. FK columns are materialized indexes derived from the compilation, for DB-level referential integrity.

This compiler paradigm evolves through four concept layers:

### Layer 0 — Axiomatic Bootstrap (hardcoded) `[current]`

The seed layer. 9 GSM base Archetypes and their compilation logic are hardcoded in Java (`switch(type)` in `AscriptionService.buildEntity`). The DM knows how to compile each GSM primitive: resolving FK references from the request payload, validating against the Archetype schema, registering schemas, and producing the immutable `compilation` JSONB.

- **What exists**: base Archetype schemas (Structure, Mechanism, Effector, Receptor, Interaction, Interface, Directive, Norm, Archetype), hardcoded entity builders, schema validation.
- **Extension model**: none — tenants consume GSM primitives but cannot extend the type system.
- **Compiler surface**: Java code only. The Archetype schema validates the output; the DM code defines the input-to-output transformation.

### Layer 1 — Schema-driven Adaptation

Tenants create Archetypes extending GSM bases via top-level `$ref`. The DM adapts by reading the tenant Archetype's schema, auto-deriving input requirements from the schema diff (base portion compiled by Layer 0 logic, extension fields passed through).

- **What changes**: DM inspects `$ref` chains at authoring time. For any tenant Archetype that extends a GSM base, DM applies Layer 0 compilation for the base portion and passes through tenant-defined extension fields.
- **Extension model**: schema-only. Tenants define _what_ properties exist; the DM controls _how_ they are compiled.
- **Compiler surface**: Java (Layer 0 core) + schema introspection (auto-adaptation).

### Layer 2 — Mechanism-driven Compilation

Tenants author Mechanisms (Starlark rules) for custom domain-specific compilation. The DM orchestrates Layer 0 + tenant Mechanisms.

- **What changes**: tenant Mechanisms can intercept, transform, or enrich the compilation pipeline. A Mechanism's Starlark rule receives the authored input, applies domain logic, and produces the compilation output conforming to its Archetype schema.
- **Extension model**: behavioral. Tenants define _what_ properties exist (Archetype schema) **and** _how_ they are compiled (Mechanism rules). This is SIE operating itself through itself.
- **Compiler surface**: Java (Layer 0 core) + Starlark (tenant Mechanisms).

### Layer 3 — Full Reflexivity (autopoietic end-state)

The DM's own Layer 0 compilation logic is modeled as governable Mechanisms. The governance loop governs the compiler itself.

- **What changes**: Layer 0 Java logic is replaced by (or wrapped in) GSM Mechanisms whose rules the governance loop can inspect, evaluate, and evolve. The DM becomes a System in the GSM sense — reflexive Mechanisms defining and operating the compilation pipeline.
- **Extension model**: self-governing. The compiler is part of the governed system.
- **Compiler surface**: GSM Mechanisms (Starlark) all the way down.

## Explicit-fetch design for lazy associations

Controllers follow an **explicit-fetch** pattern for loading JPA associations (`definition`, `archetype`) that are mapped as `@ManyToOne(fetch = FetchType.LAZY)` on `AscriptionEntity`.

Instead of relying on `@EntityGraph`, JPQL JOIN FETCH, or Open Session In View (OSIV) to transparently resolve lazy proxies, each controller endpoint **explicitly** fetches the required associations via dedicated service calls and passes them as method arguments to DTO mapping and HATEOAS helpers.

### Constraints driving this design

- **OSIV is disabled** (`spring.jpa.open-in-view=false`). Accessing a lazy proxy outside the originating transaction throws `LazyInitializationException`. The controller layer is outside the service transaction boundary, so lazy associations are unresolvable by default.
- **Hibernate 6 proxy FK extraction**: calling `proxy.getId()` on a `@ManyToOne` lazy proxy returns the FK value _without_ triggering proxy initialization. This makes extracting IDs for explicit fetch free (no DB hit).
- **TABLE_PER_CLASS inheritance**: JPA `@EntityGraph` and JPQL JOIN FETCH on the abstract `AscriptionEntity` base require UNION ALL across 9 subtype tables. Adding a JOIN FETCH on top of that UNION is poorly optimized by most JPA providers and leads to complex, hard-to-tune queries.

### Single-item fetch pattern

For endpoints returning one ascription (`create`, `getById`):

1. Fetch the ascription entity via the subtype service.
2. Extract FKs: `entity.getDefinition().getId()` and `entity.getArchetype().getId()` (no proxy init).
3. Fetch the full Definition and Archetype entities via `DefinitionService.getById()` and `ArchetypeService.findEntityById()` — two simple PK lookups.
4. Pass all three entities to the HATEOAS builder.

Cost: **3 queries** (entity + definition PK + archetype PK). All are indexed PK lookups — negligible overhead, fully predictable.

### Batch fetch pattern (lists and history)

For endpoints returning multiple ascriptions (`list`, `getAscriptionHistory`):

1. Fetch the page/list of ascription entities.
2. If the result is empty, return immediately (no batch query).
3. Collect distinct archetype IDs from the result set (FK extraction, no proxy init).
4. Batch-fetch all required Archetypes in one `IN (...)` query via `ArchetypeService.getByIds()`.
5. Map each entity to its DTO using the pre-fetched map.

Cost: **2 queries** (page + batch archetype IN). The batch fetch eliminates the N+1 problem.

### Rationale and trade-offs

| Pros                                                                                                                                                                                                                                                                                                                                                                             | Cons                                                                                                                                                                          |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Architectural simplicity**: no `@EntityGraph` annotations, no JPQL JOIN FETCH on TABLE_PER_CLASS unions, no OSIV. The persistence layer stays vanilla Spring Data derived queries — easy to understand, test, and maintain.                                                                                                                                                    | **Extra queries on single-item reads**: 3 queries instead of 1 JOIN. In practice, all are PK lookups served from the buffer pool — negligible for web latency.                |
| **Predictable query count**: every endpoint has an explicit, audit-visible query profile (2 or 3 queries, all PK or IN-list indexed).                                                                                                                                                                                                                                            | **Controller verbosity**: explicit fetch calls add a few lines per endpoint. This is deliberate — visibility over magic.                                                      |
| **Enables embed opt-in**: associations are fetched as first-class entities in the controller, readily available for embedding in HAL `_embedded` projections. Adding or removing embedded projections is a controller-only concern — no service/repository signature change required. A future `?embed=definition,archetype` query parameter can trivially gate the fetch calls. | **Batch callers must guard empty collections**: `getByIds()` should not be called with an empty collection (wasted round-trip). Callers must check emptiness before batching. |
| **Service layer stays clean**: services return domain entities without DTO/presentation concerns. The controller owns the decision of which associations to load and how to present them.                                                                                                                                                                                        |                                                                                                                                                                               |
| **No proxy surprises**: lazy proxies are never dereferenced outside a transaction boundary — the pattern is immune to `LazyInitializationException` regardless of OSIV configuration.                                                                                                                                                                                            |                                                                                                                                                                               |

## Where to start

- GSM model: `def/gsm.puml`
- DSM model (companion): `../sie-description-manager/def/dsm.puml`
- Supervision information model: `../sie-supervision/def/sie-supervision-information-model.puml`
- SIE overview: `../README.md`

## Governance examples — Directive → Norm by element type

> **Terminology note.** These examples use the current GSM primitives
> (Directive, Norm, Archetype, Structure). Some sections above still use
> earlier naming (Policy, Rule, Qualifier / DPQR) and will be aligned in a
> subsequent pass.

### The qualifierArchetypeId governance chain

A Directive's `qualifierArchetypeId` references the **Archetype** whose JSON
Schema defines the measurable properties being governed. Every Norm
operationalizing that Directive references the **same Archetype** as root
identifier in its CEL `assertion`. This closes a machine-verifiable governance
chain:

```text
Directive (qualifierArchetypeId → Archetype A)
    └── Norm (assertion root identifier → same Archetype A)
```

The definition-manager validates this link: a Norm whose assertion references
an Archetype that differs from its Directive's `qualifierArchetypeId` is
rejected.

### Two governance patterns

The same Directive → Norm machinery supports two distinct evaluation modes,
depending on what the qualifier Archetype types:

| Pattern                 | qualifierArchetypeId types…                                | Norm evaluates…                                                 | Trigger                 |
| ----------------------- | ---------------------------------------------------------- | --------------------------------------------------------------- | ----------------------- |
| **Definition-time**     | Ascriptions (Structure, Mechanism, Interface, Interaction) | Ascription properties when a definition is authored or modified | Definition change event |
| **Runtime observation** | Artifact instances from the Description plane              | Observed metrics / measurements at runtime                      | Observation event       |

### Example 1 — Structure governance: SecurityProperties

**Archetype**: `SecurityProperties` (extends StructureArchetype).
Schema defines: `encryptionLevel`, `authenticationProtocol`, `dataClassification`, …

**Directive** (Ascription payload):

```json
{
  "structureId": "platform-governance",
  "modal": "MUST",
  "verb": "ENSURE",
  "qualifierArchetypeId": "SecurityProperties",
  "purposeStructureId": "order-processing"
}
```

> Reads: "platform-governance MUST ENSURE SecurityProperties OF order-processing."

**Norms** operationalizing this Directive:

| applicability | assertion                                             | toleranceMode | meaning                                                      |
| ------------- | ----------------------------------------------------- | ------------- | ------------------------------------------------------------ |
| `true`        | `SecurityProperties.encryptionLevel >= "AES-256"`     | INSTANTANEOUS | All order-processing Structures must use AES-256+ encryption |
| `true`        | `SecurityProperties.authenticationProtocol == "mTLS"` | INSTANTANEOUS | Must use mTLS between services                               |

> _Pattern: **definition-time**. Norms evaluate Structure Ascription properties when the definition is authored._

### Example 2 — Mechanism governance: ComplianceProperties

**Archetype**: `ComplianceProperties` (extends MechanismArchetype).
Schema defines: `framework`, `validationCoverage`, `lastAuditDate`, …

**Directive**:

```json
{
  "structureId": "compliance-board",
  "modal": "MUST",
  "verb": "ENSURE",
  "qualifierArchetypeId": "ComplianceProperties",
  "purposeStructureId": "payment-validation"
}
```

> Reads: "compliance-board MUST ENSURE ComplianceProperties OF payment-validation."

**Norms**:

| applicability                                 | assertion                                         | toleranceMode | meaning                                                 |
| --------------------------------------------- | ------------------------------------------------- | ------------- | ------------------------------------------------------- |
| `ComplianceProperties.framework == "PCI-DSS"` | `ComplianceProperties.validationCoverage >= 0.95` | INSTANTANEOUS | PCI-DSS mechanisms must have ≥ 95 % validation coverage |
| `ComplianceProperties.framework == "SOC2"`    | `ComplianceProperties.validationCoverage >= 0.80` | INSTANTANEOUS | SOC2 mechanisms need ≥ 80 % coverage                    |

> _Pattern: **definition-time**. The applicability filters which Mechanisms the Norm applies to (only those in the matching compliance framework). Applicability is how Norms achieve conditional governance without branching logic._

### Example 3 — Interface governance: APISecurityProperties

**Archetype**: `APISecurityProperties` (extends InterfaceArchetype).
Schema defines: `exposure`, `tlsVersion`, `rateLimitRps`, `corsPolicy`, …

**Directive**:

```json
{
  "structureId": "security-team",
  "modal": "MUST",
  "verb": "ENSURE",
  "qualifierArchetypeId": "APISecurityProperties",
  "purposeStructureId": "customer-portal"
}
```

> Reads: "security-team MUST ENSURE APISecurityProperties OF customer-portal."

**Norms**:

| applicability                                | assertion                                   | toleranceMode | meaning                                   |
| -------------------------------------------- | ------------------------------------------- | ------------- | ----------------------------------------- |
| `APISecurityProperties.exposure == "public"` | `APISecurityProperties.tlsVersion >= "1.3"` | INSTANTANEOUS | Public interfaces must use TLS 1.3+       |
| `APISecurityProperties.exposure == "public"` | `APISecurityProperties.rateLimitRps > 0`    | INSTANTANEOUS | Public interfaces must have rate limiting |
| `true`                                       | `APISecurityProperties.corsPolicy != ""`    | INSTANTANEOUS | All interfaces must declare a CORS policy |

> _Pattern: **definition-time**. Guards filter by exposure level — some Norms apply only to public-facing Interfaces, others apply universally._

### Example 4 — Interaction governance: InteractionReliabilityProperties

**Archetype**: `InteractionReliabilityProperties` (extends InteractionArchetype).
Schema defines: `encryptionInTransit`, `maxPayloadBytes`, `retryPolicy`, …

**Directive**:

```json
{
  "structureId": "infrastructure-team",
  "modal": "MUST",
  "verb": "ENSURE",
  "qualifierArchetypeId": "InteractionReliabilityProperties",
  "purposeStructureId": "payment-notification-flow"
}
```

> Reads: "infrastructure-team MUST ENSURE InteractionReliabilityProperties OF payment-notification-flow."

**Norms**:

| applicability | assertion                                                      | toleranceMode | meaning                                       |
| ------------- | -------------------------------------------------------------- | ------------- | --------------------------------------------- |
| `true`        | `InteractionReliabilityProperties.encryptionInTransit == true` | INSTANTANEOUS | All interactions must be encrypted in transit |
| `true`        | `InteractionReliabilityProperties.maxPayloadBytes <= 1048576`  | INSTANTANEOUS | Payload must not exceed 1 MB                  |
| `true`        | `InteractionReliabilityProperties.retryPolicy != "none"`       | INSTANTANEOUS | A retry policy must be configured             |

> _Pattern: **definition-time**. Norms constrain Interaction Ascription properties that govern how causal couplings between Mechanisms behave._

### Example 5 — Artifact governance (runtime observation): LatencyMetrics

**Archetype**: `LatencyMetrics` (extends ArtifactArchetype).
Schema defines: `environment`, `endpoint`, `p95ResponseMs`, `p99ResponseMs`, `errorRate`, …

**Directive**:

```json
{
  "structureId": "platform-governance",
  "modal": "SHOULD",
  "verb": "OPTIMIZE",
  "qualifierArchetypeId": "LatencyMetrics",
  "purposeStructureId": "checkout-service"
}
```

> Reads: "platform-governance SHOULD OPTIMIZE LatencyMetrics OF checkout-service."

**Norms**:

| applicability                                | assertion                              | toleranceMode | temporalWindow | temporalAggregation | sustainedThreshold | meaning                                                          |
| -------------------------------------------- | -------------------------------------- | ------------- | -------------- | ------------------- | ------------------ | ---------------------------------------------------------------- |
| `LatencyMetrics.environment == "production"` | `LatencyMetrics.p95ResponseMs <= 500`  | AGGREGATED    | PT5M           | P95                 | —                  | P95 latency ≤ 500 ms over 5-minute windows (production only)     |
| `LatencyMetrics.environment == "production"` | `LatencyMetrics.p99ResponseMs <= 2000` | SUSTAINED     | PT15M          | P99                 | 0.95               | P99 latency ≤ 2 s must hold for 95 % of each 15-minute window    |
| `LatencyMetrics.environment == "production"` | `LatencyMetrics.errorRate < 0.01`      | SUSTAINED     | PT10M          | AVG                 | 0.99               | Average error rate < 1 % must hold 99 % of each 10-minute window |

> _Pattern: **runtime observation**. Unlike examples 1–4, `LatencyMetrics` is an Artifact type (not a structural type). Instances are produced by the Description plane as runtime observations. Same Directive → Norm machinery, different temporal semantics: observation Norms typically use AGGREGATED or SUSTAINED tolerance modes with temporal windows._

## Definition-time appraisal (plane integration)

The Definition Manager is not a passive store. When definitions are created, modified, or deleted, the Definition Manager triggers **Norm Appraisal and Form Appraisal by the relevant plane components**:

| Definition change                                      | Appraising plane                          | What is checked                                                                                                         |
| ------------------------------------------------------ | ----------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| Directive created/modified                             | **Governance** (`../sie-governance/`)     | Constitutive Norm Appraisal: Directive ↔ Quality, scope validity, verb conflicts, governance plane coverage            |
| Norm created/modified                                  | **Regulation** (`../sie-regulation/`)     | Constraint Norm Appraisal: direction vs. Directive verb, metric→qualifier path, scope containment, Norm↔Norm conflicts |
| Mechanism rule created/modified                        | **Regulation** (`../sie-regulation/`)     | rule→Norm coverage, CEL/Starlark validity                                                                               |
| Structural changes (Structure, Interface, Interaction) | **Regulation** (`../sie-regulation/`)     | Structural Form Appraisal (cardinality and dependency Norm predicates)                                                  |
| Any definition activated                               | **Supervision** (`../sie-supervision/`)   | Re-appraise (Form Appraisal) existing observations against the new/updated definition                                   |
| Cross-system definition changes                        | **Coordination** (`../sie-coordination/`) | Inter-system coherence, collision detection, oscillation dampening (Stabilization)                                      |

This means each `PROPOSED → APPROVED → ACTIVE` transition in `AscriptionStatus` passes through the relevant plane's appraisal rules before advancing. The Definition Manager orchestrates these checks; the planes own the rules.

### Norm Appraisal at definition time

The Definition Manager is the primary trigger for **Norm Appraisal** — the appraisal of the form of norms (definition ↔ definition, intra-normative). Every definition change passes through Norm Appraisal before the definition can advance in its lifecycle. The three coherence dimensions checked are:

| Dimension        | What it checks                          | Definition Manager triggers                                                                                                                                                                                                                                                                                                                                                                                           |
| ---------------- | --------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Consistency**  | No contradictions between norms         | Directive verb conflict detection (NA-DIR-01); Norm↔Norm constraint conflicts (NA-POL-04)                                                                                                                                                                                                                                                                                                                            |
| **Completeness** | No gaps within declared normative scope | DNA chain traversability (NA-DPR-01); Norm operationalization coverage (NA-POL-08); rule→Norm minimum (NA-RULE-01)                                                                                                                                                                                                                                                                                                    |
| **Adequacy**     | Norms fit for declared purpose          | Norm→Directive direction coherence (NA-POL-01); metric→qualifier path tracing (NA-POL-02, SUSPENDED); scope containment (NA-POL-03); Directive applicability guard validation (NA-DIR-02); Norm→Purpose grounding (NA-POL-05); Norm→qualifier path tracing (NA-POL-06, SUSPENDED); Norm→Structure grounding (NA-POL-07); rule→Norm scope containment (NA-RULE-02); rule behavioral grounding (NA-RULE-04, NA-RULE-05) |

Norm Appraisal is executed by the **Governance** and **Regulation** planes. The Definition Manager sends definition-change events; those planes evaluate and return appraisal findings. Findings at `ERROR` severity block lifecycle transitions; `WARNING` severity allows advancement but flags the finding for review.

### Form Appraisal on definition activation

The Definition Manager triggers **Form Appraisal** — the appraisal of the form of reality (definition ↔ reality, trans-normative) — when a definition transitions to `ACTIVE`. At that point, existing state-plane observations (held by the Description Manager / DSM) must be re-evaluated against the new or updated definition. The three coherence dimensions checked are:

| Dimension       | What it checks                               | Definition Manager triggers                                                                                                                                       |
| --------------- | -------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Fidelity**    | Observed behavior matches definitions        | Mechanism definition ↔ observed behavior (FA-MECH-01); Norm constraints ↔ measured metrics (FA-POL-01); Closed-loop Mechanism feedback verification (FA-ACT-01) |
| **Coverage**    | Observed state has corresponding definitions | Archetype ↔ observed state (FA-STATE-01); detect state objects or observed mechanisms lacking GSM definitions (input to Emergence)                               |
| **Convergence** | Formation stabilizes toward the norm         | Normative envelope convergence tracking (FA-CONV-01); track whether repeated appraisal cycles show decreasing deviation between definitions and reality           |

Form Appraisal is executed by the **Supervision** plane. On definition activation, the Definition Manager publishes a `DefinitionActivatedEvent`; the Supervision plane re-appraises all relevant observations against the activated definition and produces Form Appraisal findings. Structural Form Appraisal (checking structural Norm constraints like cardinality and dependency) is also triggered by the Regulation plane when structural definitions change.

## Decision Records

### ADR-001: Appraisal as Meta-DNA (Option B — Decoupled Status + Genesis Seeds)

**Status**: Accepted (2026-02-16)

**Context**: SIE's Definition Manager governs lifecycle transitions of systemic primitives via `AscriptionStatus` (DRAFT → PROPOSED → APPROVED → ACTIVE → ...). The question is how norm appraisal checkpoints (NA-DIR-\*, NA-POL-\*, NA-RULE-\*, NA-DPR-\*) integrate with this lifecycle.

Three options were evaluated:

- **Option A (Integrate)**: Embed appraisal semantics directly into `AscriptionStatus`. Rejected — status instability with probabilistic checks; mixes lifecycle with evaluation.
- **Option B (Decouple)**: Model checkpoints as native DNA artifacts; status remains a pure lifecycle state machine. **Selected.**
- **Option C (Hybrid)**: Split status into lifecycle + appraisal sub-states. Rejected — accidental complexity, unclear ownership.

**Decision**: Option B — decouple status from appraisal. Model norm appraisal checkpoints as native DNA artifacts owned by the SIE genesis system.

#### Core constructs

| Construct                | GSM Primitive                      | Purpose                                                                                                                                                                                 |
| ------------------------ | ---------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Appraisal Finding        | `Archetype` (persisted, queryable) | Result of a checkpoint evaluation. Includes `severity`, `checkpointId`, `targetPrimitiveId`, `evaluationMode` (DETERMINISTIC / PROBABILISTIC / UNDECIDABLE), `confidence` \[0.0, 1.0\]. |
| Transition Applicability | `Norm`                             | Constrains lifecycle transitions using cardinality assertions over AppraisalFinding state objects. Scoped to the DefinitionManager component.                                           |
| Checkpoint               | `Mechanism rule`                   | Behavioral definition triggered by persisted-events. Each named checkpoint (NA-\*) is a Rule that evaluates coherence and produces findings.                                            |

#### Key design corrections

- **No `ContextVariableReferenceExpression` in Norms.** Norms are declarative state constraints with no receptor context. Per-instance scoping (matching findings to the primitive being transitioned) is handled by Mechanism rules, which have receptor context. The Norm states the normative goal; the Mechanism rule enforces it behaviorally.
- **Norm is used with structural constraint assertions.** Transition applicability expressions constrain _what definitions MUST BE_ (structural invariants of the DNA set). The constraint nature (structural) is derived from the assertion target (cardinality via `size()`, dependencies via structural primitives) and the Directive's qualifier, not from a Norm subtype. Applicability scoping: `scopedFunctions: [DefinitionLifecycleManagement]`.
- **NormAppraisal is a Purpose; each checkpoint is a Function.** The Purpose is _why_ (appraise normative coherence); each checkpoint Function is _what_ (the specific check activity). Functions serve the Purpose.
- **Findings are Archetype-typed** (persisted, queryable), not transient events. Mechanism rule execution produces AppraisalFinding instances.
- **Seed immutability via ownership.** Genesis seeds are owned by the SIE system. Tenants cannot modify primitives outside their governance authority. No explicit `mutable` flag needed — the existing ownership model handles it.
- **Non-determinism support.** Checks can be deterministic, probabilistic, or undecidable. Determinism lives at the applicability (Norm) level; checks (Mechanism rule) can have any evaluation mode. Applicability Norms define confidence thresholds for probabilistic check acceptance.

**DSL impact**: None. Existing Norm applicability/assertion CEL expressions and archetype-scoped selectors express all applicability constraints. No new types, functions, or syntax needed.

**Genesis seed**: `sie-genesis-seed.json` (adjacent to `gsm.puml`). Contains the full SIE system axiomatic definition including all norm appraisal checkpoints instantiated as DNA, plus supporting purposes, qualities, functions, mechanisms, and infrastructure.

#### Risks

| ID  | Risk                                                                       | Severity | Mitigation                                                                                        |
| --- | -------------------------------------------------------------------------- | -------- | ------------------------------------------------------------------------------------------------- |
| R1  | Bootstrap complexity — seed DNA validated outside the lifecycle it governs | Medium   | Design-time validation; SIE upgrades are the only path to modify seeds                            |
| R2  | Cognitive load — two DNA layers (meta + tenant)                            | Medium   | UI separation; `SIE_*` namespace prefix; platform vs. system governance sections                  |
| R3  | Performance — every lifecycle transition triggers checkpoint evaluation    | Low      | Batch evaluation; lazy evaluation on transition request; checkpoint parallelization               |
| R4  | Meta-governance escape hatch — seed checkpoint blocks legitimate work      | Low      | SUSPEND checkpoint Mechanism rule; admin force-transition with audit event; SIE platform override |
| R5  | Recursive governance depth — meta-DNA governs itself                       | Low      | Exactly 2 layers (meta + tenant); meta-layer seeds are axiomatic (bypass lifecycle)               |

#### Consequences

| Aspect          | Pro                                                                            | Con                                                                   |
| --------------- | ------------------------------------------------------------------------------ | --------------------------------------------------------------------- |
| Coherence       | SIE governs itself with its own DNA constructs (maximal alignment)             | Two DNA layers increase indirection                                   |
| Extensibility   | Tenants add custom checkpoint Mechanism rules without SIE code changes         | Tenant mistakes in custom Mechanism rules require escape hatches      |
| Auditability    | Full DNA chain for every governance decision (compliance-grade trail)          | —                                                                     |
| Future-proofing | LLM/probabilistic checks integrate cleanly via `evaluationMode` + `confidence` | Performance cost scales with checkpoint count                         |
| Non-determinism | Determinism at applicability level; checks can be probabilistic                | Need confidence thresholds for probabilistic applicability evaluation |
| Ownership model | Existing governance authority handles seed immutability                        | Tenants must understand they cannot modify `SIE_*` seeds              |

**Behavioral Executor (architectural note)**: SIE needs a generic Mechanism rule executor (Behavioral Executor) serving both Operators and Supervisors — the Operation plane's execution substrate. Technology analogs: Temporal.io (durable step-based workflows), Drools (rule engine), OPA/Rego (policy evaluation), Apache Flink (streaming CEP). Practical SIE stack: Temporal for rule sequencing + CEL for expression evaluation.
