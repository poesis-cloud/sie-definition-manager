# SIE Definition Manager

SIE (Systemic Intelligence Engine) is an **AI Context Platform**: it turns “context” into a governed, typed, provenance-backed asset (observations + definitions + evaluations) that can be assembled for AI reasoning, deterministic checks, and remediation decisions.
This module hosts the **Definition plane** conceptual models, primarily the **GSM (Generative System Model)** — SIE's generative/definitional system model.

## GSM ↔ DSM (Definition–Description Duality)

GSM is one half of SIE's core duality:

- **GSM** (this module) defines *what the system must become*: principles, policies, rules, controls, mechanisms, and their governance relationships. GSM primitives are active definitions from which structure, behavior, and viability are derived. GSM has **definitional primacy**.
- **DSM** (`../sie-description-manager/`) captures *what the system currently is*: state descriptions, signals, measurements, comparisons, deviations, and assessments. DSM feeds evidence into GSM's generative machinery. DSM has **evidential primacy**.

In the Six Planes model:

- **Governance + Regulation planes** (GSM) = controller semantics
- **Supervision plane** (DSM) = sensor + comparator semantics
- **Coordination plane** = stabilizes pacing between GSM and DSM
- **Operation plane** = actuator — realizes GSM definitions
- **State plane** = ground truth — observed by DSM

Closed-loop flow: State → DSM (observe) → GSM (define/adjust) → Operation (actuate) → State.

Definition without description is dogma; description without definition is noise.

### Structural and behavioral description (completeness–complexity tradeoff)

GSM deliberately stops at the **Structural properties layer** — the lowest definitional layer. Structure defines the arrangement and constraints of behavior (Rules, Controls, Mechanisms, topology), but behavior itself emerges in the State plane and is *observed*, not *defined*.

DSM mirrors this structural layer and extends observation into behavior:

- **Structural description** captures system topology at a point in time (components, interfaces, mechanisms, connections). It is cheap, stable, and immediately available — the efficient approximation.
- **Behavioral description** captures state transitions, event sequences, and I/O patterns over time. It is the more complete epistemic form: given perfect behavioral observation, structure can be inferred.

This creates a natural priority gradient for DSM:

1. **Discovery mode**: structural descriptions dominate (cheapest to acquire, sufficient to scaffold governance definitions via Emergence).
2. **Steady-state mode**: behavioral descriptions deepen (needed for dynamic Form Appraisal — does the system *behave* as defined?).
3. **At convergence**: behavioral observation approaches completeness, and structural assumptions become empirically grounded rather than inferred.

GSM and DSM are therefore coupled at the structural layer — this is their shared correlation surface — but DSM's descriptive reach extends beyond structure into behavior, where governance definitions are ultimately validated or falsified.

## Where to start

- GSM model: `definition/sie-gsm-v1.puml`
- DSM model (companion): `../sie-description-manager/definition/sie-dsm-v1.puml`
- Supervision information model: `../sie-supervision/definition/sie-supervision-information-model-v1.puml`
The canonical SIE overview README has been moved to:

- ../README.md

## Definition-time appraisal (plane integration)

The Definition Manager is not a passive store. When definitions are created, modified, or deleted, the Definition Manager triggers **Norm Appraisal and Form Appraisal by the relevant plane components**:

| Definition change | Appraising plane | What is checked |
|-------------------|------------------|-----------------|
| Principle created/modified | **Governance** (`../sie-governance/`) | Constitutive Norm Appraisal: Principle ↔ Quality, scope validity, verb conflicts, governance plane coverage |
| Policy created/modified | **Regulation** (`../sie-regulation/`) | Constraint Norm Appraisal: direction vs. Principle verb, metric→Quality path, scope containment, Policy↔Policy conflicts |
| Rule/Control created/modified | **Regulation** (`../sie-regulation/`) | Rule→Policy coverage, Control integrity, SIE DSL validity, CEL transcompilability |
| Structural changes (Component, Interface, Channel) | **Regulation** (`../sie-regulation/`) | Structural Form Appraisal (CardinalityPolicyConstraint, DependencyPolicyConstraint) |
| Any definition activated | **Supervision** (`../sie-supervision/`) | Re-appraise (Form Appraisal) existing observations against the new/updated definition |
| Cross-system definition changes | **Coordination** (`../sie-coordination/`) | Inter-system coherence, collision detection, oscillation dampening (Stabilization) |

This means each `PROPOSED → APPROVED → ACTIVE` transition in `SystemicPrimitiveDefinitionStatus` passes through the relevant plane's appraisal rules before advancing. The Definition Manager orchestrates these checks; the planes own the rules.

### Norm Appraisal at definition time

The Definition Manager is the primary trigger for **Norm Appraisal** — the appraisal of the form of norms (definition ↔ definition, intra-normative). Every definition change passes through Norm Appraisal before the definition can advance in its lifecycle. The three coherence dimensions checked are:

| Dimension | What it checks | Definition Manager triggers |
|-----------|----------------|----------------------------|
| **Consistency** | No contradictions between norms | Principle verb conflict detection (NA-PRIN-01); Policy↔Policy constraint conflicts (NA-POL-04); Control binding consistency (NA-CTRL-01); mapping type compatibility (NA-CTRL-02) |
| **Completeness** | No gaps within declared normative scope | PPRC chain traversability (NA-PPRC-01); Policy operationalization coverage (NA-POL-08); Rule→Policy minimum (NA-RULE-01) |
| **Adequacy** | Norms fit for declared purpose | Policy→Principle direction coherence (NA-POL-01); metric→purposeQualifier path tracing (NA-POL-02, SUSPENDED); scope containment (NA-POL-03); Principle applicability guard validation (NA-PRIN-02); Policy→Purpose grounding (NA-POL-05); Policy→purposeQualifier path tracing (NA-POL-06, SUSPENDED); Policy→Component grounding (NA-POL-07); Rule→Policy scope containment (NA-RULE-02); Rule behavioral grounding (NA-RULE-04, NA-RULE-05) |

Norm Appraisal is executed by the **Governance** and **Regulation** planes. The Definition Manager sends definition-change events; those planes evaluate and return appraisal findings. Findings at `ERROR` severity block lifecycle transitions; `WARNING` severity allows advancement but flags the finding for review.

### Form Appraisal on definition activation

The Definition Manager triggers **Form Appraisal** — the appraisal of the form of reality (definition ↔ reality, trans-normative) — when a definition transitions to `ACTIVE`. At that point, existing state-plane observations (held by the Description Manager / DSM) must be re-evaluated against the new or updated definition. The three coherence dimensions checked are:

| Dimension | What it checks | Definition Manager triggers |
|-----------|----------------|----------------------------|
| **Fidelity** | Observed behavior matches definitions | Mechanism definition ↔ observed behavior (FA-MECH-01); Policy constraints ↔ measured metrics (FA-POL-01); Actuation feedback loop verification (FA-ACT-01) |
| **Coverage** | Observed state has corresponding definitions | StateObjectArchetype ↔ observed state (FA-STATE-01); detect state objects or observed mechanisms lacking GSM definitions (input to Emergence) |
| **Convergence** | Formation stabilizes toward the norm | Normative envelope convergence tracking (FA-CONV-01); track whether repeated appraisal cycles show decreasing deviation between definitions and reality |

Form Appraisal is executed by the **Supervision** plane. On definition activation, the Definition Manager publishes a `DefinitionActivatedEvent`; the Supervision plane re-appraises all relevant observations against the activated definition and produces Form Appraisal findings. Structural Form Appraisal (checking structural Policy constraints like cardinality and dependency) is also triggered by the Regulation plane when structural definitions change.

## Mode behavior

- **Discovery mode**: Definition Manager receives definition proposals from Governance Determination: Emergence (inductive: observations → definition drafts). Definitions enter as DRAFT, progress through governance review. Plane appraisals apply as definitions advance toward ACTIVE — even in discovery, a proposed Policy must be coherent with its Principle (Regulation Norm Appraisal), and a proposed Principle must not conflict with existing Principles (Governance Norm Appraisal).
- **Steady-State mode**: Definition Manager handles the full lifecycle. Definition modifications trigger re-appraisal by all relevant planes. Determination: Adaptation (Regulation adjusting Rules/Policies from deviation evidence) produces definition changes that are themselves appraised before activation. The loop is: Deviation evidence → Determination: Adaptation proposes change → plane appraisals validate → Definition Manager persists if coherent.

## Aggregates Endpoints to expose (WIP)

- Create/read/update(status) Principle (includes operations on Quality it is composed of)
- Create/read/update(status) Policy (includes operations on Functions it scopes, and attachment to Principles it operationalizes)
- Create/read/update(status) Rule (includes operations on Functions, Controls, Receptor it is composed of, and (un)attachment to Policies)

## Decision Records

### ADR-001: Appraisal as Meta-PPRC (Option B — Decoupled Status + Genesis Seeds)

**Status**: Accepted (2026-02-16)

**Context**: SIE's Definition Manager governs lifecycle transitions of systemic primitives via `SystemicPrimitiveDefinitionStatus` (DRAFT → PROPOSED → APPROVED → ACTIVE → ...). The question is how norm appraisal checkpoints (NA-PRIN-\*, NA-POL-\*, NA-RULE-\*, NA-CTRL-\*, NA-PPRC-\*) integrate with this lifecycle.

Three options were evaluated:

- **Option A (Integrate)**: Embed appraisal semantics directly into `SystemicPrimitiveDefinitionStatus`. Rejected — status instability with probabilistic checks; mixes lifecycle with evaluation.
- **Option B (Decouple)**: Model checkpoints as native PPRC artifacts; status remains a pure lifecycle state machine. **Selected.**
- **Option C (Hybrid)**: Split status into lifecycle + appraisal sub-states. Rejected — accidental complexity, unclear ownership.

