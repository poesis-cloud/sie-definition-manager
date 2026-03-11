# Analysis: Archetype allOf extensibility and compilation pipeline design

Status: **design analysis** — not yet decided.

This document evaluates the feasibility and design consequences of tenant/vendor
Archetype schema extensibility via JSON Schema `allOf`, and the compilation
pipeline model (statement → Receptor → Mechanism chain → Effector → compilation).

---

## Request model under analysis

```
POST /ascriptions
{
  "compilationEffectorId":  UUID,       // pipeline exit — types the output
  "statementReceptorId":    UUID?,      // pipeline entry — types the input
  "statement":              JsonNode    // authored payload
}
```

- DM resolves `compilationEffectorId` → Effector → Effector.outputArchetype →
  **output contract** (types resulting compilation).
- DM resolves `statementReceptorId` → Receptor → Receptor.inputArchetype →
  **input contract** (validates statement).
- The Mechanism chain between Receptor and Effector constitutes the **compilation
  pipeline**: a governed Structure whose purpose is to compile Ascriptions.
- Pipeline Structures, Mechanisms, Interactions, Interfaces, Effectors, and
  Receptors are themselves GSM Ascriptions — governed, typed, tenant-extensible.

Bootstrap: axiomatic seed entities (Primal Effector, Primal Receptor) use
self-reference cycles analogous to the Archetype seed. Both columns are NOT NULL
on all Ascriptions — no null-based bootstrap escape.

---

## Problem 1: `additionalProperties` kills allOf

If a GSM base Archetype schema declares `additionalProperties: false`, any
allOf extension that adds properties is impossible — the base branch rejects
the extension's properties, and `allOf` requires ALL branches to pass.

**Root cause**: `additionalProperties` does not look across allOf boundaries.

**Solution**: JSON Schema draft 2020-12 `unevaluatedProperties`. It works
across allOf branches — extension properties are "evaluated" by their own
branch and not flagged as unevaluated by the base.

**GSM architectural rule**: ALL GSM base Archetype schemas MUST use
`unevaluatedProperties: false`, NEVER `additionalProperties: false`. DM MUST
reject any base Archetype schema that uses `additionalProperties: false`.

**Severity**: Low — enforcement rule. Viable with strict base schema discipline.

---

## Problem 2: Which schema validates the statement?

When a tenant sends a statement containing extension fields (e.g., `costCenter`
from `MyStructureArchetype` extending `StructureArchetype`), DM must know which
*specific* allOf-variant Archetype to validate against.

The pipeline Effector is typed by the base Archetype (`StructureArchetype`)
— it knows nothing about `costCenter`. Three options evaluated:

### Option A — Archetype reference inside the statement

```json
{
  "statement": {
    "archetype": "MyStructureArchetype",
    "purpose": "order-processing",
    "costCenter": "CC-4200"
  }
}
```

DM extracts `archetype` from the unvalidated statement, resolves it, then
validates the full statement.

**Problems**:

- Chicken-and-egg: must read the statement to find the schema that validates
  the statement.
- Reserves `archetype` as a field name across all statement schemas.
- Mixes envelope concerns (typing) with payload concerns (content).

**Verdict**: Viable but semantically unclean.

### Option B — Archetype reference in the request envelope

```json
{
  "compilationEffectorId": "...",
  "archetypeId": "<MyStructureArchetype.id>",
  "statement": { "purpose": "order-processing", "costCenter": "CC-4200" }
}
```

DM resolves `archetypeId`, validates allOf chain, validates statement.

**Problems**:

- Three references in the request. Redundancy: `compilationEffectorId` already
  implies the base Archetype. `archetypeId` specifies the allOf variant. DM
  must validate consistency.
- Bypasses the Receptor role: the Receptor's Archetype is the input contract,
  but here a separate `archetypeId` overrides what the Receptor expects. The
  Receptor cannot autonomously identify/validate what it receives — the envelope
  tells it. This undermines Receptor semantics.

**Verdict**: Viable engineering shortcut, but conceptually breaks Receptor as
the autonomous input gatekeeper.

### Option C — Receptor/Effector per tenant Archetype (recommended)

The tenant creates (or DM auto-derives) Receptor and Effector instances typed
by the specific tenant Archetype:

```
MyStructureArchetype (extends StructureArchetype via allOf)
  → MyStructureReceptor   (Receptor.inputArchetype = MyStructureArchetype)
  → MyStructureEffector    (Effector.outputArchetype = MyStructureArchetype)
```

Client sends:

```json
{
  "compilationEffectorId": "<MyStructureEffector.id>",
  "statementReceptorId": "<MyStructureReceptor.id>",
  "statement": { "purpose": "order-processing", "costCenter": "CC-4200" }
}
```

