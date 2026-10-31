---
id: ADR-003
title: OTel-native observability posture (logs + traces; metrics roadmapped)
status: accepted
accepted: 2026-06-01
governor_decisions:
  OQ-D2: "Default sink=both accepted (durable until S-014)"
  OQ-D6: "terminationGracePeriodSeconds=30 accepted"
  OQ-D7: "Metrics Stories no earlier than Sprint-3 accepted"
product: sie-definition
supersedes: []
extends: [ADR-001]
authors: [SE-Architect]
deciders: [Central Supervisor]
created: 2026-06-01
---

# ADR-003 — OTel-native observability posture (logs + traces; metrics roadmapped)

> Restored from the `poesis` repository history (`products/sie-definition/architecture/`,
> removed in commit `1ffcb49`). `sie-definition` is the former name of the SIE Definition Manager.

> **Transversal knowledge.** The reusable OTel-native posture (OTLP-only transport, agent/SDK-owned native attributes and trace/span correlation, MDC-vs-Baggage context, dual-sink logs, compliance gate) is captured in the [`observability-otel`](https://github.com/poesis-cloud/poesis/blob/main/.github/skills/observability-otel/SKILL.md) knowledge skill. This ADR records the **sie-definition-specific** decisions (D-1..D-8).

> **Numbering note.** This ADR was drafted as `ADR-002` in the original Central Supervisor brief. ID `ADR-002` was already taken by `adr-002-aop-broad-instrumentation-policy.md` (broad-pointcut AOP, accepted 2026-05-26). This ADR is therefore filed as `ADR-003`. The successor metrics ADR referenced in **D-7** is `ADR-004` (TBD).

## Context

ADR-001 fixed the MDC vocabulary (`gsm.tenant.id`, `sie.component`) and the collector topology (D-1) but **left log transport unspecified at the application boundary**. ADR-001 D-1 allows three deployment modes (`shared-collector` / `direct` / `stdout`) at the SDK-export level for **traces and SDK-exported logs**, but it did not normatively decide how the Java application _emits_ log records to the OTel SDK in the first place, nor whether application stdout JSON logging remains a permanent or transitional sink. Story **S-006** (JSON logs with trace/span MDC correlation) surfaced that gap when it landed as a stdout-only structured-logging proposal and was rolled back to backlog on 2026-06-01.

On 2026-06-01 the **Central Supervisor set the system-wide posture: 100% OTel-compliant**. The 2026-06-01 OTel sem-conv compliance audit (logged in `products/sie-definition/sprint-2/progress.md` and `products/sie-definition/sprint-2/qa/S-005-signoff.md` round-2) confirmed the existing attribute vocabulary (ADR-001 D-3, S-004, S-004b, S-005) is sem-conv-compliant. This ADR formalizes the posture for **logs and traces** going forward.

**Scope:**

- **In scope:** logs and traces — transport, context propagation, resource attributes, shutdown/flush, compliance gating.
- **Out of scope (roadmapped only):** metrics. D-7 acknowledges the future posture but does not decide it; a successor ADR (`ADR-004`) will formalize when the first metric Story lands.

This ADR **EXTENDS** ADR-001. It does not replace any decision in ADR-001 and is fully compatible with ADR-002 (AOP broad instrumentation).

## Decisions

### D-1 — Transport: OTLP-only for logs and traces

All log records and all spans exit Defman via **OTLP** to the `sie-otel-collector` (ADR-001 D-1). The collector is the single egress point; no application-side direct-to-vendor exporters are introduced.

- **Rationale.** Vendor-neutral; symmetric with traces (already OTLP via the OTel Java agent — see Dockerfile `-javaagent:/otel-agent.jar`); enables backend swap without an application change (swap a collector exporter, not Defman code); preserves the "one configuration surface per tenant" property established by ADR-001 D-1.
- **Alternatives rejected.**
  - _Direct vendor exporters from the SDK_ (Datadog APM, Splunk HEC, App Insights native, …) — vendor lock-in; violates ADR-001 D-1's per-tenant configuration-surface property; re-introduces per-product config drift.
  - _Fluentbit (or any sidecar/DaemonSet log shipper) parallel to OTLP_ — redundant pipeline next to the collector; two places to govern redaction, sampling, attribute normalization; defeats the symmetry-with-traces principle.
- **Consequences.** All log-shipping operability lives at the collector (ADR-001 D-1). Future per-tenant exporter mixes are achieved by collector multi-exporter fan-out, not by re-wiring Defman.

### D-2 — Logs: dual-sink with Helm switch, OTel-bridged primary

Defman emits log records through **two configurable sinks**:

- **Primary sink: OTLP** via the OTel Java agent's Logback `OpenTelemetryAppender`, auto-bridged when the SDK log exporter is enabled in agent configuration (`OTEL_LOGS_EXPORTER=otlp`).
- **Secondary sink: stdout structured JSON** via Spring Boot 3.5's **built-in structured logging** (`logging.structured.format.console=ecs` recommended; ECS is OTel-attribute-aligned in its naming).

A single Helm switch controls which sinks are active:

```
observability.logs.sink ∈ { otlp, stdout, both }
```

**Default value: `both`.**

- **Rationale for `both` as default.** Durable during ramp: when the collector is reachable, OTLP is the authoritative pipeline; when it is not (collector outage, dev `mvn spring-boot:run` with no collector, CI pipelines, emergency triage), `kubectl logs` still works. Duplication cost is small at current volumes. Explicit roadmap: flip default to `otlp` once collector-outage handling (S-014) lands.
- **Rationale for keeping stdout as a permanent option (not just transitional).**
  - Local development (`mvn spring-boot:run`) routinely runs without a collector.
  - CI pipelines build and run unit/integration tests without a collector.
  - Emergency triage when the collector or its downstream is unavailable — operators must still be able to read structured logs from the pod.
- **Encoder choice for the stdout sink:** Spring Boot 3.5's first-party **built-in structured logging** (`logging.structured.format.console=ecs` or `logstash`/`gelf`/`otlp` formats as offered by Boot). NOT `net.logstash.logback:logstash-logback-encoder` (third-party dependency; "Logstash" in the name is misleading since ECS is the OTel-aligned schema; first-party Boot support has zero added dependencies and tracks Boot's lifecycle).
- **Alternatives rejected.**
  - _Stdout-only_ (S-006 as originally written) — incompatible with Central Supervisor's 100% OTel posture; loses the OTLP-native log/trace correlation that the agent's `OpenTelemetryAppender` provides for free.
  - _OTLP-only from day one_ — too brittle while collector-outage handling (S-014) is not in place; loses local-dev and CI ergonomics.
  - _Third-party `LogstashEncoder`_ — adds a dependency for what Boot 3.5 ships in-tree; "Logstash" branding is a footgun against the OTel-native posture.
