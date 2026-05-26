---
parent_feature: F-01
parent_story: S-004b
adrs: [ADR-001, ADR-002]
---

# Telemetry Coverage Map — Log Axis (Definition Manager)

**Status:** Active
**Story:** [S-004b](../../../products/sie-definition/sprint-2/stories/S-004b.md)
**Feature:** [F-01](../../../products/sie-definition/features/F-01-otel-observability-baseline.md)
**ADRs:** [ADR-001](../../../products/sie-definition/architecture/adr-001-otel-collector-and-conventions.md), [ADR-002](../../../products/sie-definition/architecture/adr-002-aop-broad-instrumentation-policy.md)

---

## Structural invariant (ADR-002 D-9)

**100% by construction** of method-level entry/exit/exception logs, gated by `observability.aop.logLevel`.

Per ADR-002 D-8, the same broad AOP aspect that produces the structural span axis (see [telemetry-coverage-spans.md](telemetry-coverage-spans.md)) also emits **three structured JSON log lines per intercepted invocation**:

- **Entry log** — emitted before method invocation
- **Exit log** — emitted on normal return
- **Exception log** — emitted on throw

Each line carries:

- `code.namespace`, `code.function`
- `sie.aop.args.summary` (types and sizes only, never values; respects ADR-001 D-7 cap)
- `sie.aop.duration_ms` (exit and exception lines)
- `exception.type` / `exception.message` / `exception.stacktrace` (exception line only)
- `gsm.tenant.id`, `sie.component` from MDC (per ADR-001 D-3)
- `trace_id`, `span_id` (correlation per ADR-001 D-3)

Emission is governed by a single configurable Helm value `observability.aop.logLevel` (enum: `TRACE` | `DEBUG` | `INFO` | `WARN` | `ERROR` | `OFF`). One value controls entry, exit, and exception logs uniformly. Default: `INFO`. Per-environment overrides happen in `environments/{dev,preprod,prod}/values.yaml`.

This invariant is **enforced by `BroadInstrumentationAspectIT`** (added in Unit 5 of S-004b), which asserts that every stereotype bean method in `cloud.poesis.sie.defman.**` emits entry / exit / exception JSON log lines (when `observability.aop.logLevel != OFF`) with the required fields.

Per-branch / per-sub-call logs remain **developer discipline**, enforced by PR review only (see checklist below). F-AC-8 (≥95% telemetry coverage) is satisfied on the log axis when the structural layer is at 100% (per ADR-002 D-9).

---

## Non-AOP log axis items

The following log-axis coverage units have **content/structure requirements beyond the structural entry/exit/exception layer** (e.g. error duality semantics, payload-content logging per ADR-001 D-3). They remain individually enumerated and require dedicated test evidence.

