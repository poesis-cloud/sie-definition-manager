---
parent_feature: F-01
parent_story: S-004b
adrs: [ADR-001, ADR-002]
---

# Telemetry Coverage Map — Span Axis (Definition Manager)

**Status:** Active
**Story:** [S-004b](../../../products/sie-definition/sprint-2/stories/S-004b.md)
**Feature:** [F-01](../../../products/sie-definition/features/F-01-otel-observability-baseline.md)
**ADRs:** [ADR-001](../../../products/sie-definition/architecture/adr-001-otel-collector-and-conventions.md), [ADR-002](../../../products/sie-definition/architecture/adr-002-aop-broad-instrumentation-policy.md)

---

## Structural invariant (ADR-002 D-9)

**100% by construction** of stereotype-bean methods in `cloud.poesis.sie.defman.**`.

Per ADR-002 D-8, a single Spring AOP aspect wraps every method of every bean annotated (directly or meta-annotated) with any Spring stereotype — `@Component`, `@Service`, `@Repository`, `@Controller`, `@RestController`, `@Configuration` — residing in `cloud.poesis.sie.defman.**`. There is no opt-out annotation and no exclusion list.

Each intercepted invocation emits **one INTERNAL span** with attributes:

- `code.namespace` (FQ class), `code.function` (method name)
- `sie.aop.args.summary` (types and sizes only — never values; respects ADR-001 D-7 cap)
- `sie.aop.duration_ms`
- `exception.type` / `exception.message` / `exception.stacktrace` on throw (OTel-native)
- `gsm.tenant.id`, `sie.component` from MDC (per ADR-001 D-3)

This invariant is **enforced by `BroadInstrumentationAspectIT`** (added in Unit 5 of S-004b). The test:

1. Enumerates every stereotype bean in `cloud.poesis.sie.defman.**`.
2. Asserts no bean declares a `final` method (Spring AOP proxy constraint — see ADR-002 D-8 note).
3. Asserts that invoking any public method on each bean produces one AOP INTERNAL span with the required attributes.

F-AC-8 (≥95% telemetry coverage) is satisfied on the span axis when this structural test is green at 100% (per ADR-002 D-9).

---

## Non-AOP span axis items

The following span-axis coverage units are **not** produced by the broad AOP aspect and remain individually enumerated. The OTel Java agent owns HTTP and JDBC spans; the AOP aspect's INTERNAL spans become children (for HTTP) and parents (for JDBC) of those agent spans.