- **Consequences.** Two Helm-driven configuration paths to maintain (OTLP appender wiring + stdout encoder wiring). Both are governed from the same `observability.logs.*` Helm block to keep the surface unified.

### D-3 — Context: MDC stays in-process; Baggage for cross-service

The ADR-001 D-3 MDC vocabulary stands unchanged:

- `gsm.tenant.id` and `sie.component` continue to be MDC keys.
- `TenantMdcFilter` remains the **sole writer** of those keys (per its existing cohabitation contract).

For **cross-service propagation** — when Defman starts calling other SIE services, or when orchestrators / clients call Defman carrying tenant context — a future Story will bridge MDC ↔ OTel **`Baggage` API** so that `gsm.tenant.id` rides the **W3C `baggage` header** alongside W3C `traceparent`.

- **Rationale.** MDC is a thread-local in-process concern; W3C Baggage is the OTel-standard wire format for application-level context. The two must be bridged, not conflated.
- **Scope of THIS ADR.** Establish the principle and the direction. The bridge implementation (filter on inbound = read baggage → MDC; interceptor on outbound = read MDC → baggage) is **out of scope here** and will be a separate Story when the first cross-SIE-product call lands.
- **Alternatives rejected.** Custom `X-Tenant-Id` propagation across services — would diverge from the OTel-native posture and require bespoke header handling at every boundary.

### D-4 — Attribute vocabulary unchanged

The 2026-06-01 OTel sem-conv compliance audit confirmed:

- OTel-native attributes (`http.*`, `db.*`, `code.*`, `exception.*`, `service.*`, `url.*`, `network.*`, …) remain **agent-owned**. Defman does not re-implement them.
- Vendor namespaces (`gsm.*`, `sie.*`) remain governed by **ADR-001 D-3** and any future amendments to that table.
- Audit result: **compliant**. No vocabulary change required by this ADR.

### D-5 — Resource attributes: SDK-emitted + Collector-enriched