DM resolves `MyStructureReceptor` → `MyStructureArchetype` → validates
statement. DM resolves `MyStructureEffector` → `MyStructureArchetype` →
types compilation.

**Advantages**:

- Receptor is the autonomous input gatekeeper: its Archetype IS the full
  input contract, including extension fields. No bypass, no envelope override.
- Effector is the autonomous output typist: its Archetype IS the full
  output contract.
- No `archetypeId` in the request — typing is fully structural.
- Mechanism handles only data identified by its Receptor's Archetype schema
  — a Mechanism never receives untyped/unknown fields.
- Discovery is structural: "what can I create?" = "what Effectors does this
  pipeline Interface expose?" Each Effector's Archetype tells the client
  exactly what output type it produces, and the connected Receptor tells
  what input it expects.

**Questions**:

- Must DM auto-derive Receptors/Effectors per tenant Archetype?
  **No.** The tenant/vendor can create them explicitly as Ascriptions
  through the normal API. Auto-derivation is a convenience, not a
  requirement. What IS required:
  - The new Receptor/Effector must reference the tenant's Archetype.
  - The new Receptor/Effector must be wired (via Interactions) to the
    compilation Mechanism(s) that handle this Archetype family.
  - The pipeline Interface must expose these new endpoints.
- What about custom Mechanisms?
  The tenant/vendor may also create custom Mechanisms for tenant-specific
  compilation logic (Layer 2). The full pipeline authoring is:
  1. Create tenant Archetype (allOf extending base).
  2. Create Receptor typed by tenant Archetype.
  3. Create Effector typed by tenant Archetype.
  4. Optionally create Mechanism(s) for custom compilation logic.
  5. Create Interactions wiring Receptor → Mechanism(s) → Effector.
  6. Add to pipeline Interface.
  All of these are Ascriptions — governed, reviewed, activatable through the
  standard lifecycle.

**Verdict**: This is the correct design. Receptor and Effector carry the
specific Archetype — typing is structural, not envelope-overridden. The
Mechanism is autonomous: it handles data fully described by its own Receptor's
Archetype.

---

## Problem 3: Base vs extension field compilation

Statement validated. DM must compile it. Base fields (e.g., `purpose`) have
Layer 0 hardcoded logic. Extension fields (e.g., `costCenter`) do not.

### Under Option C

The compilation Mechanism receives data typed by the Receptor's Archetype.
Two scenarios:

**Scenario 3a — existing base Mechanism handles extension Archetype:**

The tenant creates `MyStructureReceptor` (typed by `MyStructureArchetype`) but
wires it to the EXISTING `StructureCompilationMechanism`. This Mechanism
understands `StructureArchetype` fields. It does NOT know `costCenter`.

Behavior: Mechanism compiles base fields (Layer 0 logic). Extension fields
pass through — copied from statement to compilation without transformation.
The Mechanism's Starlark rule processes properties it recognizes; unrecognized
properties (from allOf extensions) are preserved verbatim.

This is the Layer 1 contract: **structural extension (new typed fields) without
behavioral extension (new compilation logic).**

Pass-through is safe: the JSON Schema validates the shape. Semantic validation
(e.g., "CC-4200 exists in cost center registry") requires a Layer 2 Mechanism.

**Scenario 3b — tenant Mechanism handles extension Archetype:**

The tenant creates a custom `CostCenterEnrichmentMechanism` (Layer 2) with a
Receptor typed by `MyStructureArchetype`. This Mechanism's rule validates
`costCenter` against an external source, enriches/transforms it. The pipeline
becomes:

```
MyStructureReceptor → StructureCompilationMechanism → CostCenterEnrichmentMechanism → MyStructureEffector
```

The base Mechanism handles base fields; the tenant Mechanism handles extension
fields. Pipeline composition.

### The schema diffing question

How does the base Mechanism know which fields are "base" vs "extension"?

Under Option C, it doesn't need to. The base Mechanism handles what its own
rule handles (base fields) and passes through everything else. The allOf chain
is the *Archetype author's* concern, not the *Mechanism's* concern. The
Mechanism sees a JSON payload; it processes the fields it knows; the rest
survives.

This simplifies Problem 3 significantly vs Option B, where DM itself had to
diff the allOf chain to determine base vs extension.

**Severity under Option C**: Low. Pass-through is the default; schema diffing
is unnecessary for Layer 0/1.

---

## Problem 4: Depth-N allOf chains

```
StructureArchetype (GSM base, sealed)
  └── ComplianceStructureArchetype (vendor)
       └── GDPRStructureArchetype (tenant)
```

### Under Option C

Each level in the chain has its own Receptor/Effector:

