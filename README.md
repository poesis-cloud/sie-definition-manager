# SIE Definition Manager

SIE (Systemic Intelligence Engine) is an **AI Context Platform**: it turns "context" into a governed, typed, provenance-backed asset (observations + definitions + evaluations) that can be assembled for AI reasoning, deterministic checks, and remediation decisions.

This module hosts the **Definition plane** conceptual models, primarily the **GSM (Generative System Model)** — SIE's generative/definitional system model.

## GSM — the Generative System Model

GSM is a fundamental departure from classical systemic models such as Beer's Viable System Model (VSM) or von Bertalanffy's General System Theory (GST). Those models are *descriptive*: they provide analytical frameworks for observing and reasoning about systems that already exist. GSM, by contrast, is *generative* (equivalently: *definitional*). Its primitives are not passive descriptions of an observed reality; they are **active definitions** from which system structure, behavior, governance, and viability are *derived and produced*:

- A declared **Directive** generates identity constraints enforced by the governance plane; the regulation plane produces Policies and Rules from them.
- A declared **Policy** constraint automatically derives its own evaluation semantics, closing the homeostatic cycle.
- An **Actuation** derived from a Rule body auto-defines the Actuator and Sensor mechanisms needed to realize and observe the effect.

Classical models *describe* systems; GSM *defines* them. Description tells you what a system is; definition tells the system what it must become — and provides the governance machinery to get there.

## DPQR across layers — the visual grammar

DPQR (Directives, Policies, Qualifiers, Rules) is the **System Definitional Grammar** — the definitional spine that cross-cuts all three layers of a System. The following diagram shows how governance artifacts flow from identity (Constitution) through governance logic (Organization) down to structural realization (Infrastructure):

```
CONSTITUTION (identity — WHY)
+---------+          +--------------------------+
| Purpose |<---------+        Directive         |
|         |  scopes  | Modal x Verb x Qual x P  |
+---------+          +------------+-------------+
. . . . . . . . . . . . . . . . .|. . . . . . . .
ORGANIZATION (governance — HOW)   | operationalizes
                       +----------+----------+
                       |       Policy        |
                       |  guard + predicate  |
                       +---+-------+------+--+
                           |       |      |
            +--------------+       |      +-----------+
      DPR   |         DP-only      |             DPQ  |
      +-----+------+        (no terminal)    +--------+---+
      |    Rule    |       Supervision       | Qualifier  |
      |  Starlark  |        evaluates        |    JSON    |
      +-----+------+        directly         +------------+
      . . . | . . . . auto-derives . . . . . . . . . . .
INFRASTRUCTURE (substrate — WITH WHAT)
      +-----+------+
      | Controller +---> Actuator ---> Sensor
      +------------+   Mechanism, R/E, Channel

Specificity: Directive --> Policy --> {Rule, Qualifier}
Modality:    constraining (DP) --> defining (R, Q)
Velocity:    slow (DP) ---------> fast (R, Q)
```

Three sub-chains fork from the common constraining root (Directive → Policy):

| Chain | Terminal atom | Modality | Governs | Verification |
|-------|---------------|----------|---------|--------------|
| **DPR** | Rule (Starlark) | Imperative | Behavioral logic | Observation-time |
| **DPQ** | Qualifier (JSON) | Imperative | Structural properties | Definition-time |
| **DP-only** | — (none) | — | Graph topology / cardinality | Definition-time |

Together these layers form the system's **generative envelope** — the complete definitional specification from which governance processes derive their runtime behavior.

## Quality as Purpose operationalization effectiveness

The quality level of a system IS the effectiveness of its Purpose operationalization through DPQR. Quality is not an external metric imposed on the system — it is an intrinsic structural property of the DPQR graph itself.

**Why this follows from GSM semantics:**

- **Directive IS a quality attribute.** Its grammar (`Modal × Verb × PurposeQualifier × Purpose`) simultaneously declares the value axis (Purpose) and the viability axis (purposeQualifier). Quality is constitutive identity.
- **Operationalization IS the quality lineage.** The full structural chain `Function ← Mechanism → Rule → Policy → Directive → Purpose` makes quality operationalization a graph property.
- **Quality level IS operationalization effectiveness.** Complete, coherent, adequate DPQR chains = high quality. Gaps, contradictions, unenforced Directives = low quality.

