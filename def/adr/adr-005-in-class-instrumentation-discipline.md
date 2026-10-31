---
id: ADR-005
title: In-class instrumentation discipline (manual telemetry minimization)
status: proposed
product: sie-definition
parent_feature: F-01
amends: [ADR-002]
extends: [ADR-003]
deciders:
  - central-supervisor
  - SE-Architect
consulted:
  - SE-DevOps-CI
created: 2026-06-09
decided: null
supersedes: null
superseded_by: null
---

# ADR-005 — In-class instrumentation discipline (manual telemetry minimization)

> Restored from the `poesis` repository history (`products/sie-definition/architecture/`,
> removed in commit `1ffcb49`). `sie-definition` is the former name of the SIE Definition Manager.

> **Transversal knowledge.** The manual-telemetry discipline this ADR adopts is maintained product-agnostically as **INST-R1..R13** in the [`instrumentation-coverage`](https://github.com/poesis-cloud/poesis/blob/main/.github/skills/instrumentation-coverage/SKILL.md) skill, with the surrounding OTel-native posture in [`observability-otel`](https://github.com/poesis-cloud/poesis/blob/main/.github/skills/observability-otel/SKILL.md). This ADR records the **sie-definition-specific** adoption (D-1..D-7).

> **Numbering note.** `ADR-004` is reserved for the metrics posture (see ADR-003 D-7: "A successor
> ADR (`ADR-004`) will formalize the metrics posture when the first metric Story lands"). This
> instrumentation-discipline decision is therefore filed as `ADR-005`.

## Context

ADR-002 (D-8/D-9) established a **broad AOP instrumentation floor**: a single aspect wraps every
method of every stereotype bean in `cloud.poesis.sie.defman.**`, emitting one INTERNAL span plus
entry/exit/exception structured logs per invocation. ADR-002 D-9 explicitly declares that the
structural layer satisfies F-AC-8 and that **per-branch depth is "developer discipline, enforced by
PR review only."** The coverage registry `features/telemetry-coverage.md` encodes this: e.g. the
lifecycle transition flow is contracted as `structural (AOP) + enrichment` (only
`gsm.ascription.id`, `gsm.ascription.state.from`, `gsm.ascription.state.to`).

Separately, the `instrumentation-coverage` skill (the S-008/S-009 AC-8 gate) was interpreted as
"for every high/medium-audit branch, emit an in-class span event **and** a correlated log." Applied
literally, this produced manual instrumentation that **dominates** the business surface:

- `AscriptionStatusTransitionService` (S-008, merged via PR #9) added ~10 `EVENT_HOOK_*` constants
  and correlated success/skip/failure logs at ~10 call sites — far beyond its own registry contract
  of "AOP span + 3 enrichment attributes."
- `AscriptionService` (S-009, PR #10) emits a correlated INFO log for **every** success milestone in
  addition to the span event for the same milestone, and adds in-class events for steps that are
  actually delegated to other stereotype beans.

The result conflates business logic with instrumentation (>50% of the create/transform and
transition surfaces), **double-emits** the same fact (span event + correlated INFO log), duplicates
the ADR-002 AOP floor, and pushes against the ADR-001 D-4 perf budget. This is an incoherence
between the `instrumentation-coverage` skill and ADR-002 D-9 that S-008/S-009 inherited.

## Decision

Manual, in-class telemetry is a **thin enrichment layer** on top of the ADR-002 AOP floor — the
**exception**, not the default. It adds only what the AOP/delegated spans cannot express.

### D-1 — Two-layer model

Telemetry is (a) the **ADR-002 AOP structural floor** (automatic per-method span + entry/exit/
exception logs) plus (b) a **thin manual enrichment layer**. The manual layer is the **exception, not
the default**: prefer reclassification (`delegated`/`AOP-structural`), an explicit waiver, or an
observability collaborator over adding in-class telemetry.

### D-2 — Enrich the AOP span; no manual spans

A business operation MUST NOT open a manual span. It **enriches the active AOP span**
(`Span.current()`) with the domain attributes the generic AOP span lacks (`gsm.<primitive>.operation`,
definition/ascription id, kind, tenant, component). Since the broad AOP floor already emits exactly
one INTERNAL span per stereotype-bean method, a dedicated manual span would be span-on-span
duplication. Pure helpers and delegated calls add nothing — they are covered by their own AOP spans.

### D-3 — In-class explicit telemetry is reserved for high-audit branches

In-class explicit telemetry is limited to: (i) the **single error boundary** (see D-7), recorded
**once** (`recordException` + error status + at most one log with body); and (ii) **skips that change
observable outcome** (span event; WARN log only if audit-relevant). **Success milestones are span
EVENTS only** — they MUST NOT additionally emit a correlated log. Emitting the same fact as both a
span event and a log is forbidden double-emission. Correlated structured logs are reserved for
**WARN/ERROR**.

### D-4 — Coverage accounting (refines ADR-002 D-9; binds the `instrumentation-coverage` skill)

A branch counts as covered when it is **AOP-structural**, **delegated** (the callee is another
stereotype bean → its AOP span covers it), or **in-class explicit** per D-3. Calls to **non-beans**
(static helpers, entities, DTOs, value records) are **not** delegated-covered. Branches delegated to
stereotype beans MUST NOT be re-instrumented. No-audit guards are **explicitly waived** (excluded).
The gate is **binary**: every audit-relevant branch is **covered or waived** — no percentage.

### D-5 — Canonical rule set (INST-R1..INST-R13)

This decision is operationalized as **thirteen hard, testable rules `INST-R1..INST-R13`** maintained
in the `instrumentation-coverage` skill (§0). The skill is the canonical, product-agnostic source;
this ADR adopts it for `sie-definition`. Every observability Story (Sprint 3 onward) MUST carry an
**`AC-INST`** acceptance criterion binding its implementation to INST-R1..R13. The harnessed Driver
and QA agents MUST load the skill for any Story changing telemetry.

### D-6 — Means of verification (QA Alignment Audit)

Every observability QA sign-off MUST include the **Instrumentation Alignment Audit** (skill §5b): a
per-rule PASS/FAIL table plus two **machine checks** — INST-R7 (no agent-owned OTel-native
attributes set from code) and INST-R8 (no `trace_id`/`span_id` written from code). A FAIL on either
machine check is a hard gate failure regardless of the coverage decision. This complements (does not
replace) the ADR-003 D-8 sem-conv compliance row.

### D-7 — Error telemetry at the boundary (INST-R13)

Terminal error telemetry — one ERROR/WARN log plus transport error mapping — is emitted **once**, at
the inbound **edge adapter** where the exception stops propagating: the `@RestControllerAdvice` for
HTTP, a Kafka listener's error handler for consumers, a scheduler wrapper for jobs; or, for a
recoverable error, the catch site that **handles-and-swallows** it. Pass-through service/domain
layers **rethrow without manual error logging** — their AOP spans already carry the exception as it
propagates. This keeps a reused service transport-agnostic: an HTTP caller and a Kafka caller each
own their own error→outcome mapping and terminal log, and the service never double-logs.

## Options considered

1. **Keep in-class-per-branch (status quo)** — cons: conflates business and instrumentation; >50%
   telemetry; double-emission; perf-budget pressure; contradicts ADR-002 D-9. Rejected.
2. **Option B — lean on AOP + delegated + waivers (CHOSEN, now).** Pros: coherent with ADR-002 D-9
   and ADR-003; minimal manual code; lower telemetry volume. Cons: requires trimming S-008/S-009.
3. **Option A — observability collaborator/facade (deferred).** Pros: cleanest physical separation
   of concerns. Cons: adds structure now. Recommended as an optional later refinement on top of B.

## Consequences

- **Positive.** Business methods read as clean steps; telemetry volume drops (ADR-001 D-4 headroom);
  policy is coherent with ADR-002 D-9 and ADR-003 D-2/D-4.
- **Negative / rework.** `AscriptionStatusTransitionService` (S-008) and `AscriptionService` (S-009)
  over-instrument under this policy and must be **trimmed**; both Stories reopen for alignment and
  re-QA under D-4. PR #10 is paused until the trim lands.
- **Confirmed violation (INST-R8).** Both merged S-008 (`AscriptionStatusTransitionService` lines
  ~664/668) and S-009 PR #10 (`AscriptionService` lines ~428/432) **write `trace_id`/`span_id`
  onto the OTel `LogRecord` from code** — exactly what ADR-003 D-4 declares agent-owned and
  machine-checked-forbidden. `SemConvGuardTest` only guards MDC writes, so the `LogRecordBuilder`
  path slipped through; the §5b INST-R8 grep closes that hole.
- **ACs encode the anti-pattern.** The "Span-event/log correlation" AC in S-008/S-009/S-010/S-011/
  S-012/S-013 mandates a correlated log carrying `trace_id`/`span_id` for **state-changing** (incl.
  success) milestones, and S-010/S-013 catalog rows mandate "INFO correlated success" evidence.
  Re-alignment therefore fixes **ACs first**, then code.
- **Implementation note (trim, not git-revert).** The alignment is a **forward trim**, not a
  `git revert`: delete the manual domain spans and redundant success-milestone correlated logs and
  the delegated-bean in-class events, keep the **AOP-span enrichment attributes** (S-008/S-009), the
  single error boundary, and bounded ascription-statement-content logging. A full
  revert-then-reimplement would also discard the parts D-2/D-3 keep, so it is more work, not less.
- **Artifacts updated.** `instrumentation-coverage` skill (D-3/D-4 minimization rule),
  `features/telemetry-coverage.md` §3 AC-8 framing (on acceptance), and a new
  `.github/instructions/observability-instrumentation.instructions.md`. Future observability Stories
  (S-010+) inherit D-1..D-4.

## Implementation hooks

- `sie/sie-definition-manager` — `AscriptionService.java`, `AscriptionStatusTransitionService.java`
- `.github/skills/instrumentation-coverage/SKILL.md`
- `products/sie-definition/features/telemetry-coverage.md` (§3 logic-to-instrumentation axis)
- `.github/instructions/observability-instrumentation.instructions.md`

## References

- **ADR-001** — D-4 perf budget; D-3 attribute vocabulary.
- **ADR-002** — D-8 broad AOP floor; D-9 "per-branch is developer discipline" (refined by this ADR).
- **ADR-003** — D-2 OTLP-bridged logs; D-4 agent-owned OTel-native attributes; D-8 sem-conv gate.
- **`instrumentation-coverage` skill** — control-flow-first method; amended to encode D-3/D-4.
- **`features/telemetry-coverage.md`** — registry contract for the affected flows.