- `StructureReceptor` / `StructureEffector` (Layer 0, base)
- `ComplianceStructureReceptor` / `ComplianceStructureEffector` (vendor, Layer 1-2)
- `GDPRStructureReceptor` / `GDPRStructureEffector` (tenant, Layer 1-2)

Each can be wired to different pipeline variants:

```
GDPRStructureReceptor → StructureCompilationMechanism → ComplianceEnrichmentMechanism → GDPRStructureEffector
```

Pipeline ordering is explicit: Mechanism chain topology determines execution
order. No DM-internal ordering heuristic needed (compare: under Option B, DM
had to somehow determine "apply Layer 0, then vendor Mechanism, then tenant
Mechanism" based on the allOf depth — which is implicit).

Under Option C, the wiring IS the ordering. The tenant/vendor creates
Interactions that define the execution sequence. The topology of the pipeline
Structure is the explicit specification of compilation order.

**Severity under Option C**: Low. Wiring is explicit, structural, governed.

---

## Problem 5: Schema evolution with allOf

Vendor updates `ComplianceStructureArchetype` to add a required field
(`auditFrequency`). Existing `GDPRStructureArchetype` Ascriptions were compiled
without it.

### Under Option C

Same challenge regardless of option — this is a schema evolution problem, not
a pipeline routing problem. The solution is:

1. **Statement preservation**: the original statement is persisted. DM can
   recompile against the updated Archetype chain.
2. **Recompilation**: DM re-runs the pipeline on the preserved statement.
   If the statement lacks `auditFrequency`, recompilation fails — DM flags
   these Ascriptions as requiring statement amendment.
3. **Archetype versioning**: Ascriptions pin to an Archetype Ascription version
   (Archetype IS an Ascription with version + status). Existing Ascriptions
   reference Archetype v1; the new version is Archetype v2. Migration is
   explicit.

Note: the Receptor is versioned too. When `ComplianceStructureArchetype` v2 is
created, the vendor creates a new `ComplianceStructureReceptor` Ascription
(or updates via new Ascription version) whose Archetype reference points to v2.
The pipeline evolution is structural — new Receptor version, new wiring if
needed, new Interface exposure.

**Severity under Option C**: Same as other options (Medium-High). The pipeline
model doesn't solve schema evolution — statement preservation does. But
Option C makes the evolution path clearer: update the Archetype, update the
Receptor, rewire if needed. All visible as Ascription versions.

---

## Problem 6: Cross-cutting allOf (facet Archetypes) and base schema question

### The cross-cutting concern

GSM allows Directives to reference "facet" Archetypes — intermediate
Archetypes that multiple leaf Archetypes extend:

```
DataProtectionArchetype (facet)
├── allOf into DataProtectionStructureArchetype (extends StructureArchetype + DataProtectionArchetype)
├── allOf into DataProtectionMechanismArchetype (extends MechanismArchetype + DataProtectionArchetype)
└── allOf into DataProtectionInterfaceArchetype (extends InterfaceArchetype + DataProtectionArchetype)
```

A single Directive references `DataProtectionArchetype` as qualifier. Norms
reference its properties in CEL predicates. ALL Ascriptions whose Archetype
chain includes this facet are governed.

### Should facet Archetypes have a base schema other than Archetype itself?

**Current GSM rule**: "allOf paths converge to the same GSM base." The GSM base
archetypes are: `StructureArchetype`, `MechanismArchetype`,
`InteractionArchetype`, `InterfaceArchetype`, `Archetype`,
`DirectiveArchetype`, `NormArchetype`, `EffectorArchetype`,
`ReceptorArchetype`.

**The question**: Where does `DataProtectionArchetype` root?

It does NOT root at `StructureArchetype` — it can be applied to Structures,
Mechanisms, and Interfaces. It does NOT root at `MechanismArchetype` either.

**Analysis**: facet Archetypes are a **different kind of Archetype** than
structural-subtype Archetypes. There are two extension patterns:

**Pattern 1 — Structural subtyping** (vertical: refining a single base):

```
StructureArchetype  →(allOf)→  ComplianceStructureArchetype  →(allOf)→  GDPRStructureArchetype
```

Linear chain. Exactly one GSM base. Adds domain-specific properties to a
specific structural kind.

**Pattern 2 — Cross-cutting faceting** (horizontal: adding a concern across bases):

```
DataProtectionArchetype                  ← facet (not rooted at any structural base)
  └── allOf into DataProtectionStructureArchetype (base: StructureArchetype)
  └── allOf into DataProtectionMechanismArchetype (base: MechanismArchetype)
```

The facet defines properties (`dataClassification`, `retentionPolicy`, ...).
Leaf Archetypes combine the facet with a structural base via multi-branch allOf.

**Is a facet Archetype possible without a structural base?**

Yes — and it MUST be. `DataProtectionArchetype` extends only `Archetype`
(the meta-Archetype, the seed). Its allOf chain is:

```json
{ "$id": "DataProtectionArchetype",
  "allOf": [{ "$ref": "Archetype" }],
  "properties": {
    "dataClassification": { "type": "string" },
    "retentionPolicy": { "type": "string" }
  }
}
```

This is a valid Archetype — rooted at `Archetype` (the seed). It defines a
vocabulary (property set) that is NOT tied to any structural kind.
It has `DefinitionSubjectType = ARCHETYPE`.

**But can it be instantiated as an Ascription?**

A facet Archetype by itself is NOT an Ascription subtype — you never create a
"DataProtection" Ascription in isolation. It only has meaning when composed
into a structural subtype via allOf. It is a **governance and type vocabulary
artifact**, not a structural element.

This means:

- Facet Archetypes are Ascriptions (they are Archetype instances, Archetype
  extends Ascription) with `subjectType = ARCHETYPE`.
- They are authored, versioned, governed through the standard lifecycle.
- They define schemas (property sets) that Directives and Norms reference
  as qualifiers.
- They are NOT instantiated as non-Archetype Ascriptions. You never POST a
  statement saying "create a DataProtection" — you create a
  `DataProtectionStructure` (which is a Structure, not a DataProtection).

**The base schema question resolved**:

| Pattern | Base | Rooted at | Goal |
|---|---|---|---|
| Structural subtype | Single GSM structural base | StructureArchetype, MechanismArchetype, etc. | Refine a structural kind |
| Facet | `Archetype` (the seed) | `Archetype` | Define cross-cutting properties |
| Structural + facet | Both | Multi-branch allOf: one branch → structural base, other branches → facets | Compose concerns |

**The convergence rule refined**:

The current GSM rule ("all allOf paths converge to the same GSM base") applies
to the **structural base chain**. Facet branches converge to `Archetype` (seed)
and are classified separately.

**DM resolution algorithm** for an allOf chain:

1. Walk each allOf `$ref` branch recursively.
2. Classify each terminal (reached-a-sealed-schema) branch:
   - If it reaches a GSM structural base (carries `$gsm:sealed` AND
     `$gsm:subjectType != ARCHETYPE`) → **structural base branch**.
   - If it reaches Archetype (seed) or a sealed Archetype with
     `$gsm:subjectType == ARCHETYPE` → **facet branch**.
3. All structural base branches MUST converge to the same GSM base.
   Multiple facet branches are permitted (compose multiple cross-cutting
   concerns).
4. Exactly one structural base is required for non-facet Archetypes
   (i.e., Archetypes intended to be instantiated as Structures, Mechanisms,
   etc.). Facet Archetypes may have zero structural base branches.

### Facet Archetypes under Option C pipeline model

Does a facet Archetype need its own Receptor/Effector/pipeline?

**No.** Facet Archetypes are type vocabulary — they are never the target of a
compilation pipeline. The **composed** Archetype (e.g.,
`DataProtectionStructureArchetype`) is the pipeline target. It has its own
Receptor/Effector (per Option C) wired to a pipeline that may include a
`DataProtectionEnrichmentMechanism` in the chain.

A Directive referencing `DataProtectionArchetype` as qualifier governs all
Ascriptions whose Archetype chain includes that facet. The Directive resolution
is a schema graph query (which Archetype allOf chains include this facet?), not
a pipeline concern.

**Severity under Option C**: Low. Facets are type vocabulary, not pipeline
targets. Option C handles composed Archetypes (structural base + facets) as
first-class pipeline endpoints. The facet itself is a governance artifact,
not a compilation artifact.

---

## Summary comparison: Option B vs Option C

| Concern | Option B (envelope archetypeId) | Option C (Receptor/Effector per Archetype) |
|---|---|---|
| Typing discriminator | `archetypeId` in request envelope | Receptor.inputArchetype / Effector.outputArchetype |
| Receptor autonomy | **Bypassed** — envelope overrides Receptor's Archetype | **Preserved** — Receptor IS the type authority |
| Mechanism autonomy | Mechanism receives data not fully typed by its Receptor | Mechanism receives only Receptor-typed data |
| Base vs extension diffing | DM must introspect allOf to diff base vs extension | Unnecessary — Mechanism handles what it knows, rest passes through |
| Pipeline ordering (depth-N) | DM must infer ordering from allOf depth | Explicit: Interaction topology = execution order |
| Facet handling | `archetypeId` must resolve multi-branch allOf | Facets are governance vocabulary, not pipeline targets |
| Pipeline discovery | Client must know `archetypeId` out-of-band | Client discovers types via pipeline Interface Effectors |
| Tenant setup complexity | Create Archetype only | Create Archetype + Receptor + Effector + Interactions + Interface update |
| Bootstrap simplicity | Simpler — less entities to seed | More entities, but all structurally coherent |
| GSM conceptual integrity | Receptor role is partly decorative | Receptor and Effector are first-class structural contracts |

**Recommendation**: Option C. The additional structural entities (Receptor
- Effector per tenant Archetype) are a real cost, but they preserve the
conceptual integrity of the Receptor/Effector/Mechanism model. Option B
reduces the Receptor to a decorative label — the real typing authority is the
envelope `archetypeId`, which bypasses the structural model.

The Mechanism autonomy argument is decisive: a Mechanism MUST NOT handle data
whose type is not fully identified by its own Receptor's Archetype schema.
If extension fields arrive that the Receptor doesn't know about (because the
Receptor is typed by the base Archetype, and the extension is declared in an
envelope field), the Mechanism is operating on untyped data. This contradicts
GSM's foundational principle that all causality is structurally mediated.