**Quality dimensions** (all derivable from existing constructs):

| Dimension | Question | Source |
|-----------|----------|--------|
| Governance coverage | What fraction of Purpose × purposeQualifier space has DPQR chains? | Operationalization graph |
| Governance depth | What ratio of chains reach terminal atoms (DPR/DPQ) vs. DP-only? | Operationalization completeness |
| Normative integrity | Are DPQR chains contradiction-free? | Norm Appraisal |
| Normative adequacy | Do Policies adequately translate Directives? | Norm Appraisal |
| Operational fidelity | Does observed state deviate from defined Policies? | Form Appraisal |
| Governance velocity | How quickly does governance adapt to such deviation? | S3×S4 homeostat |

**Effective criticality** (weighting function): not all Purposes are equally important. The effective criticality of a Purpose is derived from its Directive distribution: `effectiveCriticality(P) = max(modal)` over all Directives scoping P. A Purpose governed by MUST Directives is de facto critical; one with zero Directives is an ungoverned governance gap.

**No new quality primitives needed.** DPQR already IS the quality grammar. Quality assessment is goverable through meta-Directives (see §12 in `gsm.puml` GsmArchitectureAnalysis).

## Three definitional layers

Every System is defined through three layers:

### Constitution — "What the system IS" (WHY)

The constitutional layer declares the system's identity: its reason for existence and the immovable quality commitments it upholds.

- **Purpose**: the system's reasons for existence — the intended outcomes or objectives. Purposes are the functional anchors to which all governance is traceable through the DPQR chain.
- **Directive**: identity-level normative constraints expressed as structured quality-attribute sentences (`Modal × Verb × PurposeQualifier × Purpose`). The subject is always the owning System. Directives are the normative root of the DPQR chain.

Constitution carries both the *value axis* (Purpose: what the system does) and the *viability axis* (Directive: how well it must do it).

### Organization — "How the system governs itself" (HOW)

The organizational layer defines how the system achieves its constitutional purposes while respecting its directives. Organization is the **logical structure realizing Constitution, by means of its Infrastructure**. It contains:

**Governance norms and behavioral logic (DPQR + relationships):**

- **Policy**: measurable normative envelopes (CEL predicates with tolerance modes) that operationalize Directives into bounded, falsifiable constraints across all governance axes.
- **Rule**: behavioral logic — event-triggered Starlark programs. The atomic, compilable/executable governance logic. Infrastructure primitives (Controller, Actuator, Sensor) are auto-derived from Rule bodies by the Definition Manager.
- **Qualifier**: structural/property definition atom — JSON values within Policy bounds. The atomic, declarative structural set point. Schema-extensible for cross-domain properties.
- **Operationalization**: traced governance lineage path (Directive → Policy → {Rule, Qualifier}). Each instance pins one thread from the many-to-many DPQR web.
- **Relationship**: positional links to other systems, encoding role pairs (via canonical Roles) and governance postures.
- **Actuation** / **Sensing**: auto-derived governance markers for state mutations and their homeostatic verification (closed-loop vs. open-loop actuations).

**Information model (epistemic constructs):**

- **Function**: an activity or process that the system can perform — the logical capability that Mechanisms realize.
- **EventArchetype**: the structure and semantics of events that flow through mechanisms.
- **StateObjectArchetype**: the structure and semantics of observable/mutable state objects.

### Infrastructure — "What it is made of" (WITH WHAT)

Inherited from Component (the base type). The structural substrate — mechanism topology upon which governance operates:

- **Mechanism**: atomic causal unit of behavior (at least one Receptor, one Effector). Subtypes: **Controller** (implements Rules), **Actuator** (executes Actuations), **Sensor** (observes Sensings).
- **Receptor** / **Effector**: event I/O channels bound to EventArchetypes.
- **Interaction**: atomic causal coupling (Effector → Receptor).
- **Channel**: declared exchange medium carrying Interactions.
- **Interface**: named I/O surface — a curated selection of Receptors/Effectors grouped into a coherent, addressable contact point.