**Decision**: Option B — decouple status from appraisal. Model norm appraisal checkpoints as native PPRC artifacts owned by the SIE genesis system.

**Core constructs:**

| Construct | GSM Primitive | Purpose |
|---|---|---|
| Appraisal Finding | `StateObjectArchetype` (persisted, queryable) | Result of a checkpoint evaluation. Includes `severity`, `checkpointId`, `targetPrimitiveId`, `evaluationMode` (DETERMINISTIC / PROBABILISTIC / UNDECIDABLE), `confidence` \[0.0, 1.0\]. |
| Transition Guard | `Policy` | Constrains lifecycle transitions using `CardinalityPolicyConstraint` over AppraisalFinding state objects. Scoped to the DefinitionManager component. |
| Checkpoint | `Rule` + `Control` | Behavioral definition triggered by persisted-events. Each named checkpoint (NA-\*) is a Rule whose Controls evaluate coherence and produce findings. |

**Key design corrections:**

- **No `ContextVariableReferenceExpression` in Policies.** Policies are declarative state constraints with no receptor context. Per-instance scoping (matching findings to the primitive being transitioned) is handled by Rules/Controls, which have receptor context. The Policy states the normative goal; the Rule enforces it behaviorally.
- **Policy is used with structural constraint types.** Transition guards constrain *what definitions MUST BE* (structural invariants of the PPRC set). The constraint nature (structural) is derived from the constraint type (CardinalityPolicyConstraint) and the Principle's purposeQualifier, not from a Policy subtype. Guard scoping: `scopedFunctions: [DefinitionLifecycleManagement]`.
- **NormAppraisal is a Purpose; each checkpoint is a Function.** The Purpose is *why* (appraise normative coherence); each checkpoint Function is *what* (the specific check activity). Functions serve the Purpose.
- **Findings are `StateObjectArchetype`** (persisted, queryable), not transient events. A `CreationActuationControl` produces AppraisalFinding instances.
- **Seed immutability via ownership.** Genesis seeds are owned by the SIE system. Tenants cannot modify primitives outside their governance authority. No explicit `mutable` flag needed — the existing ownership model handles it.
- **Non-determinism support.** Checks can be deterministic, probabilistic, or undecidable. Determinism lives at the guard (controller/Policy) level; checks (sensor/Rule) can have any evaluation mode. Guard Policies define confidence thresholds for probabilistic check acceptance.

**DSL impact**: None. Existing `CardinalityPolicyConstraint` + `StateObjectSelectorExpression` + `StateObjectArchetypeReferenceExpression` express all guard constraints within the current SIE DSL. No new types, functions, or syntax needed.

**Genesis seed**: `sie-genesis-seed-v1.json` (adjacent to `sie-gsm-v1.puml`). Contains the full SIE system axiomatic definition including all norm appraisal checkpoints instantiated as PPRC, plus supporting purposes, qualities, functions, mechanisms, and infrastructure.

**Risks:**

| ID | Risk | Severity | Mitigation |
|---|---|---|---|
| R1 | Bootstrap complexity — seed PPRC validated outside the lifecycle it governs | Medium | Design-time validation; SIE upgrades are the only path to modify seeds |
| R2 | Cognitive load — two PPRC layers (meta + tenant) | Medium | UI separation; `SIE_*` namespace prefix; platform vs. system governance sections |
| R3 | Performance — every lifecycle transition triggers checkpoint evaluation | Low | Batch evaluation; lazy evaluation on transition request; checkpoint parallelization |
| R4 | Meta-governance escape hatch — seed checkpoint blocks legitimate work | Low | SUSPEND checkpoint Rule; admin force-transition with audit event; SIE platform override |
| R5 | Recursive governance depth — meta-PPRC governs itself | Low | Exactly 2 layers (meta + tenant); meta-layer seeds are axiomatic (bypass lifecycle) |

**Consequences:**

| Aspect | Pro | Con |
|---|---|---|
| Coherence | SIE governs itself with its own PPRC constructs (maximal alignment) | Two PPRC layers increase indirection |
| Extensibility | Tenants add custom checkpoint Rules without SIE code changes | Tenant mistakes in custom Rules require escape hatches |
| Auditability | Full PPRC chain for every governance decision (compliance-grade trail) | — |
| Future-proofing | LLM/probabilistic checks integrate cleanly via `evaluationMode` + `confidence` | Performance cost scales with checkpoint count |
| Non-determinism | Determinism at guard level; checks can be probabilistic | Need confidence thresholds for probabilistic guard evaluation |
| Ownership model | Existing governance authority handles seed immutability | Tenants must understand they cannot modify `SIE_*` seeds |

**Behavioral Executor (architectural note)**: SIE needs a generic Rule/Control runner (Control Sequencer / Behavioral Executor) serving both Operators and Supervisors — the Operation plane's execution substrate. Technology analogs: Temporal.io (durable step-based workflows), Drools (rule engine), OPA/Rego (policy evaluation), Apache Flink (streaming CEP). Practical SIE stack: Temporal for Control sequencing + CEL for expression evaluation.
