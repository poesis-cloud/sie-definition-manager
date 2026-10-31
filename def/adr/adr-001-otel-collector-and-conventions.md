---
id: ADR-001
title: OpenTelemetry collector topology, sampling, and attribute vocabulary for sie-definition
status: accepted
product: sie-definition
parent_feature: F-01
deciders:
  - central-supervisor
  - SE-Architect
consulted:
  - SE-Security
  - SE-DevOps-CI
created: 2026-05-25
decided: 2026-05-25
supersedes: null
superseded_by: null
amended_by: ADR-002
---

# ADR-001 — OpenTelemetry collector topology, sampling, and attribute vocabulary for sie-definition

> Restored from the `poesis` repository history (`products/sie-definition/architecture/`,
> removed in commit `1ffcb49`). `sie-definition` is the former name of the SIE Definition Manager.

> **Transversal knowledge.** The reusable, product-agnostic patterns referenced here (OTLP-only transport, shared-collector topology + deployment modes, SDK-vs-collector resource-attribute split, perf budget, payload cap, graceful flush) are captured in the [`observability-otel`](https://github.com/poesis-cloud/poesis/blob/main/.github/skills/observability-otel/SKILL.md) knowledge skill. This ADR records the **sie-definition-specific** decisions (D-1..D-7).

## Context

PRD-001 establishes the observability foundation for product `sie-definition` (Definition Manager + Definition Database correlation) using OpenTelemetry logs and traces, with W3C Trace Context propagation and 100% OTel semantic-conventions compliance. PRD-001 §11 deferred **seven open questions** (OQ-1…OQ-7) to Gate 2: collector topology, sampling strategy, `sie.*` / `gsm.*` attribute vocabulary, perf-overhead budget, shared-library timing, retention hand-off, and a payload-size cap on definition-content logs.

Feature **F-01** is flagged `structurant: true` precisely because these seven questions are **tightly coupled** — the collector topology constrains where exporters live; the attribute vocabulary drives cardinality which feeds back into the perf-overhead budget; payload size caps interact with retention sizing; and the shared-library decision shapes how every later SIE service inherits the same answers. Resolving these in **one ADR** prevents ripple incoherence across the Helm shape, instrumentation code, and downstream per-service PRDs.

This ADR is scoped to **logs and traces only** (metrics deferred per PRD-001 §5) and to **sie-definition** (Definition Manager + JDBC correlation to Definition Database).

### Deployment context that shapes the topology choice

SIE is delivered as a **per-tenant deployment**: one SIE instance per customer, on-premise or in cloud, sitting inside the customer's Kubernetes cluster. Observability decisions must respect three properties of that context:

- **Customer-owned backends.** The actual telemetry store (Tempo, Loki, App Insights, Splunk, Datadog, ELK, …) is chosen and operated by the customer, not by SIE. SIE's responsibility ends at producing standards-compliant OTLP and offering a routing point the customer can configure.
- **Heterogeneous sink capability.** Some on-premise customers can only consume telemetry via container **stdout** (no reachable OTLP endpoint, no external network egress). Others run their own OTel Collector and expect OTLP. Others use vendor-specific exporters. The same SIE chart must serve all of them through configuration alone.
- **Multi-product SIE deployment.** A single tenant deployment will eventually run several SIE products (`sie-definition`, `sie-blackboard`, `sie-operator`, `sie-event-bus`, …). Cross-product trace continuity is required (one `trace_id` end-to-end across SIE products participating in a flow), and **a single per-tenant configuration surface** is preferable to per-product duplication of routing, redaction, and exporter choices.

The topology must therefore offer **one place to configure the customer's sink**, accommodate **stdout-only** fallback, and be **shared across all SIE products** in the same tenant deployment.

## Decision

We adopt an in-cluster, vendor-neutral OpenTelemetry pipeline for `sie-definition` with seven coupled decisions, each resolving one open question from PRD-001 §11.

### D-1 (OQ-1) — Collector topology: single shared `sie-otel-collector` per tenant deployment

Deploy **one** OpenTelemetry Collector per tenant deployment as a **single Helm release** named **`sie-otel-collector`** in the `sie` namespace, owned by SIE (not by individual SIE products). It is a small Deployment (≥2 replicas for HA) exposing a stable in-cluster OTLP/gRPC endpoint (`sie-otel-collector.sie.svc.cluster.local:4317`).

- **All SIE products** in the tenant deployment (`sie-definition-manager`, and later `sie-blackboard`, `sie-operator`, `sie-event-bus`, `sie-schema-registry`) export OTLP to this one collector. This gives **cross-SIE-product trace continuity** for free and **one configuration surface per tenant**.
- **The export pipeline (the customer-facing sink) is a Helm values block** in `sie-otel-collector`'s chart. Supported exporter profiles ship out of the box:
  - `otlp` — forward to a customer-owned collector or backend via OTLP/gRPC or HTTP.
  - `logging` — write OTLP records (traces and logs) to the collector pod's **stdout** in line-delimited form, for customers whose only telemetry pipeline is `kubectl logs` / Fluent Bit / Loki-via-stdout. `trace_id` and `span_id` are preserved in every line so correlation survives the stdout transit.
  - `file` — write to a mounted PVC, for air-gapped / batch-collection customers.
  - **Multi-exporter fan-out** — any combination of the above can be enabled simultaneously per-signal (logs / traces).
- **No sidecar, no DaemonSet.** Products talk OTLP directly to the `sie-otel-collector` service in the same namespace (one hop). This was justified by the previous two-tier proposal _only_ because of tail-sampling needs (D-2 below now removes that need at this stage).
- **Resilience.** OTel SDK exporters retry with bounded in-process buffer; collector unavailability does not block product startup or take down products (satisfies PRD-001 §9).
- **Lifecycle ownership.** The `sie-otel-collector` Helm chart lives in a dedicated repo (TBC by platform); per-tenant values files live alongside per-product values in `ops/helm/environments/<env>/values.yaml` so the customer configures one place.

This topology is the most direct fit for SIE's per-tenant deployment model, supports stdout-only customers natively, scales linearly with tenant load (gateway replicas), and remains vendor-neutral.

#### Supported deployment modes (single switch: `observability.mode`)

Every SIE product chart (starting with `sie-definition-manager`) ships a single Helm values switch — `observability.mode` — that wires the OTel Java SDK env vars correctly without code changes. Three modes are supported:

- **`shared-collector` (default, recommended).** Product exports OTLP to `sie-otel-collector.sie.svc.cluster.local:4317`. Requires the `sie-otel-collector` Helm release to be installed in the tenant deployment. This is the mode that delivers all benefits described above (single per-tenant config surface, cross-SIE-product aggregation point, centralized processing, polished stdout fallback, multi-exporter fan-out, future tail-sampling option).
- **`direct`.** Product exports OTLP straight to a customer-owned endpoint (their own OTel Collector, a vendor SaaS that accepts OTLP, etc.) configured per product. No `sie-otel-collector` release required. Supported but secondary — the customer accepts a list of tradeoffs (see below).
- **`stdout`.** Product configures the SDK `logging` exporter to write OTLP JSON to its own pod stdout, interleaved with logback application logs. No `sie-otel-collector` release required. For fully air-gapped customers whose only telemetry pipeline is `kubectl logs` and who do not want a dedicated SIE collector pod.

**Tradeoffs accepted in `direct` and `stdout` modes (relative to `shared-collector`):**

- No single per-tenant configuration surface — customer reconfigures each SIE product separately (endpoint, headers, token rotation).
- No central aggregation point — customer's backend sees N producers per tenant instead of 1; cross-SIE-product trace continuity still works via W3C Trace Context propagation but the network shape is many-to-one.
- No centralized processing — PII scrubbing, attribute normalization, redaction, span dropping must be done either in the SDK (per-product or shared library) or in the customer's collector.
- `stdout` mode mixes OTLP JSON with regular logback application logs in the same pod stdout — the customer's log pipeline must demux.
- No multi-exporter fan-out (SDK supports limited combos; collector is trivial).
- Reduced resilience — only the SDK's bounded in-process retry buffer; no disk-backed queue.
- Vendor-specific exporters (Datadog APM, Splunk HEC, Dynatrace native, App Insights native) unavailable — OTLP only.
- No future SIE-wide sampling policy possible — sampling would have to be done in the customer's backend.

**Mechanism.** The switch translates to SDK env vars in `ops/helm/templates/deployment.yaml`:

| `observability.mode` | `OTEL_TRACES_EXPORTER` | `OTEL_LOGS_EXPORTER` | `OTEL_EXPORTER_OTLP_ENDPOINT`                          |
| -------------------- | ---------------------- | -------------------- | ------------------------------------------------------ |
| `shared-collector`   | `otlp`                 | `otlp`               | `http://sie-otel-collector.sie.svc.cluster.local:4317` |
| `direct`             | `otlp`                 | `otlp`               | `${observability.otlp.endpoint}` (customer-provided)   |
| `stdout`             | `logging`              | `logging`            | (unset)                                                |

All three modes preserve W3C Trace Context propagation, MDC `trace_id` / `span_id` injection, the OTel Java agent, OTel semantic conventions, and the `sie.*` / `gsm.*` attribute vocabulary defined in D-3.

### D-2 (OQ-2) — Sampling: keep all signals, no sampling (for now)

Apply **no sampling**. Head-based sampler ratio at the SDK is `1.0` in **every** environment (dev, preprod, prod), and **no tail-sampling processor** is configured on `sie-otel-collector`. All spans and all log records are exported.

- This preserves PRD-001's outcome hypothesis to the strictest possible degree: nothing is ever dropped, so every transaction is always fully reconstructable.
- It is appropriate while volumes are small (early customers, pilot deployments) and while we lack production telemetry to make a defensible sampling policy.
- It will be revisited when (and only when) a specific tenant's backend cost or cardinality forces it. Revision will be **per-tenant Helm values override**, not a code change — `sie-otel-collector` ships with the OTel tail-sampling processor available but disabled by default. A future ADR (likely amending or superseding this one) will define the policy when the trigger appears.
- Always-keep guarantees for errors and `gsm.*` lifecycle spans become **vacuous** under "keep all" — they remain documented in the configuration template (commented-out) so the future sampling ADR has a known good starting point.

### D-3 (OQ-3) — Attribute vocabulary: checked-in `sie.*` / `gsm.*` reference table

Adopt the vocabulary in the table below as the **normative extension** to OTel semantic conventions for `sie-definition`. All custom attributes live under `sie.*` or `gsm.*`. All standard concerns continue to use OTel-native attributes (`http.*`, `db.*`, `code.*`, `exception.*`, `service.*`).

**Span naming convention:**

- Lifecycle transitions: `gsm.ascription.transition` (one span per state change, parent = caller span).
- Rule evaluation: `gsm.rule.evaluate` (one span per rule execution).
- Definition operations: `gsm.definition.<operation>` (e.g. `gsm.definition.create`, `gsm.definition.transform`).
- HTTP / JDBC / outbound: OTel-default span names (do not override).

**Attribute table:**

| Attribute                   | Type          | Required on                                                                                                                                | Allowed values / shape                                                                                                          | Cardinality                               |
| --------------------------- | ------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------- |
| `gsm.definition.id`         | string (UUID) | `gsm.definition.*` spans, definition-touching log events                                                                                   | UUID                                                                                                                            | **high** (1 per definition)               |
| `gsm.definition.kind`       | string (enum) | `gsm.definition.*` spans                                                                                                                   | one of the 8 GSM primitives (`Structure`, `Mechanism`, `Effector`, `Receptor`, `Interaction`, `Archetype`, `Directive`, `Norm`) | low (≤ 8)                                 |
| `gsm.definition.operation`  | string (enum) | `gsm.definition.*` spans                                                                                                                   | `create` \| `transform` \| `read` \| `delete`                                                                                   | low (≤ ~6)                                |
| `gsm.ascription.id`         | string (UUID) | `gsm.ascription.*` spans, ascription log events                                                                                            | UUID                                                                                                                            | **high**                                  |
| `gsm.ascription.state.from` | string (enum) | `gsm.ascription.transition` spans                                                                                                          | Ascription lifecycle state name                                                                                                 | low                                       |
| `gsm.ascription.state.to`   | string (enum) | `gsm.ascription.transition` spans                                                                                                          | Ascription lifecycle state name                                                                                                 | low                                       |
| `gsm.rule.id`               | string        | future `gsm.rule.evaluate` spans emitted by `sie-operator`; Defman authoring-time validation spans MAY use it when a stable rule id exists | stable Starlark rule id (e.g. `itip.gdpr.r_42`)                                                                                 | medium (bounded by rule catalog)          |
| `gsm.rule.outcome`          | string (enum) | future `gsm.rule.evaluate` spans; Defman validation spans MAY use it to describe validation result                                         | `pass` \| `fail` \| `error`                                                                                                     | low (3)                                   |
| `gsm.rule.duration_ms`      | int           | future `gsm.rule.evaluate` spans; Defman validation spans MAY use it for parse/validation duration                                         | non-negative ms                                                                                                                 | low (numeric)                             |
| `gsm.tenant.id`             | string        | every span and log line emitted inside a tenant-scoped operation                                                                           | tenant slug or UUID                                                                                                             | medium                                    |
| `sie.component`             | string (enum) | every span and log line                                                                                                                    | `definition-manager` \| `definition-database-client` \| `blackboard-client` \| `event-bus-client`                               | low (closed set, extended by future ADRs) |

**Cardinality discipline:** high-cardinality attributes (`gsm.definition.id`, `gsm.ascription.id`, `gsm.tenant.id`) are span/log attributes only — **never** promoted to metric labels (a separate future metrics PRD will reaffirm this).

### D-4 (OQ-4) — Performance overhead budget: 5% p95 ceiling confirmed, measured by JMH+k6 baseline

Confirm the PRD-001 §9 ceiling: **instrumentation MUST NOT regress p95 request latency by more than 5%** vs. a non-instrumented baseline. Measurement protocol:

- **Baseline run:** Definition Manager with OTel agent disabled (`-Dotel.javaagent.enabled=false`), against a representative k6 scenario (mix of create / transform / validation failure / read at 50 RPS sustained over 10 min, single-node preprod-equivalent).
- **Instrumented run:** same k6 scenario, agent enabled, head sample ratio set to `1.0` (worst case), OTLP exporting to a local agent.
- **Pass criterion:** `(p95_instrumented − p95_baseline) / p95_baseline ≤ 0.05`.
- Captured in a repeatable harness under `sie/sie-definition-manager/src/test/perf/` (Story-level deliverable post-Gate-2).

### D-5 (OQ-5) — Shared library: **defer** to second SIE-service adoption

Do **not** introduce a shared Spring Boot starter / library now. The SIE Definition Manager implements the standard inline; a starter would be extracted only when a **second** SIE service (likely `sie-blackboard` per its own future PRD) adopts the same pattern and we can factor based on two concrete consumers. Premature abstraction risks baking Definition-Manager-specific decisions into a shared API.

The inline implementation MUST keep instrumentation code isolated under a single package (`cloud.poesis.sie.defman.observability.*`) to make later extraction mechanical.

### D-6 (OQ-6) — Retention hand-off: out of scope; owned by platform

Log and trace retention at the collector gateway and the eventual sink is **explicitly out of scope** of this ADR. The decision owner is the **platform team** (currently held by the Central Supervisor with `SE: DevOps/CI` consulted) and will be filed as a separate **platform-level ADR** (`docs/platform/architecture/adr-NNN-telemetry-retention.md` — exact path to be confirmed when platform repo conventions land). This ADR documents only that retention is configured exclusively at Tier B (gateway) and downstream sink — never at the application or agent tier.

### D-7 (OQ-7) — Payload-size cap on definition-content logs: 16 KB hard cap with truncation marker, sampled full-payload above threshold

Apply a **size-cap-with-sampled-bypass** policy on log events that carry definition or ascription payloads (`gsm.definition.create`, `gsm.definition.transform`, contribution accept/reject):

- **Hard cap:** payload field serialized length ≤ **16 KB** (UTF-8). Above that, truncate the payload field at 16 KB and append the marker `…<truncated:N bytes>`, where `N` is the original length. The enclosing span receives a payload summary event (operation, definition/ascription id, original byte length, truncation marker, and a capped payload preview) rather than an uncapped full-payload span event. This keeps D-7 coherent with D-2's current **no sampling** decision.
- **Sampled bypass:** **1 in 100** truncated log events (env-configurable) emit the full untrimmed payload as a sibling log line tagged `sie.payload.sampled=true`, to preserve audit/explainability coverage without flooding the sink while sampling remains off.
- **Operability:** cap value and sample rate are Helm-configurable (`observability.payloadCapBytes`, `observability.payloadBypassRate`).

## Options considered

### OQ-1 — Collector topology

1. **Sidecar per pod** — pros: strongest isolation, simplest per-app config. Cons: per-pod overhead, doubles pod count, cross-product correlation requires extra fan-in, awkward for stdout-only customers.
2. **DaemonSet agent only** — pros: one collector per node, low overhead, node-local. Cons: per-node config surface, no single per-tenant configuration point, awkward to expose stdout fallback.
3. **DaemonSet agent + central gateway Deployment (original proposal)** — pros: node-local fast path + gateway tier enables tail-sampling. Cons: two tiers to operate; tail-sampling is the _only_ justification for the second tier and is no longer needed (D-2 keeps all). Over-engineered for SIE's per-tenant scale.
4. **Single shared `sie-otel-collector` Deployment per tenant — CHOSEN.** Pros: one configuration surface per tenant (matches the per-tenant deployment model), shared by all SIE products (free cross-product trace continuity), native stdout fallback via the `logging` exporter for air-gapped/on-prem customers, minimal ops surface (one Helm release), vendor-neutral via configurable exporter profiles. Cons: one fewer tier of failure isolation than option 3 (mitigated by ≥2 replicas + SDK retry buffer).

### OQ-2 — Sampling

1. **Pure head-based ratio per env (no tail).** Pros: simple. Cons: down-samples errors and lifecycle spans — defeats explainability outcome.
2. **Always-on (100%) everywhere, no sampling — CHOSEN.** Pros: nothing is ever dropped — PRD-001 outcome hypothesis preserved to the strictest degree; correct posture while volumes are small and production data is not yet available to defend a policy; per-tenant Helm override available when a real trigger appears. Cons: prod volume is unbounded — acceptable today, will be revisited via a follow-up ADR when a specific customer's backend cost forces it.
3. **Head-based ratio per env + tail-based always-keep for errors / `gsm.*` / outliers (original proposal).** Pros: bounded volume with retained governance traces. Cons: introduces tail-sampling complexity (and a justification for a second collector tier) before we have any production data showing the volume is actually a problem. **Premature optimization.**

### OQ-3 — Attribute vocabulary

1. **Reuse only OTel semantic conventions, no extension.** Pros: zero invention. Cons: cannot express GSM lifecycle, rule outcomes, definition operations — defeats AC-3 / AC-5.
2. **Free-form attributes per emission site.** Pros: maximum flexibility. Cons: cardinality drift, attribute explosion, breaks AC-5.
3. **Checked-in `sie.*` / `gsm.*` reference table (single source of truth) — CHOSEN.** Pros: governed cardinality, single point of evolution, future metrics PRD inherits it. Cons: any new attribute requires an ADR amendment (acceptable — mirrors GSM discipline).

### OQ-4 — Perf overhead budget

1. **No ceiling.** Pros: simplest. Cons: regressions land silently.
2. **2% p95 ceiling.** Pros: aggressive. Cons: likely unrealistic for full-coverage instrumentation at 100% sampling.
3. **5% p95 ceiling with documented JMH+k6 measurement protocol — CHOSEN.** Pros: matches PRD-001 §9 proposal, measurable, achievable, has a fail criterion. Cons: requires a baseline harness (Story-level work).

### OQ-5 — Shared library

1. **Introduce shared starter now.** Pros: reuse from day one. Cons: abstraction based on a single consumer — almost guaranteed to be wrong shape.
2. **Never extract; copy-paste per service.** Pros: simplicity per service. Cons: drift across SIE services, breaks reference-implementation goal.
3. **Defer until second SIE-service adoption — CHOSEN.** Pros: extract from two concrete consumers; isolates instrumentation in a clearly-scoped package now to make later extraction mechanical. Cons: one service will pay a small refactor cost when the second adopts.

### OQ-6 — Retention hand-off

1. **Decide retention here.** Pros: complete answer. Cons: retention spans multiple products and platform infra — outside `sie-definition`'s scope.
2. **Hand off to platform — CHOSEN.** Pros: correct ownership. Cons: requires platform-level follow-up ADR.

### OQ-7 — Payload-size cap

1. **No cap, log full payloads always.** Pros: full fidelity. Cons: log volume unbounded; some Ascription / Archetype payloads can be tens of KB.
2. **Hard cap, truncate without recovery.** Pros: bounded. Cons: loses audit fidelity for large payloads.
3. **Hard cap with truncation marker + sampled full-payload bypass + capped span-event summary — CHOSEN.** Pros: bounded log volume and bounded trace payload while preserving a configurable audit sample. Cons: slightly more complex emission logic and full-payload recovery is sampled rather than guaranteed above the cap.

## Consequences

**Positive:**

- Single coherent contract for the seven coupled questions — unblocks F-01 transition `adr-pending → ready` at Gate 2 with no residual ambiguity.
- One per-tenant configuration surface (`sie-otel-collector`'s values.yaml) for the entire SIE product family in a deployment — matches SIE's per-tenant delivery model exactly.
- Native support for **stdout-only** customers via the OTel `logging` exporter, with `trace_id` / `span_id` preserved in every line — unblocks the pilot on-prem deployment without any code carve-out.
- Free **cross-SIE-product trace continuity** because all products feed one collector.
- Zero data loss while volumes are small (keep-all sampling) — maximises PRD-001 explainability outcome in the period where it matters most (early customers, pilots, first production incidents).
- Checked-in attribute vocabulary gives the future metrics PRD, the future shared-library extraction, and every later SIE service a stable starting point.
- Defer-the-library decision avoids premature abstraction while keeping inline code isolated for mechanical extraction.

**Negative:**

- Production telemetry volume is unbounded until a sampling policy is defined — acceptable today, becomes a real concern at scale; mitigation is per-tenant Helm override on `sie-otel-collector` plus a future sampling-policy ADR.
- `sie-otel-collector` is a per-tenant shared dependency across SIE products — its outage affects observability for all SIE products in that tenant (mitigated: ≥2 replicas, SDK retry buffer, products do not block on collector availability).
- Any new `sie.*` / `gsm.*` attribute requires an ADR amendment — slows ad-hoc evolution but is consistent with GSM governance discipline.
- Inline-instrumentation-now means the first cross-service refactor (when service #2 adopts) will incur a one-time extraction cost.
- 16 KB payload cap will truncate some real Archetype/Ascription payloads at INFO level — mitigated by capped span-event summaries and 1% sampled full-payload bypass.

**Follow-up Features / ADRs:**

- **Per-service adoption PRDs** (`sie-blackboard`, `sie-operator`, `sie-event-bus`, `sie-schema-registry`) MUST reference this ADR as the binding standard for collector wiring ("export OTLP to `sie-otel-collector.sie.svc.cluster.local:4317`"), attribute vocabulary, and payload cap.
- **`sie-otel-collector` Helm chart repository / location** — platform decision (Central Supervisor + `SE: DevOps/CI`), produces a chart that ships the documented exporter profiles (`otlp`, `logging`, `file`, multi).
- **Platform-level retention ADR** to be filed by the platform team (owner: Central Supervisor + `SE: DevOps/CI` consulted) — addresses OQ-6 at the customer-backend layer.
- **Sampling-policy ADR** to be filed when a tenant's backend cost or volume forces a real sampling decision — will amend or supersede D-2.
- **Future metrics PRD** for `sie-definition` will likely amend or supersede the cardinality decisions in D-3 (especially for `gsm.definition.id` and `gsm.ascription.id` — these MUST NOT become metric labels; that PRD will reaffirm).
- **Shared OTel starter library** extraction ADR, to be filed when the second SIE service adopts this standard (resolves the defer in D-5).
- **JMH+k6 perf-baseline harness** Story under F-01 (deliverable to validate D-4 budget).

## Implementation hooks

All hooks intersect `product.yaml` `repos[]` (`sie/sie-definition-manager`, `sie/sie-definition-database`).

> **Clarification 2026-05-25 (Sprint-1 / S-001 HUDDLE):** the observability package path is `cloud.poesis.sie.defman.observability.*`, matching the existing `defman` module root. D-5's isolation invariant ("a single package, mechanically extractable") is preserved unchanged; only the literal package string is clarified. Non-normative change; no Gate 2 re-vote.

**`sie/sie-definition-manager/`:**

- `pom.xml` — add `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter` (or align with current GA OTel Java agent + SDK BOM); add `opentelemetry-exporter-otlp`; declare `opentelemetry-bom` for version management.
  - `src/main/java/cloud/poesis/sie/defman/observability/` — new package; isolated instrumentation code (lifecycle span emitters, validation telemetry helpers, payload-cap log helpers, MDC trace/span propagation hooks). Isolation is mandatory per D-5 to enable mechanical extraction later.
- `src/main/resources/application.yaml` — `otel.*` properties wired to env vars; `logging.pattern.*` with `%X{trace_id}` / `%X{span_id}` MDC; JSON structured-log layout (Logback `LogstashEncoder` or equivalent).
- `src/main/resources/logback-spring.xml` — JSON appender + payload-cap filter implementing D-7.
- `src/test/java/cloud/poesis/sie/defman/observability/` — JaCoCo-covered tests asserting span shape, attribute presence, error duality (AC-4), and payload-cap truncation marker. JaCoCo ≥ 95% per repo invariant.
- `src/test/perf/` — JMH+k6 baseline harness implementing D-4 protocol.
- `ops/helm/environments/{dev,preprod,prod}/values.yaml` — Helm values exposing:
  - `observability.mode` (enum: `shared-collector` (default) | `direct` | `stdout`; selects the deployment mode per D-1)
  - `observability.otlp.endpoint` (string; defaults to `http://sie-otel-collector.sie.svc.cluster.local:4317`; overridden by customer in `direct` mode; ignored in `stdout` mode)
  - `observability.sampling.headRatio` (number; per D-2 default `1.0` in every environment; exposed for future per-tenant override)
  - `observability.payloadCapBytes` (int; default `16384`)
  - `observability.payloadBypassRate` (number; default `0.01`)
  - `observability.logLevel` (enum)
  - `service.name`, `service.version`, `service.namespace` (= `poesis`; Kubernetes namespace remains `sie`)
- `ops/helm/templates/deployment.yaml` — env vars `OTEL_SERVICE_NAME`, `OTEL_RESOURCE_ATTRIBUTES`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_TRACES_SAMPLER` (= `parentbased_always_on` per D-2), plus the OTel Java agent JAR mounted via init-container or baked into the image.
- `Dockerfile` — bundle the OTel Java agent JAR; `-javaagent:` flag wired via container `JAVA_TOOL_OPTIONS`.

**`sie/sie-definition-database/`:**

- No code changes inside the database. Helm values may expose `db.logStatementMinDurationMs` for correlation with JDBC spans — Story-level deliverable, not required by this ADR.

**Out of this ADR's repo scope (separate Helm release in `sie` namespace, owned by SIE):**

- **`sie-otel-collector`** Helm release — single per-tenant OpenTelemetry Collector Deployment (≥2 replicas) hosting the configurable exporter pipeline (`otlp` / `logging` / `file` / multi). Chart location TBC by platform; service DNS contract is fixed: `sie-otel-collector.sie.svc.cluster.local:4317`.