A System IS-A Component with reflexive capacity. Components have first-order dynamics only (process inputs, produce outputs). Systems add second-order dynamics (self-governance through Constitution + Organization).

## Primitives at a glance

| Layer | Primitive | Role |
|-------|-----------|------|
| **Meta** | Schema | Defines property vocabulary for extensible primitives |
| **Meta** | SystemicPrimitiveDefinition | Abstract base — carries `id` + `definitionStatus` lifecycle |
| **Constitution** | Purpose | Reason for existence — value axis anchor |
| **Constitution** | Directive | Identity-level quality constraint (Modal × Verb × Qual × Purpose) |
| **Organization** | Policy | Measurable boundary condition (CEL guard + predicate + tolerance) |
| **Organization** | Rule | Behavioral logic atom (event-triggered Starlark body) |
| **Organization** | Qualifier | Structural/property definition atom (JSON value within Policy bounds) |
| **Organization** | Operationalization | Governance lineage trace (D → P → R or Q) |
| **Organization** | Function | Named capability that Mechanisms realize |
| **Organization** | Relationship | Positional link between Systems (Role × Role) |
| **Organization** | Role | Relational label for Systems in Relationships |
| **Organization** | Actuation | State mutation marker (auto-derived from Rule body) |
| **Organization** | Sensing | Homeostatic verification marker (auto-derived, closed-loop) |
| **Organization** | EventArchetype | Event structure + semantics (Schema-bound) |
| **Organization** | StateObjectArchetype | State object structure + semantics (Schema-bound) |
| **Infrastructure** | Component | Structural organ — mechanisms + I/O topology |
| **Infrastructure** | Mechanism | Atomic causal unit (Receptors → processing → Effectors) |
| **Infrastructure** | Controller | Mechanism subtype — implements Rules |
| **Infrastructure** | Actuator | Mechanism subtype — executes Actuations |
| **Infrastructure** | Sensor | Mechanism subtype — observes Sensings |
| **Infrastructure** | Receptor / Effector | Event input / output channels |
| **Infrastructure** | Interaction | Causal coupling (Effector → Receptor) |
| **Infrastructure** | Channel | Exchange medium carrying Interactions |
| **Infrastructure** | Interface | Named I/O surface (curated R/E group) |

Every primitive inherits `SystemicPrimitiveDefinitionStatus` (DRAFT → PROPOSED → APPROVED → ACTIVE → SUSPENDED / DEPRECATED → RETIRED). Domain-extensible primitives (EventArchetype, StateObjectArchetype, Qualifier) additionally carry a Schema for structural extensibility.

## Six governance planes

SIE is organized into six canonical planes grouped into two layers:

| Layer | Plane | Function | DPQR role |
|-------|-------|----------|-----------|
| **Reflexive** | Governance | Defines and enforces Directives | Governor (S5) |
| **Reflexive** | Regulation | Interprets Directives → Policies + Rules | Regulator (S4) |
| **Reflexive** | Supervision | Enforces Policies and Rules (appraisal) | Supervisor (S3) |
| **Reflexive** | Coordination | Stabilizes concurrent governance loops | Coordinator (S2) |
| **Ontic** | Operation | Executes Rules in the state plane | Operator (S1) |
| **Ontic** | State | Ground truth — external systems | — |

GRS&CO labels (Governor, Regulator, Supervisor, Coordinator, Operator) name **SIE's own governance functions** — one per plane. They are SIE's own Systems, not Components composed by tenant Systems. DPQR is the **System's DNA**: SIE deploys GRS&CO to operate on each System's DPQR.

## GSM ↔ DSM (Definition–Description duality)

GSM is one half of SIE's core duality:

- **GSM** (this module) defines *what the system must become*: directives, policies, rules, qualifiers, mechanisms, and their governance relationships. GSM primitives are active definitions from which structure, behavior, and viability are derived. GSM has **definitional primacy**.
- **DSM** (`../sie-description-manager/`) captures *what the system currently is*: state descriptions, signals, measurements, comparisons, deviations, and assessments. DSM feeds evidence into GSM's generative machinery. DSM has **evidential primacy**.