| Flow                                           | Layer       | Owner (class.method)                                       | Trigger                  | Unit Type                                  | Coverage Status                      | Expected Telemetry                                                                                                       | Required Attributes                                                                                  | Expected Test Evidence                                            | Implementing Story |
| ---------------------------------------------- | ----------- | ---------------------------------------------------------- | ------------------------ | ------------------------------------------ | ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------- | ------------------ |
| **HTTP Entry**                                 | Controller  | `*Controller.*`                                            | Inbound HTTP request     | SERVER span enrichment                     | `agent-only` + `required` enrichment | OTel agent SERVER span + Defman-added `gsm.tenant.id`, `sie.component`                                                   | `http.method`, `http.route`, `gsm.tenant.id`, `sie.component=definition-manager`                     | MockMvc test asserting span attributes                            | S-005              |
| **Repository/JDBC Calls**                      | Repository  | Spring Data JPA repositories                               | Any persistence call     | CLIENT span (DB)                           | `agent-only`                         | OTel agent DB CLIENT span with `db.system=postgresql`, `db.operation`, `db.statement`                                    | `db.system`, `db.operation`, `db.statement` (agent-provided)                                         | Integration test asserting SERVER → INTERNAL (AOP) → CLIENT tree  | S-010              |
| **Ascription Lifecycle Transition (Accepted)** | Service     | `AscriptionStatusTransitionService.transition`             | Valid state transition   | AOP INTERNAL span + enrichment             | `structural` (AOP) + enrichment      | AOP span with lifecycle-specific attributes added                                                                        | `gsm.ascription.id`, `gsm.ascription.state.from`, `gsm.ascription.state.to`                          | Integration test for DRAFT → ACTIVE transition                    | S-008              |
| **Lifecycle Cascade Transition**               | Service     | `AscriptionStatusTransitionService.transition` (recursive) | Cascade trigger          | AOP INTERNAL span (child of parent)        | `structural` (AOP)                   | Child transition span nested under initiating transition (natural from AOP per-call instrumentation)                     | Parent-child span relationship via AOP                                                               | Test cascade flow, assert nested span tree                        | S-008              |
| **Definition Create**                          | Service     | `AscriptionService.create` (definitionId == null)          | POST create request      | AOP INTERNAL span + enrichment             | `structural` (AOP) + enrichment      | AOP span with definition-create attributes added                                                                         | `gsm.definition.operation=create`, `gsm.definition.id`, `gsm.definition.kind`                        | Integration test asserting span + attributes                      | S-009              |
| **Definition Transform**                       | Service     | `AscriptionService.create` (definitionId != null)          | POST transform request   | AOP INTERNAL span + enrichment             | `structural` (AOP) + enrichment      | AOP span with definition-transform attributes added                                                                      | `gsm.definition.operation=transform`, `gsm.definition.id`, `gsm.definition.kind`                    | Integration test asserting span + attributes                      | S-009              |
| **Definition Read**                            | Service     | `DefinitionService.findById` (or similar)                  | GET read request         | AOP INTERNAL span + enrichment             | `structural` (AOP) + enrichment      | AOP span with definition-read attributes added                                                                           | `gsm.definition.operation=read`, `gsm.definition.id`                                                | Integration test asserting span + attributes                      | S-010              |
| **Bootstrap Seed Startup**                     | Bootstrap   | `ArchetypeSeedRunner.run`                                  | Application startup      | AOP INTERNAL span (stereotype bean)        | `structural` (AOP)                   | AOP span at startup for the runner's `run()` method                                                                      | `sie.component=definition-manager`, span kind INTERNAL                                              | Startup integration test asserting span exists                    | S-013              |
| **Startup Span Marker Coherence**              | Bootstrap   | `StartupSpanEmitter` + `ArchetypeSeedRunner`               | Application startup      | No false nesting                           | `required`                           | Seed span NOT nested under startup marker event that starts after runner                                                | Span tree inspection                                                                                | Startup test asserting correct span relationships                 | S-013              |
| **Entity Method Calls**                        | Entity      | All entity classes                                         | Entity business logic    | None                                       | `no-instrumentation`                 | No instrumentation; entities are data holders (not Spring stereotype beans → outside AOP pointcut)                       | N/A                                                                                                  | Documented rule; no test needed                                   | S-010              |
| **Resource Attributes (service.name)**         | Config      | OpenTelemetry SDK config                                   | Application startup      | Resource attribute                         | `required`                           | `service.name=sie-definition-manager`                                                                                    | `service.name` present in OTLP export                                                                | Helm test + integration test asserting resource attr              | S-014              |
| **Resource Attributes (service.version)**      | Config      | OpenTelemetry SDK config                                   | Application startup      | Resource attribute                         | `required`                           | `service.version=<pom.version>`                                                                                          | `service.version` present in OTLP export                                                             | Helm test + integration test asserting resource attr              | S-014              |
| **Resource Attributes (service.namespace)**    | Config      | OpenTelemetry SDK config                                   | Application startup      | Resource attribute                         | `required`                           | `service.namespace=sie` (or final decided value)                                                                         | `service.namespace` present in OTLP export                                                           | Helm test + integration test asserting resource attr              | S-014              |
| **Sampling Configuration**                     | Config      | OTel SDK sampler                                           | Application startup      | Sampler setup                              | `required`                           | `observability.sampling.headRatio` applied; default=1.0 (keep-all)                                                       | Sampler config matches Helm value                                                                    | Helm test + config test                                           | S-014              |
| **Collector Outage Resilience**                | Runtime     | OTLP exporter                                              | Collector unavailable    | Non-fatal export failure                   | `required`                           | Startup/request handling succeed; export failures logged but not fatal; recovery on endpoint return                      | App starts and serves requests with collector down; resumes export when collector returns           | Integration test with collector outage simulation                 | S-014              |

---

## Coverage Status Values

| Status                      | Meaning                                                                                                                |
| --------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `structural` (AOP)          | Produced by construction by `BroadInstrumentationAspect`; verified by `BroadInstrumentationAspectIT` (Unit 5 / S-004b) |
| `agent-only`                | OTel Java agent owns instrumentation; Defman documents but does not implement custom code                              |
| `required`                  | Must have test evidence; counted in non-AOP coverage denominator                                                       |
| `no-instrumentation`        | Intentionally not instrumented (e.g., entities — not Spring stereotype beans)                                          |

---

## Coverage Target (PRD-001 §4, F-AC-2, F-AC-8)

**Quantitative criterion (PRD-001 §4 / F-AC-8):** ≥95% of `required` units (denominator = non-AOP rows above) must have passing evidence. The AOP-covered span set is 100% structurally and is not part of the percentage denominator (the structural test green-light *is* the evidence).

**Qualitative criterion (F-AC-2):** No critical state-changing flow (Definition create/transform, Ascription lifecycle transitions, bootstrap seeding) may be uncovered, regardless of aggregate percentage. The AOP aspect guarantees this for stereotype-bean code paths; non-AOP rows above carry the remainder.

---

## Routing Table (S-004b)

- **S-004b:** `BroadInstrumentationAspect` + `BroadInstrumentationAspectIT` (structural invariant)
- **S-005:** HTTP entry enrichment
- **S-008:** Ascription lifecycle enrichment
- **S-009:** Definition create/transform enrichment
- **S-010:** Definition read enrichment, JDBC tree shape, entity no-instrumentation rule
- **S-013:** Bootstrap startup span, marker coherence
- **S-014:** Resource attributes, sampling, collector outage
- **S-015:** End-to-end trace shape, perf baseline

---

## Revision History

| Date       | Story  | Change                                                                |
| ---------- | ------ | --------------------------------------------------------------------- |
| 2026-05-26 | S-004  | Initial map created per approved HUDDLE outline                       |
| 2026-05-26 | S-004b | Split per ADR-002 D-9; AOP rows collapsed under structural invariant  |
