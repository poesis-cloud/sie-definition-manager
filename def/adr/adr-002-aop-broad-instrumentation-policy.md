---
id: ADR-002
title: AOP-based broad instrumentation policy for sie-definition
status: accepted
product: sie-definition
parent_feature: F-01
deciders:
  - central-supervisor
  - SE-Architect
created: 2026-05-26
decided: 2026-05-26
amends: ADR-001
---

# ADR-002 — AOP-based broad instrumentation policy for sie-definition

> Restored from the `poesis` repository history (`products/sie-definition/architecture/`,
> removed in commit `1ffcb49`). `sie-definition` is the former name of the SIE Definition Manager.

> **Transversal knowledge.** The reusable broad-AOP instrumentation-floor pattern (one INTERNAL span + entry/exit/exception logs per stereotype-bean method, proxy-boundary coverage semantics, no-opt-out, final-method constraint) is captured in the [`observability-otel`](https://github.com/poesis-cloud/poesis/blob/main/.github/skills/observability-otel/SKILL.md) knowledge skill. This ADR records the **sie-definition-specific** decisions (D-8, D-9).

## Context

ADR-001 resolved seven structurant OQs (D-1…D-7) but was silent on _which_ methods get a span and an entry/exit/exception log. S-004 filled that silence with a curated `@InternalSpan` marker-annotation model. The Central Supervisor has reversed that on 2026-05-26: coverage-by-forgetting is unacceptable; the floor must be structural.

This ADR is a narrow amendment to ADR-001. D-1…D-7 are untouched. It adds D-8 (broad-pointcut AOP) and D-9 (split coverage map).

## Decision

### D-8 — Broad AOP instrumentation

A single Spring AOP aspect under `cloud.poesis.sie.defman.observability.*` wraps **every method** of **every bean** annotated (directly or meta-annotated) with any Spring stereotype — `@Component`, `@Service`, `@Repository`, `@Controller`, `@RestController`, `@Configuration` — residing in `cloud.poesis.sie.defman.**`.

No opt-out annotation. No exclusion list. Everything is instrumented.

Per intercepted invocation, the aspect emits:

- **One INTERNAL span** with attributes:
  - `code.namespace` (FQ class), `code.function` (method name)
  - `sie.aop.args.summary` (types and sizes only, never values; respects ADR-001 D-7 cap)
  - `sie.aop.duration_ms`
  - `exception.type` / `exception.message` / `exception.stacktrace` on throw (OTel-native)
  - `gsm.tenant.id`, `sie.component` from MDC (per ADR-001 D-3)
- **Three structured JSON log lines** (entry, exit, exception) with the same fields plus `trace_id` / `span_id`.

Log emission is governed by a single configurable Helm value `observability.aop.logLevel` (enum: `TRACE` | `DEBUG` | `INFO` | `WARN` | `ERROR` | `OFF`). One value controls entry, exit, and exception logs uniformly. Default in `values.yaml`: `INFO`. Per-environment overrides happen in `environments/{dev,preprod,prod}/values.yaml` as operators see fit; this ADR does not legislate per-env defaults.

Per-branch / per-sub-call logs remain developer discipline, enforced by PR review only.

Note on `@RestController` and JDBC `@Repository` overlap with the OTel Java agent: the AOP span wraps the _Spring bean method_; the agent's HTTP SERVER span and JDBC CLIENT spans become parents (HTTP) or children (JDBC) of the AOP span. This is the desired tree shape, not double-counting.

Note on `final` classes/methods: Spring AOP is proxy-based and cannot intercept `final` methods or methods on `final` classes. The S-004b implementation enforces a structural test that fails the build if any Defman stereotype bean declares a `final` method, so the "no opt-out" invariant is preserved at compile-time rather than degraded silently at runtime.

### D-9 — Coverage axes split

The single S-004 telemetry coverage map is consolidated into the product-level coverage registry ([spans](../telemetry-coverage-spans.md), [logs](../telemetry-coverage-logs.md)), which carries three orthogonal axes:

- §1 Span axis — 100% by construction of stereotype-bean methods in `cloud.poesis.sie.defman.**`. Enforced by one integration test.
- §2 Log axis — 100% by construction at method granularity; per-branch depth is a developer-discipline checklist.

F-AC-8 (≥95% telemetry coverage) is satisfied when both axes' structural layers are at 100% (enforced by the integration test).

## Consequences

- F-01 `adrs:` frontmatter list extended to include ADR-002. F-AC-8 wording remains valid (coverage target satisfied structurally by D-9's two axes).
- S-004 scaffold (`@InternalSpan`, `InternalSpanAspect`, `ObservabilityPackageDisciplineTest`, single coverage map) is superseded. Executed by follow-up Story **S-004b**.
- Span and log volume increase. The ADR-001 D-4 perf budget (5% p95) remains binding and is verified by the S-015 perf harness against the new implementation. If breached, the remediation is to lower `observability.aop.logLevel` in the affected env; no per-method opt-out exists.
- Argument values are never serialized — only types and sizes (ADR-001 D-7 discipline preserved).
- ADR-001 is updated by RTE to add `amended_by: ADR-002`.

## References

- ADR-001 — collector, sampling, attribute vocabulary, perf budget, payload cap.
- F-01 — `adrs:` list extended.
- S-004 — superseded scaffold, PR #3 merged.
- S-004b — follow-up implementation Story (filed by RTE on acceptance).