In the Six Planes model:

| Layer | Plane | Model | Cybernetic role |
|-------|-------|-------|-----------------|
| Reflexive | **Governance + Regulation** | GSM | Controller semantics |
| Reflexive | **Supervision** | DSM | Sensor + comparator semantics |
| Reflexive | **Coordination** | — | Stabilizes pacing between GSM and DSM |
| Ontic | **Operation** | — | Actuator — realizes GSM definitions |
| Ontic | **State** | — | Ground truth — observed by DSM |

Closed-loop flow: **State → DSM (observe) → GSM (define/adjust) → Operation (actuate) → State**.

Definition without description is dogma; description without definition is noise.

### Structural and behavioral description (completeness–complexity tradeoff)

GSM deliberately stops at the **structural properties layer** — the lowest definitional layer. Structure defines the arrangement and constraints of behavior (Rules, Mechanisms, topology), but behavior itself emerges in the State plane and is *observed*, not *defined*.

DSM mirrors this structural layer and extends observation into behavior:

- **Structural description** captures system topology at a point in time (components, interfaces, mechanisms, connections). It is cheap, stable, and immediately available — the efficient approximation.
- **Behavioral description** captures state transitions, event sequences, and I/O patterns over time. It is the more complete epistemic form: given perfect behavioral observation, structure can be inferred.

This creates a natural priority gradient for DSM:

1. **Discovery mode**: structural descriptions dominate (cheapest to acquire, sufficient to scaffold governance definitions via Emergence).
2. **Steady-state mode**: behavioral descriptions deepen (needed for dynamic Form Appraisal — does the system *behave* as defined?).
3. **At convergence**: behavioral observation approaches completeness, and structural assumptions become empirically grounded rather than inferred.

GSM and DSM are therefore coupled at the structural layer — this is their shared correlation surface — but DSM's descriptive reach extends beyond structure into behavior, where governance definitions are ultimately validated or falsified.

## Where to start

- GSM model: `definition/gsm.puml`
- DSM model (companion): `../sie-description-manager/definition/dsm.puml`
- Supervision information model: `../sie-supervision/definition/sie-supervision-information-model.puml`
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
identifier in its CEL `predicate`. This closes a machine-verifiable governance
chain:

```
Directive (qualifierArchetypeId → Archetype A)
    └── Norm (predicate root identifier → same Archetype A)
```

The definition-manager validates this link: a Norm whose predicate references
an Archetype that differs from its Directive's `qualifierArchetypeId` is
rejected.

### Two governance patterns

The same Directive → Norm machinery supports two distinct evaluation modes,
depending on what the qualifier Archetype types:

| Pattern | qualifierArchetypeId types… | Norm evaluates… | Trigger |
|---------|----------------------------|-----------------|---------|
| **Definition-time** | ElementDefinition Ascriptions (Structure, Mechanism, Interface, Interaction) | Ascription properties when a definition is authored or modified | Definition change event |
| **Runtime observation** | Artifact instances from the Description plane | Observed metrics / measurements at runtime | Observation event |

### Example 1 — Structure governance: SecurityProperties

**Archetype**: `SecurityProperties` (extends StructureArchetype).
Schema defines: `encryptionLevel`, `authenticationProtocol`, `dataClassification`, …

**Directive** (Ascription payload):

```json
{
  "structureId":        "platform-governance",
  "modal":              "MUST",
  "verb":               "ENSURE",
  "qualifierArchetypeId": "SecurityProperties",
  "purposeStructureId": "order-processing"
}
```

*Reads: "platform-governance MUST ENSURE SecurityProperties OF order-processing."*

**Norms** operationalizing this Directive:

| guard | predicate | toleranceMode | meaning |
|-------|-----------|---------------|---------|
| `true` | `SecurityProperties.encryptionLevel >= "AES-256"` | INSTANTANEOUS | All order-processing Structures must use AES-256+ encryption |
| `true` | `SecurityProperties.authenticationProtocol == "mTLS"` | INSTANTANEOUS | Must use mTLS between services |