| Flow                                           | Layer                 | Owner (class.method)                                       | Trigger                              | Unit Type                                  | Coverage Status                      | Expected Telemetry                                                                                                                                            | Required Attributes                                                                                                                                                      | Expected Test Evidence                                            | Implementing Story |
| ---------------------------------------------- | --------------------- | ---------------------------------------------------------- | ------------------------------------ | ------------------------------------------ | ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------- | ------------------ |
| **Tenant Extraction (Present)**                | Filter                | `TenantMdcFilter.doFilter`                                 | X-Tenant-Id header present           | MDC + span attribute                       | `required`                           | MDC key `gsm.tenant.id`, active span attribute `gsm.tenant.id`                                                                                                | `gsm.tenant.id=<sanitized value>`                                                                                                                                        | Filter test with present header                                   | S-005              |
| **Tenant Extraction (Missing)**                | Filter                | `TenantMdcFilter.doFilter`                                 | X-Tenant-Id header absent            | MDC + span attribute                       | `required`                           | MDC key `gsm.tenant.id=unknown`, span attribute `gsm.tenant.id=unknown`                                                                                       | `gsm.tenant.id=unknown`                                                                                                                                                  | Filter test with missing header                                   | S-005              |
| **MDC Cleanup**                                | Filter                | `TenantMdcFilter.doFilter` (finally)                       | Request completion                   | MDC clear                                  | `required`                           | MDC keys removed after request                                                                                                                                | No MDC leak to next request/test                                                                                                                                         | Filter test asserting cleanup                                     | S-005              |
| **JSON Log Rendering**                         | Logback               | Logback JSON encoder                                       | Any log line                         | JSON log structure                         | `required`                           | Valid JSON with stable field names (`timestamp`, `level`, `logger`, `message`, `trace_id`, `span_id`, `gsm.tenant.id`, `sie.component`)                       | All listed fields when in-span and during HTTP request                                                                                                                   | Logback config + test asserting JSON validity                     | S-006              |
| **Log-Trace Correlation (In-Span)**            | Logback               | Logback JSON encoder                                       | Log line inside active span          | MDC trace/span fields                      | `required`                           | `trace_id` and `span_id` populated from MDC                                                                                                                   | `trace_id`, `span_id` (non-zero, non-null)                                                                                                                               | Test emitting log inside span, asserting fields present           | S-006              |
| **Log-Trace Correlation (No-Span)**            | Logback               | Logback JSON encoder                                       | Log line outside active span         | MDC trace/span fields absent               | `required`                           | `trace_id` and `span_id` absent (not zero/null placeholders)                                                                                                  | Fields not present in JSON                                                                                                                                               | Test emitting log outside span, asserting fields absent           | S-006              |
| **Create Payload Logging (Under Cap)**         | Service               | `AscriptionService.create` (create branch)                 | Payload < cap                        | Bounded JSON log (ADR-001 D-3)             | `required`                           | Full payload in JSON log field                                                                                                                                | `sie.payload.bytes=<actual>`, `sie.payload.capped=false`                                                                                                                 | Test with small payload, assert full payload logged               | S-007 + S-009      |
| **Transform Payload Logging (Over Cap)**       | Service               | `AscriptionService.create` (transform branch)              | Payload > cap                        | Bounded JSON log + span event (ADR-001 D-3) | `required`                          | Truncated log payload with `…<truncated:N bytes>` suffix; capped span event summary                                                                           | `sie.payload.bytes=<original>`, `sie.payload.capped=true`, span event with capped preview                                                                                | Test with large payload, assert truncation marker + span event    | S-007 + S-009      |
| **Payload Sampled Bypass**                     | Service               | Payload helper                                             | Bypass rate hit                      | Full payload sibling log (ADR-001 D-3)     | `required`                           | Second JSON log line with full payload and `sie.payload.sampled=true`                                                                                         | `sie.payload.sampled=true`, full payload present                                                                                                                         | Test with bypass rate=1.0, assert bypass log exists               | S-007              |
| **Caught Unrecoverable Error**                 | Controller Advice     | `GlobalExceptionHandler` (or similar)                      | Unhandled exception at HTTP boundary | ERROR log + span exception (S-011 duality) | `required`                           | One ERROR JSON log with exception.type, exception.message, exception.stacktrace, trace_id, span_id, tenant, component; span marked error with exception event | All listed fields                                                                                                                                                        | Test 500 error path, assert log + span error status               | S-011              |
| **Caught Recoverable Warning**                 | Service (catch sites) | Various service methods                                    | Caught + handled exception           | WARN log + span event (S-011 duality)      | `required`                           | One WARN JSON log + span event (no duplicate logs); span NOT marked error                                                                                     | `exception.type`, `exception.message`, trace/span correlation                                                                                                            | Test recoverable warning, assert log + span event, no duplicate   | S-011              |
| **Persistence Exception Translation**          | Service               | `PersistenceExceptionTranslationService`                   | JDBC/persistence exception           | Translated exception + telemetry           | `required`                           | Exception translation + recoverable warning telemetry per S-011                                                                                               | Translated exception type, original cause                                                                                                                                | Test DB constraint violation, assert translation + telemetry      | S-011              |
| **Catch-Site Audit (Rethrow-Only)**            | All                   | All `catch` blocks                                         | Catch-and-rethrow                    | No emission                                | `no-instrumentation`                 | Catch site logs nothing, just rethrows                                                                                                                        | N/A                                                                                                                                                                      | Documented in PR/QA sign-off per AC-5                             | S-011              |
| **Validation Exception Duality**               | Service               | Validation services                                        | Validation error                     | ERROR/WARN log + span exception per S-011  | `required`                           | Error duality per S-011 rules                                                                                                                                 | Exception type, message, trace/span correlation                                                                                                                          | Test validation exception, assert S-011 duality                   | S-012 + S-011      |
| **Bootstrap Seed Failure**                     | Bootstrap             | `ArchetypeSeedRunner.run`                                  | Seed exception                       | ERROR log + span exception                 | `required`                           | Seed span marked error, ERROR log with trace/span correlation                                                                                                 | Exception type, message, trace/span correlation                                                                                                                          | Test seed failure, assert span error status + log                 | S-013              |
| **Log Level Configuration**                    | Config                | Logback / `observability.aop.logLevel`                     | Application startup                  | Log level setup                            | `required`                           | `observability.logLevel` and `observability.aop.logLevel` applied per environment                                                                             | Effective log level matches Helm value                                                                                                                                   | Helm test + config test                                           | S-014              |