---

## Open questions — deep analysis

---

### Q1: Should DM auto-derive Receptors/Effectors when an allOf Archetype is created?

**The tension**: Option C requires Receptor + Effector per tenant Archetype.
If the tenant creates `MyStructureArchetype`, they need `MyStructureReceptor`
and `MyStructureEffector` before they can use the pipeline. Is this the
tenant's explicit responsibility, or does DM create them automatically?

**Three strategies evaluated:**

#### Strategy 1a — Fully explicit (tenant creates everything)

Tenant workflow:
1. Create `MyStructureArchetype` (Archetype Ascription)
2. Create `MyStructureReceptor` (Receptor Ascription, inputArchetype = MyStructureArchetype)
3. Create `MyStructureEffector` (Effector Ascription, outputArchetype = MyStructureArchetype)
4. Create Interactions wiring Receptor → Mechanism(s) → Effector
5. Update pipeline Interface to expose new Receptor/Effector

**Advantages**:
- Full governance control. Every entity goes through the standard lifecycle
  (DRAFT → PROPOSED → APPROVED → ACTIVE). No hidden side effects.
- The tenant chooses which Mechanisms to wire to — they can compose a custom
  pipeline from the start, or reuse existing base Mechanisms.
- Consistent with GSM philosophy: everything is an Ascription, everything
  is governed.