*Pattern: **definition-time**. Norms evaluate Structure Ascription properties when the definition is authored.*

### Example 2 — Mechanism governance: ComplianceProperties

**Archetype**: `ComplianceProperties` (extends MechanismArchetype).
Schema defines: `framework`, `validationCoverage`, `lastAuditDate`, …

**Directive**:

```json
{
  "structureId":        "compliance-board",
  "modal":              "MUST",
  "verb":               "ENSURE",
  "qualifierArchetypeId": "ComplianceProperties",
  "purposeStructureId": "payment-validation"
}
```

*Reads: "compliance-board MUST ENSURE ComplianceProperties OF payment-validation."*

**Norms**:

| guard | predicate | toleranceMode | meaning |
|-------|-----------|---------------|---------|
| `ComplianceProperties.framework == "PCI-DSS"` | `ComplianceProperties.validationCoverage >= 0.95` | INSTANTANEOUS | PCI-DSS mechanisms must have ≥ 95 % validation coverage |
| `ComplianceProperties.framework == "SOC2"` | `ComplianceProperties.validationCoverage >= 0.80` | INSTANTANEOUS | SOC2 mechanisms need ≥ 80 % coverage |

*Pattern: **definition-time**. The guard filters which Mechanisms the Norm applies to (only those in the matching compliance framework). Guards are how Norms achieve conditional governance without branching logic.*

### Example 3 — Interface governance: APISecurityProperties

**Archetype**: `APISecurityProperties` (extends InterfaceArchetype).
Schema defines: `exposure`, `tlsVersion`, `rateLimitRps`, `corsPolicy`, …

**Directive**:

```json
{
  "structureId":        "security-team",
  "modal":              "MUST",
  "verb":               "ENSURE",
  "qualifierArchetypeId": "APISecurityProperties",
  "purposeStructureId": "customer-portal"
}
```

*Reads: "security-team MUST ENSURE APISecurityProperties OF customer-portal."*

**Norms**:

| guard | predicate | toleranceMode | meaning |
|-------|-----------|---------------|---------|
| `APISecurityProperties.exposure == "public"` | `APISecurityProperties.tlsVersion >= "1.3"` | INSTANTANEOUS | Public interfaces must use TLS 1.3+ |
| `APISecurityProperties.exposure == "public"` | `APISecurityProperties.rateLimitRps > 0` | INSTANTANEOUS | Public interfaces must have rate limiting |
| `true` | `APISecurityProperties.corsPolicy != ""` | INSTANTANEOUS | All interfaces must declare a CORS policy |

*Pattern: **definition-time**. Guards filter by exposure level — some Norms apply only to public-facing Interfaces, others apply universally.*

### Example 4 — Interaction governance: InteractionReliabilityProperties

**Archetype**: `InteractionReliabilityProperties` (extends InteractionArchetype).
Schema defines: `encryptionInTransit`, `maxPayloadBytes`, `retryPolicy`, …

**Directive**:

```json
{
  "structureId":        "infrastructure-team",
  "modal":              "MUST",
  "verb":               "ENSURE",
  "qualifierArchetypeId": "InteractionReliabilityProperties",
  "purposeStructureId": "payment-notification-flow"
}
```

*Reads: "infrastructure-team MUST ENSURE InteractionReliabilityProperties OF payment-notification-flow."*

**Norms**:

| guard | predicate | toleranceMode | meaning |
|-------|-----------|---------------|---------|
| `true` | `InteractionReliabilityProperties.encryptionInTransit == true` | INSTANTANEOUS | All interactions must be encrypted in transit |
| `true` | `InteractionReliabilityProperties.maxPayloadBytes <= 1048576` | INSTANTANEOUS | Payload must not exceed 1 MB |
| `true` | `InteractionReliabilityProperties.retryPolicy != "none"` | INSTANTANEOUS | A retry policy must be configured |

*Pattern: **definition-time**. Norms constrain Interaction Ascription properties that govern how causal couplings between Mechanisms behave.*