A clean split:

- **Defman emits** (via OTel SDK / agent autoconfig + `OTEL_RESOURCE_ATTRIBUTES`):
  - `service.name`
  - `service.version`
  - `service.instance.id`
- **`sie-otel-collector` enriches** (via `k8sattributes` processor):
  - `k8s.namespace.name`
  - `k8s.pod.name`
  - `k8s.node.name`
  - `deployment.environment.name`

No Defman code sets Kubernetes/deployment-environment resource attributes. That is the agent + collector responsibility.

- **Rationale.** Defman does not (and should not) have ambient knowledge of its own pod/namespace/node/env. The collector does (via downward API + cluster metadata).
- **Alternatives rejected.** Injecting K8s attributes from Helm into Defman env vars — duplicates information the collector already enriches; couples Defman releases to K8s metadata changes.

### D-6 — Shutdown / flush

- **SIGTERM triggers the OTel SDK graceful shutdown** (agent default behaviour: flush all batch processors then exit).
- **Helm Pod spec MUST set `terminationGracePeriodSeconds` ≥ OTel batch flush interval + safety margin.** Recommended value: **30s**.
- **`preStop` hook: not required.** The agent handles flush on SIGTERM. This MUST be documented in `ops/helm/templates/deployment.yaml` (and the chart README) so future maintainers do not add a redundant `preStop` sleep.

- **Rationale.** Standard OTel-recommended posture; avoids reinventing flush logic; mirrors what other OTel-instrumented Spring Boot services do in production.
- **Open question OQ-D6** (see "Open questions" section): is 30s sufficient under worst-case OTLP batch backlog?

### D-7 — Metrics (ROADMAP, not a decision in this ADR)

**Out of scope for this ADR.** Documented here only so the metrics direction is unambiguous when the first metric Story arrives (expected Sprint-3 at the earliest, pending Central Supervisor confirmation).

When metrics Stories arrive, expect:

- **Transport:** OTLP only (same posture as D-1).
- **Source:** OTel auto-instrumentation (agent) + manual instrumentation via the OTel API where domain metrics are required.
- **Exemplars:** metric→trace exemplars enabled (so a dashboard data-point can jump to a representative trace).
- **Cardinality discipline:** high-cardinality attributes from ADR-001 D-3 (`gsm.definition.id`, `gsm.ascription.id`, `gsm.tenant.id`) **MUST NOT** be promoted to metric labels.

A **successor ADR (`ADR-004`)** will formalize the metrics posture when the first metric Story lands. Until then, no metric-emitting code is added to Defman beyond what the agent emits by default.

### D-8 — Compliance gate

Every observability Story from S-006 onwards (in the `sie-definition` observability domain) **MUST** include an explicit "OTel sem-conv compliance" check in its QA sign-off. The check is a single table row asserting:

1. **Naming.** Attribute names follow OTel naming (lowercase, dot-namespaced, vendor-prefix `gsm.*`/`sie.*` for non-standard attributes).
2. **No reserved-namespace collisions.** No attribute or span name collides with reserved namespaces (`otel.*`, `telemetry.*`).
3. **Standard concerns delegated.** No re-implementation of agent-owned concerns (HTTP, DB, JDBC, exceptions, code attributes).

Ivy (QA) is the row's owner; sign-off cannot complete without it.

- **Rationale.** Bakes the Central Supervisor's 100% OTel posture into the per-Story acceptance pipeline. Drift becomes a sign-off blocker, not a post-hoc audit finding.
- **Operational note.** The audit performed 2026-06-01 (PASS for S-004 / S-004b / S-005) becomes the reference template for this row.

## Consequences

### Positive

- **Vendor-neutral observability fabric** end-to-end (Defman → collector → customer-chosen sink).
- **Symmetric signal handling** (logs ↔ traces today; metrics tomorrow with the same posture).
- **Simpler backend migrations** — swap a collector exporter, not application code.
- **Stdout remains operable** for local dev, CI, and emergency triage; no operability regression vs. today.
- **Compliance enforcement at the Story level** (D-8) prevents drift between sprints.
- **Cross-service tenant propagation has a clear forward path** (D-3 Baggage bridge) without committing implementation cost now.

### Negative / costs