---

## Coverage Status Values

| Status                      | Meaning                                                                                                                |
| --------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `structural` (AOP)          | Entry/exit/exception logs produced by construction by `BroadInstrumentationAspect`; verified by `BroadInstrumentationAspectIT` (Unit 5 / S-004b) |
| `required`                  | Must have test evidence; counted in non-AOP log coverage denominator                                                   |
| `no-instrumentation`        | Intentionally not instrumented (e.g., rethrow-only catch blocks per S-011 AC-5)                                        |

---

## Branch-level developer-discipline checklist

Per ADR-002 D-8, per-branch / per-sub-call logs remain developer discipline, enforced by PR review only. Reviewers MUST verify, for each domain touched by the PR, that meaningful branch outcomes are logged with adequate context.

Domains and checklist placeholders (fill in per-PR as code lands):

- **HTTP / Filters (S-005, S-006)** — tenant header sanitization branches, MDC scope boundaries.
- **Payload handling (S-007)** — under-cap / over-cap / sampled-bypass branches.
- **Ascription lifecycle (S-008)** — accepted transition, rejected transition, cascade taken, cascade skipped, activation hook, deactivation hook.
- **Definition transform pipeline (S-009)** — create branch, transform branch, subtype `afterCreate` hook, Mechanism port derivation.
- **Repository / persistence (S-010)** — JDBC tree-shape sanity (AOP INTERNAL parent over agent CLIENT child) — span axis, not log; log here only for translated persistence exceptions.
- **Error / warning duality (S-011)** — unrecoverable error path, recoverable warning path, catch-rethrow path (no log), persistence translation path.
- **Validation (S-012)** — Starlark parse pass/fail, Starlark validation outcome, Norm CEL applicability outcome, Norm CEL assertion outcome.
- **Bootstrap (S-013)** — seed per-kind iteration outcome, seed failure path.
- **Config / runtime (S-014)** — collector outage detection, recovery on endpoint return.

---

## Coverage Target (PRD-001 §4, F-AC-2, F-AC-8)

**Quantitative criterion (PRD-001 §4 / F-AC-8):** ≥95% of `required` units (denominator = non-AOP rows above) must have passing evidence. The AOP-covered entry/exit/exception log set is 100% structurally and is not part of the percentage denominator (the structural test green-light *is* the evidence).

**Qualitative criterion (F-AC-2):** No critical state-changing flow (caught error/warning handling, payload-content logging, lifecycle/bootstrap failure logging) may be uncovered, regardless of aggregate percentage. The AOP aspect guarantees method-level entry/exit/exception lines; non-AOP rows above carry the semantic-content requirements; the branch-level checklist carries the developer-discipline depth.

---

## Routing Table (S-004b)

- **S-004b:** `BroadInstrumentationAspect` + `BroadInstrumentationAspectIT` (structural entry/exit/exception layer)
- **S-005:** Tenant extraction (present/missing), MDC cleanup
- **S-006:** JSON log rendering, log-trace correlation (in-span/no-span)
- **S-007:** Payload logging (under-cap/over-cap), sampled bypass
- **S-009:** Create/transform payload logging (combined with S-007)
- **S-011:** Caught unrecoverable error, caught recoverable warning, persistence exception translation, catch-site audit
- **S-012:** Validation exception duality
- **S-013:** Bootstrap seed failure
- **S-014:** Log level configuration

---

## Revision History

| Date       | Story  | Change                                                                |
| ---------- | ------ | --------------------------------------------------------------------- |
| 2026-05-26 | S-004  | Initial map created per approved HUDDLE outline                       |
| 2026-05-26 | S-004b | Split per ADR-002 D-9; AOP rows collapsed under structural invariant; branch-level discipline checklist added |