### Example 5 — Artifact governance (runtime observation): LatencyMetrics

**Archetype**: `LatencyMetrics` (extends ArtifactArchetype).
Schema defines: `environment`, `endpoint`, `p95ResponseMs`, `p99ResponseMs`, `errorRate`, …

**Directive**:

```json
{
  "structureId":        "platform-governance",
  "modal":              "SHOULD",
  "verb":               "OPTIMIZE",
  "qualifierArchetypeId": "LatencyMetrics",
  "purposeStructureId": "checkout-service"
}
```

*Reads: "platform-governance SHOULD OPTIMIZE LatencyMetrics OF checkout-service."*

**Norms**:

| guard | predicate | toleranceMode | temporalWindow | temporalAggregation | sustainedThreshold | meaning |
|-------|-----------|---------------|----------------|---------------------|--------------------|---------|
| `LatencyMetrics.environment == "production"` | `LatencyMetrics.p95ResponseMs <= 500` | AGGREGATED | PT5M | P95 | — | P95 latency ≤ 500 ms over 5-minute windows (production only) |
| `LatencyMetrics.environment == "production"` | `LatencyMetrics.p99ResponseMs <= 2000` | SUSTAINED | PT15M | P99 | 0.95 | P99 latency ≤ 2 s must hold for 95 % of each 15-minute window |
| `LatencyMetrics.environment == "production"` | `LatencyMetrics.errorRate < 0.01` | SUSTAINED | PT10M | AVG | 0.99 | Average error rate < 1 % must hold 99 % of each 10-minute window |

*Pattern: **runtime observation**. Unlike examples 1–4, `LatencyMetrics` is an Artifact type (not a structural type). Instances are produced by the Description plane as runtime observations. Same Directive → Norm machinery, different temporal semantics: observation Norms typically use AGGREGATED or SUSTAINED tolerance modes with temporal windows.*

## Definition-time appraisal (plane integration)

The Definition Manager is not a passive store. When definitions are created, modified, or deleted, the Definition Manager triggers **Norm Appraisal and Form Appraisal by the relevant plane components**:

| Definition change | Appraising plane | What is checked |
|-------------------|------------------|-----------------|
| Directive created/modified | **Governance** (`../sie-governance/`) | Constitutive Norm Appraisal: Directive ↔ Quality, scope validity, verb conflicts, governance plane coverage |
| Policy created/modified | **Regulation** (`../sie-regulation/`) | Constraint Norm Appraisal: direction vs. Directive verb, metric→Quality path, scope containment, Policy↔Policy conflicts |
| Rule created/modified | **Regulation** (`../sie-regulation/`) | Rule→Policy coverage, CEL/Starlark validity |
| Structural changes (Component, Interface, Channel) | **Regulation** (`../sie-regulation/`) | Structural Form Appraisal (cardinality and dependency Policy predicates) |
| Any definition activated | **Supervision** (`../sie-supervision/`) | Re-appraise (Form Appraisal) existing observations against the new/updated definition |
| Cross-system definition changes | **Coordination** (`../sie-coordination/`) | Inter-system coherence, collision detection, oscillation dampening (Stabilization) |

This means each `PROPOSED → APPROVED → ACTIVE` transition in `SystemicPrimitiveDefinitionStatus` passes through the relevant plane's appraisal rules before advancing. The Definition Manager orchestrates these checks; the planes own the rules.

### Norm Appraisal at definition time

The Definition Manager is the primary trigger for **Norm Appraisal** — the appraisal of the form of norms (definition ↔ definition, intra-normative). Every definition change passes through Norm Appraisal before the definition can advance in its lifecycle. The three coherence dimensions checked are:

| Dimension | What it checks | Definition Manager triggers |
|-----------|----------------|----------------------------|
| **Consistency** | No contradictions between norms | Directive verb conflict detection (NA-DIR-01); Policy↔Policy constraint conflicts (NA-POL-04) |
| **Completeness** | No gaps within declared normative scope | DPR chain traversability (NA-DPR-01); Policy operationalization coverage (NA-POL-08); Rule→Policy minimum (NA-RULE-01) |
| **Adequacy** | Norms fit for declared purpose | Policy→Directive direction coherence (NA-POL-01); metric→purposeQualifier path tracing (NA-POL-02, SUSPENDED); scope containment (NA-POL-03); Directive applicability guard validation (NA-DIR-02); Policy→Purpose grounding (NA-POL-05); Policy→purposeQualifier path tracing (NA-POL-06, SUSPENDED); Policy→Component grounding (NA-POL-07); Rule→Policy scope containment (NA-RULE-02); Rule behavioral grounding (NA-RULE-04, NA-RULE-05) |

Norm Appraisal is executed by the **Governance** and **Regulation** planes. The Definition Manager sends definition-change events; those planes evaluate and return appraisal findings. Findings at `ERROR` severity block lifecycle transitions; `WARNING` severity allows advancement but flags the finding for review.

### Form Appraisal on definition activation

The Definition Manager triggers **Form Appraisal** — the appraisal of the form of reality (definition ↔ reality, trans-normative) — when a definition transitions to `ACTIVE`. At that point, existing state-plane observations (held by the Description Manager / DSM) must be re-evaluated against the new or updated definition. The three coherence dimensions checked are:

| Dimension | What it checks | Definition Manager triggers |
|-----------|----------------|----------------------------|
| **Fidelity** | Observed behavior matches definitions | Mechanism definition ↔ observed behavior (FA-MECH-01); Policy constraints ↔ measured metrics (FA-POL-01); Actuation feedback loop verification (FA-ACT-01) |
| **Coverage** | Observed state has corresponding definitions | StateObjectArchetype ↔ observed state (FA-STATE-01); detect state objects or observed mechanisms lacking GSM definitions (input to Emergence) |
| **Convergence** | Formation stabilizes toward the norm | Normative envelope convergence tracking (FA-CONV-01); track whether repeated appraisal cycles show decreasing deviation between definitions and reality |

Form Appraisal is executed by the **Supervision** plane. On definition activation, the Definition Manager publishes a `DefinitionActivatedEvent`; the Supervision plane re-appraises all relevant observations against the activated definition and produces Form Appraisal findings. Structural Form Appraisal (checking structural Policy constraints like cardinality and dependency) is also triggered by the Regulation plane when structural definitions change.

## Decision Records

### ADR-001: Appraisal as Meta-DPR (Option B — Decoupled Status + Genesis Seeds)

**Status**: Accepted (2026-02-16)

**Context**: SIE's Definition Manager governs lifecycle transitions of systemic primitives via `SystemicPrimitiveDefinitionStatus` (DRAFT → PROPOSED → APPROVED → ACTIVE → ...). The question is how norm appraisal checkpoints (NA-DIR-\*, NA-POL-\*, NA-RULE-\*, NA-DPR-\*) integrate with this lifecycle.

Three options were evaluated:

- **Option A (Integrate)**: Embed appraisal semantics directly into `SystemicPrimitiveDefinitionStatus`. Rejected — status instability with probabilistic checks; mixes lifecycle with evaluation.
- **Option B (Decouple)**: Model checkpoints as native DPR artifacts; status remains a pure lifecycle state machine. **Selected.**
- **Option C (Hybrid)**: Split status into lifecycle + appraisal sub-states. Rejected — accidental complexity, unclear ownership.

**Decision**: Option B — decouple status from appraisal. Model norm appraisal checkpoints as native DPR artifacts owned by the SIE genesis system.

**Core constructs:**

| Construct | GSM Primitive | Purpose |
|---|---|---|
| Appraisal Finding | `StateObjectArchetype` (persisted, queryable) | Result of a checkpoint evaluation. Includes `severity`, `checkpointId`, `targetPrimitiveId`, `evaluationMode` (DETERMINISTIC / PROBABILISTIC / UNDECIDABLE), `confidence` \[0.0, 1.0\]. |
| Transition Guard | `Policy` | Constrains lifecycle transitions using cardinality predicates over AppraisalFinding state objects. Scoped to the DefinitionManager component. |
| Checkpoint | `Rule` | Behavioral definition triggered by persisted-events. Each named checkpoint (NA-\*) is a Rule that evaluates coherence and produces findings. |