- **Two Helm-driven config paths to maintain**: OTLP appender configuration + stdout encoder configuration. Mitigated by keeping both under a single `observability.logs.*` Helm block.
- **Default `sink=both` doubles log volume during ramp.** Mitigated by stdout sampling later if needed, and by flipping the default to `otlp` once S-014 lands.
- **Cross-service tenant propagation (MDC↔Baggage bridge) added to backlog** — work not yet scheduled.
- **Operability documentation burden** — `ops/helm/templates/deployment.yaml` comments must explain why no `preStop` hook is present (D-6) and what `observability.logs.sink` does (D-2).

### Invalidates / requires rework

- **S-006 as originally written** (stdout-only structured-logging assumption) — already rolled back to backlog 2026-06-01; awaits re-decomposition after this ADR is accepted.
- **HUDDLE doc `.S-006-huddle.md`** — preserved as historical reference; **not executed**. The re-decomposed S-006 (post-ADR-003) will get a fresh huddle.

## Compliance impact on existing artifacts

| Artifact                                  | Impact                                                                                                                                                                                                                                                      |
| ----------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ADR-001                                   | Unchanged. This ADR EXTENDS it.                                                                                                                                                                                                                             |
| ADR-002 (AOP broad instrumentation)       | Unchanged. Fully compatible — AOP-emitted spans/logs flow through whichever sinks D-1/D-2 enable.                                                                                                                                                           |
| S-004 (OTel Java agent in image)          | Already compliant (2026-06-01 audit PASS). No rework.                                                                                                                                                                                                       |
| S-004b (AOP entry/exit/exception logging) | Already compliant (2026-06-01 audit PASS). No rework.                                                                                                                                                                                                       |
| S-005 (`TenantMdcFilter`)                 | Already compliant (2026-06-01 audit PASS). No rework.                                                                                                                                                                                                       |
| S-007 (bounded payload logging, backlog)  | Scope unchanged — payload bounding applies regardless of sink.                                                                                                                                                                                              |
| F-01 acceptance criteria F-AC-1..F-AC-10  | **No AC text changes required.** F-AC-5 (structured log vocabulary) and F-AC-6 (log-to-trace correlation) are now satisfied by the **OTLP-native bridge** (D-2 primary sink), not by stdout JSON parsing. The AC wording is sink-neutral and remains valid. |
| `application.yaml` (Defman)               | Will gain `observability.logs.sink` mapping at S-006 re-implementation time; no change required at ADR acceptance.                                                                                                                                          |
| `ops/helm/environments/*/values.yaml`     | Will gain `observability.logs.sink` key at S-006 re-implementation time; no change required at ADR acceptance.                                                                                                                                              |

## References

- **ADR-001** — `products/sie-definition/architecture/adr-001-otel-collector-and-conventions.md` (D-1 collector topology; D-3 attribute vocabulary; Implementation Hooks).
- **ADR-002** — `products/sie-definition/architecture/adr-002-aop-broad-instrumentation-policy.md` (AOP broad instrumentation; orthogonal and compatible).
- **OTel sem-conv — general attribute naming.** https://opentelemetry.io/docs/specs/semconv/general/attribute-naming/
- **OTel Java agent — Logback `OpenTelemetryAppender` bridge.** https://opentelemetry.io/docs/zero-code/java/agent/configuration/#logback-appender
- **ECS logging format.** https://www.elastic.co/guide/en/ecs/current/index.html (Spring Boot 3.5 built-in structured logging `ecs` format uses this schema; chosen for OTel-aligned naming).
- **Spring Boot 3.5 — Structured logging.** https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured
- **2026-06-01 OTel sem-conv audit.** `products/sie-definition/sprint-2/qa/S-005-signoff.md` (round-2 section); summary in `products/sie-definition/sprint-2/progress.md`.
- **S-006 rollback context.** `products/sie-definition/sprint-2/stories/.S-006-huddle.md` (historical; not executed).

## Open questions for Central Supervisor

- **OQ-D2** — D-2 default `observability.logs.sink=both`: confirm acceptable as the steady-state default **until** collector-outage handling (S-014) lands, after which we flip the default to `otlp`?
- **OQ-D6** — D-6 grace period: is **30s** sufficient for `terminationGracePeriodSeconds`, or do we need a higher value to cover worst-case OTLP batch backlog?
- **OQ-D7** — D-7 metrics roadmap: confirm metrics Stories start **no earlier than Sprint-3**, and that `ADR-004` (metrics posture) is the right vehicle when the first metric Story is filed?