**Disadvantages**:
- High ceremony. Creating one Archetype requires 4+ additional Ascriptions
  before it's usable. This is operationally expensive — especially at Layer 1
  where the compilation logic is identical to the base (pass-through of
  extension fields).
- Error-prone: tenant might create the Archetype but forget the Receptor, or
  wire Interactions incorrectly. The Archetype exists but can't be used.
- Discovery problem: how does the tenant know WHICH Mechanisms to wire to?
  They need to understand the existing pipeline topology.

#### Strategy 1b — DM auto-derives Receptor/Effector (semi-automatic)

When a tenant creates a non-facet Archetype that extends a GSM base via allOf,
DM auto-derives:
- A Receptor typed by the new Archetype
- An Effector typed by the new Archetype
- Interactions wiring them to the same Mechanism(s) as the parent Archetype's
  Receptor/Effector
- Interface update to expose the new endpoints

These derived entities are themselves Ascriptions — visible, auditable, and
governable. They enter the lifecycle at DRAFT (or PROPOSED, following the parent
Archetype's status).

**Advantages**:
- Low ceremony. Create the Archetype; the pipeline is usable immediately
  (or after governance approval of the derived entities).
- Consistent behavior: every allOf Archetype automatically gets a pipeline
  endpoint. No orphan Archetypes.
- The tenant can modify/override any derived entity afterward (replace the
  auto-wired Mechanism, add custom Mechanisms, change the Interface grouping).
  Auto-derivation is the default, not a constraint.

**Disadvantages**:
- Side effects: creating one Ascription triggers creation of others. This is
  conceptually similar to how Mechanism compilation auto-derives Effectors
  and Receptors from the rule AST — so there IS precedent in GSM. But
  Mechanism auto-derivation is from the rule (explicit source), while
  Archetype auto-derivation is from the allOf chain (implicit source).
- Governance question: do the auto-derived entities need separate approval?
  If yes, the ceremony saving is smaller (tenant still reviews 4 items).
  If no, we're auto-approving structural changes — a governance gap.

#### Strategy 1c — Tiered: auto-derive at Layer 1, explicit at Layer 2

- **Layer 1** (schema-only extension): DM auto-derives Receptor/Effector and
  wires them to the existing base Mechanisms. The pipeline is a pass-through
  extension — base compilation + extension fields passed verbatim. No custom
  logic, so auto-derivation is safe and predictable.

- **Layer 2** (behavioral extension): when the tenant creates custom
  Mechanisms for the Archetype, they must explicitly create/rewire
  Receptors/Effectors/Interactions. This respects the governance control
  needed when behavioral logic changes.

**Bridging rule**: the auto-derived Receptor/Effector from Layer 1 is the
starting point. When the tenant adds a custom Mechanism (Layer 2), they
create a new Receptor/Effector pair (or modify the wiring of the existing
ones). The auto-derived entities are not frozen — they're modifiable first
versions.

**Recommended**: Strategy 1c. It matches the concept layers: Layer 1 is
schema-driven adaptation (auto-derivation is safe), Layer 2 is behavioral
extension (explicit wiring is required). The precedent exists: Mechanism
already auto-derives Effectors/Receptors from the rule AST.

Auto-derived entities SHOULD enter lifecycle at DRAFT and follow the standard
governance process. The tenant reviews and approves them — but doesn't have
to author them from scratch. This preserves governance control while reducing
ceremony.

---

### Q2: Should `statementReceptorId` be optional or mandatory?

**The question refined under Option C**: both `statementReceptorId` and
`compilationEffectorId` reference pipeline endpoints. Are both always needed?

#### Arguments for both mandatory

**Clarity of intent**: the client declares exactly what they're requesting —
"I'm sending data shaped like X (Receptor) and I expect output typed as Y
(Effector)." No ambiguity, no DM inference.

**Symmetry**: the statement contract (Receptor) and compilation contract
(Effector) are independent concerns. Making one optional privileges one over
the other. Both are structural — both should be explicit.

**API stability**: if `statementReceptorId` is optional at Layer 0 and becomes
required at Layer 2, the API contract changes. Clients that omitted it break.
Better to require it from the start — forward-compatible.

**Pipeline validation**: DM must validate that a path exists from the Receptor
to the Effector through the Mechanism chain. With both specified, this is a
straightforward graph traversal. With only the Effector, DM must figure out
which Receptor(s) can reach it, then pick one — or fail if ambiguous.

#### Arguments for `statementReceptorId` optional

**Deducibility at Layer 0**: each Effector has exactly one Mechanism, which has
exactly one Receptor. The mapping is 1:1:1. Requiring the client to specify
both is redundant.

**Client ergonomics**: the client cares about "I want to create a Structure"
(output type), not "I'm entering through StructureJsonReceptor" (pipeline
routing detail). The Effector is the business intent; the Receptor is the
plumbing.

#### Analysis: does the Effector determine the Receptor?

At Layer 0: yes. One pipeline per base type. `StructureCompilationEffector` 
→ `StructureCompilationMechanism` → `StructureReceptor`. Unique path.

At Layer 1 (with auto-derived Receptor/Effector per allOf Archetype):
`MyStructureEffector` → same Mechanism → `MyStructureReceptor`. Still
unique — auto-derivation preserves the 1:1 mapping.

At Layer 2: a tenant may create multiple pipelines producing the same
Effector Archetype (e.g., JSON pipeline and YAML pipeline → same
StructureArchetype output). In this case:
```
StructureJsonReceptor    → JsonPipelineMechanism    → StructureEffector
StructureYamlReceptor    → YamlPipelineMechanism    → StructureEffector
```
Now the Effector does NOT determine the Receptor. Both are required.

But wait — under Option C, each allOf variant has its OWN Effector. So the
Effector already discriminates the output Archetype. The question is whether
multiple input formats (JSON vs YAML) target the same Effector.

If they do → `statementReceptorId` is required for disambiguation.
If each input format has its own Effector → redundant again.

**The conceptual argument**: an Effector is the output endpoint of a specific
Mechanism. Two different pipeline Mechanisms cannot share the same Effector
instance — each has its own Effectors. So:
```
JsonPipelineMechanism.effector₁  →  StructureArchetype (output)
YamlPipelineMechanism.effector₂  →  StructureArchetype (output)
```
These are DIFFERENT Effector instances (effector₁ ≠ effector₂), even though
they produce the same Archetype. The client specifying `compilationEffectorId`
already selects the pipeline.

**This means**: under Option C with strict Effector-per-Mechanism ownership,
the `compilationEffectorId` IS the pipeline discriminator. The Receptor is
derivable from the Effector's Mechanism's Receptor(s).

**Exception**: a single Mechanism with multiple Receptors (a Mechanism that
accepts multiple input formats through different Receptors — a polyglot
Mechanism). In this case, the Mechanism is the same, the Effector is the same,
but the Receptor varies. The client must specify which Receptor to enter.

**Recommendation**: `statementReceptorId` optional with derivation rules.

```
if compilationEffectorId resolves to an Effector
  → resolve Effector.Mechanism (unique, by Effector ownership)
  → if Mechanism has exactly 1 Receptor → auto-select (statementReceptorId optional)
  → if Mechanism has N > 1 Receptors AND statementReceptorId is null → 422 error:
    "Multiple input formats available. Specify statementReceptorId. Options: [...]"
  → if statementReceptorId provided → validate it belongs to the Mechanism → use it
```

This preserves ergonomics at Layer 0/1 (always one Receptor per Mechanism,
always derivable) while supporting Layer 2 polyglot Mechanisms without
breaking change.

The pipeline validation constraint (Receptor → Mechanism chain → Effector) is
always satisfied because both endpoints are resolved to the same Mechanism
(or chain).

---

### Q3: Schema evolution governance for facet Archetypes

**The concern**: a facet Archetype (`DataProtectionArchetype`) adds a required
field. ALL composed Archetypes that allOf-reference it are affected:
`DataProtectionStructureArchetype`, `DataProtectionMechanismArchetype`,
`DataProtectionInterfaceArchetype` — and all downstream Ascriptions typed by
those composed Archetypes.

**Blast radius comparison**:

| Change | Affected Archetypes | Affected Ascriptions |
|---|---|---|
| Structural subtype: add required field to `ComplianceStructureArchetype` | `GDPRStructureArchetype`, `SOC2StructureArchetype` (descendants) | All Structures typed by descendants |
| Facet: add required field to `DataProtectionArchetype` | ALL composed Archetypes across ALL structural kinds | All Structures + Mechanisms + Interfaces whose Archetype chain includes `DataProtection` |

Facet evolution has **cross-kind blast radius** — it's not scoped to Structures
or Mechanisms but crosses structural boundaries. This is inherent to the
purpose of facets (cross-cutting concerns) but makes governance more complex.

**Governance process — proposed:**

**Phase 1: Impact analysis (before Archetype approval)**

When a facet Archetype is updated (new Ascription version with modified
schema), DM's compilation pipeline for Archetype itself must compute:

1. **Downstream Archetype inventory**: which Archetypes include this facet
   in their allOf chain? DM maintains the allOf graph — this is a graph
   query: "all Archetypes whose resolved allOf chain includes a `$ref` to
   this facet."

2. **Downstream Ascription inventory**: for each affected Archetype, how many
   ACTIVE or DEPRECATED Ascriptions are typed by it?

3. **Recompilation feasibility**: for each affected Ascription, can the
   preserved `statement` satisfy the new facet schema? Two categories:
   - **Auto-satisfiable**: the new field has a default or is derivable
     (Mechanism can compute it from existing statement data).
   - **Statement amendment required**: the new field is required and not
     derivable — the original statement must be updated by the client.

4. **Output**: Impact report attached to the facet Archetype Ascription as
   governance evidence (for the PROPOSED → APPROVED transition review).

**Phase 2: Migration (after Archetype approval)**

The updated facet Archetype is APPROVED (new version). DM triggers:

1. **Dependent Archetype revalidation**: each composed Archetype is
   revalidated against the updated facet schema. If the composed schema
   is still valid → no action on the Archetype itself. If invalid → the
   composed Archetype needs a new version too.

2. **Ascription recompilation**: DM re-runs the pipeline on preserved
   statements. Three outcomes per Ascription:
   - **Recompiled successfully**: new compilation satisfies new schema.
     New Ascription version auto-created (enters lifecycle).
   - **Auto-remediated**: the pipeline Mechanism derives the new field
     from existing data. New compilation includes it.
   - **Flagged for amendment**: statement lacks required data. Ascription
     is flagged (governance dashboard shows "pending statement amendment").
     No auto-action.

**Phase 3: Enforcement (ongoing)**

New Ascriptions typed by affected Archetypes must satisfy the updated schema
from creation. The Receptor's Archetype (under Option C) references the latest
Archetype version — statement validation enforces the new field.

**Key design decision**: should facet evolution be **blocking** or
**non-blocking**?

- **Blocking**: the facet Archetype update cannot be APPROVED until all
  downstream Ascriptions are recompilable. Safe but potentially paralyzing
  — one missing statement field blocks the entire facet evolution.
- **Non-blocking**: the facet is approved independently. Downstream
  Ascriptions are flagged for migration but remain ACTIVE under the previous
  Archetype version. Coexistence period until migration completes.

**Recommended**: Non-blocking with governance visibility. The facet evolves
independently (it's a governance decision at the facet level). Downstream
impact is surfaced as evidence (impact report) but doesn't block. Existing
Ascriptions continue operating under their pinned Archetype version. New
Ascriptions must use the new version. Migration is a governed process, not
an atomic operation.

This mirrors how real-world regulation works: GDPR adds a new requirement;
existing systems get a compliance deadline; they don't shut down instantly.

---

### Q4: Pipeline Interface exposure granularity

**The question**: should each tenant Archetype's Receptor/Effector be exposed
through a separate Interface, or grouped in a single "DM compilation
Interface"?

**GSM Interface semantics** (from GSM §1): Interface is a "declarative exposure
manifest" that groups Effectors and Receptors by "external exposure semantics."
Interface-level Norms target all exposed endpoints uniformly. The Interface
usage rule says:

> Group Effectors/Receptors by external exposure semantics (e.g., "admin",
> "monitoring", "public API"). Apply boundary-level governance Norms to
> Interfaces rather than individual Effectors/Receptors when the constraint
> is uniform across all grouped Effectors/Receptors, (AND) relative to the
> Interface itself.

This gives clear guidance. The grouping criterion is **exposure semantics**
(who/what accesses this boundary and under what governance), not structural
kind or Archetype family.

**Three granularity options:**

#### Option 4a — Single Interface ("DM compilation API")

All compilation Receptors/Effectors (base + tenant) grouped in one Interface.

```
Interface: "DMCompilationAPI"
  exposes: StructureReceptor, StructureEffector,
           MechanismReceptor, MechanismEffector,
           MyStructureReceptor, MyStructureEffector,
           GDPRStructureReceptor, GDPRStructureEffector,
           ...
```

**When this makes sense**: all pipeline endpoints share the same exposure
semantics — same authentication, same rate limiting, same audit requirements.
A Norm like "all compilation endpoints MUST require `policy_author` role"
applies uniformly.

**Problem**: as tenant Archetypes grow, this Interface becomes huge. An
Interface-level Norm affects ALL endpoints — you can't apply different
governance to GDPR compilations vs standard compilations at the Interface
level. You'd need per-Effector Norms, which defeats the Interface purpose.

#### Option 4b — Interface per structural kind ("Structure compilation", "Mechanism compilation")

Base structural kinds each get an Interface. Tenant extensions of the same
kind share it.

```
Interface: "StructureCompilation"
  exposes: StructureReceptor, StructureEffector,
           MyStructureReceptor, MyStructureEffector,
           GDPRStructureReceptor, GDPRStructureEffector

Interface: "MechanismCompilation"
  exposes: MechanismReceptor, MechanismEffector,
           CustomMechanismReceptor, CustomMechanismEffector
```

**When this makes sense**: governance varies by structural kind but not by
tenant extension. "All Structure compilation endpoints MUST validate purpose
uniqueness" — true for all Structure variants.

**Problem**: same issue at finer granularity. If GDPR Structures need stricter
governance than standard Structures (e.g., additional audit logging), the
Interface groups them together. Per-Effector Norms needed again.

#### Option 4c — Interface per governance domain (recommended)

Group by governance semantics — which is what Interface is designed for.

```
Interface: "BaseCompilation"
  exposes: StructureReceptor, StructureEffector,
           MechanismReceptor, MechanismEffector,
           ... (all GSM base type endpoints)

Interface: "ComplianceCompilation"
  exposes: GDPRStructureReceptor, GDPRStructureEffector,
           SOC2StructureReceptor, SOC2StructureEffector

Interface: "TenantDomainCompilation"
  exposes: MyStructureReceptor, MyStructureEffector,
           ... (tenant-specific, non-compliance)
```

**When this makes sense**: governance varies by concern domain. Compliance
endpoints get stricter audit Norms; base endpoints get standard Norms;
tenant domain endpoints get tenant-defined Norms.

**This aligns with GSM Interface usage rules**: group by external exposure
semantics. "BaseCompilation" is the GSM platform API. "ComplianceCompilation"
is vendor-governed with regulatory Norms. "TenantDomainCompilation" is
tenant-governed.

**Who decides the grouping?** The entity that governs the Structure:
- GSM base Interface → governed by SIE's own governance Structure (Layer 0)
- Vendor Interfaces → governed by vendor's governance Structure (Layer 1-2)
- Tenant Interfaces → governed by tenant's governance Structure (Layer 2)

**Recommended**: Option 4c. The Interface grouping follows governance
ownership, not structural kind. This lets each governance domain apply
uniform Interface-level Norms to its compilation endpoints without
cross-contamination.

When DM auto-derives Receptors/Effectors (Strategy 1c from Q1), the
assignment to an Interface follows the Archetype's governance domain:
- allOf extends a base → add to "BaseCompilation" Interface (default)
- allOf extends a vendor Archetype → add to vendor's Interface
- Tenant can move them to a different Interface afterward

This is a sensible default that preserves governance domain coherence
while allowing tenant override.