**Key design corrections:**

- **No `ContextVariableReferenceExpression` in Policies.** Policies are declarative state constraints with no receptor context. Per-instance scoping (matching findings to the primitive being transitioned) is handled by Rules, which have receptor context. The Policy states the normative goal; the Rule enforces it behaviorally.
- **Policy is used with structural constraint predicates.** Transition guards constrain *what definitions MUST BE* (structural invariants of the DPR set). The constraint nature (structural) is derived from the predicate target (cardinality via `size()`, dependencies via structural primitives) and the Directive's purposeQualifier, not from a Policy subtype. Guard scoping: `scopedFunctions: [DefinitionLifecycleManagement]`.
- **NormAppraisal is a Purpose; each checkpoint is a Function.** The Purpose is *why* (appraise normative coherence); each checkpoint Function is *what* (the specific check activity). Functions serve the Purpose.
- **Findings are `StateObjectArchetype`** (persisted, queryable), not transient events. Rule execution produces AppraisalFinding instances.
- **Seed immutability via ownership.** Genesis seeds are owned by the SIE system. Tenants cannot modify primitives outside their governance authority. No explicit `mutable` flag needed — the existing ownership model handles it.
- **Non-determinism support.** Checks can be deterministic, probabilistic, or undecidable. Determinism lives at the guard (controller/Policy) level; checks (sensor/Rule) can have any evaluation mode. Guard Policies define confidence thresholds for probabilistic check acceptance.

**DSL impact**: None. Existing Policy guard/predicate CEL expressions and archetype-scoped selectors express all guard constraints. No new types, functions, or syntax needed.

**Genesis seed**: `sie-genesis-seed.json` (adjacent to `gsm.puml`). Contains the full SIE system axiomatic definition including all norm appraisal checkpoints instantiated as DPR, plus supporting purposes, qualities, functions, mechanisms, and infrastructure.

**Risks:**

| ID | Risk | Severity | Mitigation |
|---|---|---|---|
| R1 | Bootstrap complexity — seed DPR validated outside the lifecycle it governs | Medium | Design-time validation; SIE upgrades are the only path to modify seeds |
| R2 | Cognitive load — two DPR layers (meta + tenant) | Medium | UI separation; `SIE_*` namespace prefix; platform vs. system governance sections |
| R3 | Performance — every lifecycle transition triggers checkpoint evaluation | Low | Batch evaluation; lazy evaluation on transition request; checkpoint parallelization |
| R4 | Meta-governance escape hatch — seed checkpoint blocks legitimate work | Low | SUSPEND checkpoint Rule; admin force-transition with audit event; SIE platform override |
| R5 | Recursive governance depth — meta-DPR governs itself | Low | Exactly 2 layers (meta + tenant); meta-layer seeds are axiomatic (bypass lifecycle) |

**Consequences:**

| Aspect | Pro | Con |
|---|---|---|
| Coherence | SIE governs itself with its own DPR constructs (maximal alignment) | Two DPR layers increase indirection |
| Extensibility | Tenants add custom checkpoint Rules without SIE code changes | Tenant mistakes in custom Rules require escape hatches |
| Auditability | Full DPR chain for every governance decision (compliance-grade trail) | — |
| Future-proofing | LLM/probabilistic checks integrate cleanly via `evaluationMode` + `confidence` | Performance cost scales with checkpoint count |
| Non-determinism | Determinism at guard level; checks can be probabilistic | Need confidence thresholds for probabilistic guard evaluation |
| Ownership model | Existing governance authority handles seed immutability | Tenants must understand they cannot modify `SIE_*` seeds |

**Behavioral Executor (architectural note)**: SIE needs a generic Rule executor (Behavioral Executor) serving both Operators and Supervisors — the Operation plane's execution substrate. Technology analogs: Temporal.io (durable step-based workflows), Drools (rule engine), OPA/Rego (policy evaluation), Apache Flink (streaming CEP). Practical SIE stack: Temporal for Rule sequencing + CEL for expression evaluation.
