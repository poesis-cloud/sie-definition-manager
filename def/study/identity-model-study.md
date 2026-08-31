# Design Study — Archetype Identity Model: from Title-Keyed to `$id`-Anchored

> **Temporary complete copy:** retained in Defman while active implementation
> processes depend on its code archaeology and work-package analysis. The
> vendor-neutral candidate semantics are owned by the
> [GSM Archetype referential model](https://github.com/poesis-cloud/gsm-research-lab/blob/main/docs/archetype-referential-model.md#2-candidate-identity).
>
> Design study. Companion implementation document:
> [identity-model-implementation-spec.md](../spec/identity-model-implementation-spec.md).
> Status: **reviewed — multi-model aligned** (Claude Opus 5, 7 adversarial rounds; GPT-5.6 Sol, 3 adversarial rounds; 2026-08-22), then **revised post-alignment per owner decisions and source-grounded challenge closure** (2026-08-23): pre-production simplification; `ref("$id")` CEL profile; all external Archetype references by `$id`; stem as the Archetype identity-bound property and the **permanent URI owner** of one Definition (not the in-effect uniqueness helper used by `purpose`/`function`); generic `definitionId` CREATE; candidate-version check on `DRAFT → PROPOSED`; URI resolvability with generic convergence on `PROPOSED → APPROVED`. Parked, out of this WP: [purpose-function-non-uniqueness-study.md](purpose-function-non-uniqueness-study.md), [archetype-static-composite-study.md](archetype-static-composite-study.md). The alignment verdicts attach to the pre-revision text.

## 1. Context and motivation

The `gsm-ontology` catalogue has an explicit ambition: a **world-scale ontology** —
reference frameworks (TOGAF, ISO 25000, SCAP, W3C PROV, GDPR, NIS2, HTTP, OIDC, …)
sourced as isolated vocabularies and composed at the domain layer (the `it` framework:
ontology, articulations, materialized composites). The Definition Manager (DM,
`sie-definition-manager`) is the GSM reference implementation that must ingest,
validate, version, and govern these definitions.

At world scale, the unit of identity matters. This study formalizes the finding that
the DM's current identity mechanism — the Archetype **`title`** as effective primary
key — carries structural limits that the catalogue's growth has begun to expose, and
proposes the target model: **title as the validated local-name component of a
namespaced, versioned `$id`**.

## 2. Current model (verified in code, 2026-08-22)

### 2.1 What `title` is today

| Mechanism                                      | Code location                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | Behavior                                                                                                                                                                                                                                                                                                                                                                                                             |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Identity property                              | `AscriptionUniquenessValidationService.validatePropertyAcrossDefinitions`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | `title` is the Archetype's identity property **today**. Parallel only as _identity-bound_ to `Structure.purpose` / `Mechanism.function`. Uniqueness **scope** already diverges: purpose is global among in-effect Structures; function is unique among in-effect Mechanisms of **one** Structure. Title uniqueness is in-effect-only. Stem uniqueness in the target model is a different kind (permanent URI owner). |
| DB invariant                                   | `V1__gsm_init.sql` `uq_archetype_title`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | unique partial index on `statement->>'title'` among ACTIVE                                                                                                                                                                                                                                                                                                                                                           |
| `$ref` resolution (3 DB lookups, 5 call sites) | `ArchetypeParsingService.extractTitleFromRef` call sites: `AscriptionParsingValidationService:294`, `ArchetypeService:323,343`, `ArchetypeCompositionValidationService:132,221` — feeding `findInEffectByTitle` (ACTIVE+DEPRECATED), `findResolvableByTitle` (complement of {RETIRED, ABANDONED, REJECTED} — "all non-terminal" only if RETIRED counts as terminal — authoring-time nested-`$ref` resolution), `ArchetypeService.resolveArchetypeSchema` (ACTIVE+DEPRECATED). Composition validation additionally keys its cycle-detection and base-convergence sets on titles | every path resolves by the **last path segment only**; the namespace path is discarded. Additionally, networknt `schemaMappers` route `gsmarc://gsm/…` refs to `classpath:gsm/schemas/{firstSegment}.schema.json` — structurally dependent on the `gsm` authority having an empty namespace path                                                                                                                     |
| CEL applicability roots                        | `NormApplicabilityValidationService:89`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | root identifiers in applicability expressions **are titles**, resolved globally by title                                                                                                                                                                                                                                                                                                                             |
| CEL assertions                                 | `NormAssertionValidationService`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | **title-free** — bare property paths bound to the Norm's qualifier archetype                                                                                                                                                                                                                                                                                                                                         |
| Governance-chain machinery                     | `ArchetypeService.getAncestorTitles` / `collectAncestorTitles` / `isDescendantOf`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | ancestor chains and descendant checks are computed over **title strings**                                                                                                                                                                                                                                                                                                                                            |
| Mechanism port derivation                      | `MechanismPortDerivationService:225-269`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | hard-coded `"Effector"`/`"Receptor"` titles plus rule-declared data-archetype names                                                                                                                                                                                                                                                                                                                                  |
| Starlark rule validation                       | `MechanismRuleValidationService:185,622`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | archetype names in rules are titles                                                                                                                                                                                                                                                                                                                                                                                  |
| Starlark dispatch                              | Operator `sys.receive("…")` / `sys.effect("…")`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | mechanisms receive/emit **by title string**                                                                                                                                                                                                                                                                                                                                                                          |

### 2.2 What `version` is today

**Nothing — like `$id` (§2.3).** The `version` column was dropped in design-phase cleanup
(`V1__gsm_init.sql` header note 8: "version column dropped … never used for
queries"); `AscriptionEntity` has no version field. The only derivable ordering is
`findAllByDefinitionIdOrderByTimestampDesc`. `gsm.puml` still documents a version
column and "version assigned at APPROVED" — divergent design intent, like `$id`
minting. The target model must therefore _introduce_ version materialization, not
merely re-key it (see spec §2.2).

### 2.3 What `$id` is today

**Nothing, at runtime — with one nuance.** The DM's main code contains no `$id`
minting, parsing, or validation, and `gsm.puml`'s "_`$id` assigned by DM_" is
unimplemented design intent. However, the **8 seeded GSM base schemas already carry
`$id`** (`gsmarc://gsm/{Title}/v1`, loaded by `ArchetypeSeedRunner` from
`classpath:gsm/schemas/*.json`) — they are simply never read. Title↔`$id` coherence is
enforced **only** by the catalogue lint (`gsm-ontology/scripts/lint_layers.py`,
rule L5: filename stem ≡ `title` ≡ `$id` segment before the version suffix).

### 2.4 Companion statement-level model (already executed in the catalogue)

Two decisions taken in the catalogue during 2026-08 frame this study and are treated
as fixed inputs:

- **Statement closure belongs to the DM** — author-side top-level
  `additionalProperties/unevaluatedProperties: false` removed from 92 archetypes
  (mirroring defman commit `e2f4760` which removed it from the GSM base schemas);
  the DM applies `unevaluatedProperties: false` at the root of the _resolved_ typing
  archetype at ascription time (GSM §5 "Statement closure"). Lint L7 prevents
  regression.
- **Facet mount rule** — JSON Schema does not namespace instance properties; sibling
  `allOf` facets sharing a property name conflate data or produce an unsatisfiable
  intersection (proven: `availabilityTarget` is a string enum in
  `Iso25000ServiceReliability` and a number in `Iso25000DataAvailability`; their bare
  `allOf` accepts **no** value). Composites therefore mount each facet under an
  optional property keyed `lowerCamelCase(facetTitle)`. Lint L8 statically rejects
  sibling-facet name duplication and vertical type-changing redefinitions. L8 is a
  conservative semantic-collision policy for these known hazards, not a general
  JSON Schema satisfiability solver.

## 3. Pain points of the current model

| #   | Pain point                                                                                                                                                                                                                                                                                                                                                                                               | Evidence                                                                                                                   | Severity                                                                                                                                    |
| --- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| PP1 | **Global title namespace** — every framework author on earth competes for names in one flat namespace; the catalogue's framework-prefix rule (`Iso25000…`, `Togaf…`) is a _workaround_ encoding the namespace into the name                                                                                                                                                                              | prefix rule adopted 2026-08-21 precisely to resolve cross-framework collisions                                             | High at world scale                                                                                                                         |
| PP2 | **Version non-addressability** — a newer ACTIVE and an older DEPRECATED ascription of the same Definition are both in-effect under one title; `findFirstByStatementTitleAndStatusIn` is order-dependent; no way to name a specific revision by title. (There is no version _numbering_ at all today — §2.2 — which makes the ambiguity strictly worse: revisions are distinguishable only by timestamp.) | `ArchetypeRepository` query; in-effect = {ACTIVE, DEPRECATED}; `V1__gsm_init.sql` note 8                                   | High                                                                                                                                        |
| PP3 | **Lossy `$ref` resolution** — all three resolution lookups discard the namespace they are given; correctness silently depends on PP1's global uniqueness. Acutest form: authoring-time nested-`$ref` resolution registers a title-resolved schema under the _requested_ URI (`findResolvableByTitle`)                                                                                                    | `extractTitleFromRef`; `AscriptionParsingValidationService:294`                                                            | High (latent defect)                                                                                                                        |
| PP4 | **Zero runtime coherence** — a tenant can submit `title: Foo` with `$id: …/Bar/v1` and the DM accepts it; the invariant lives only in a catalogue lint the DM never runs                                                                                                                                                                                                                                 | absence of `$id` validation in main code                                                                                   | Medium                                                                                                                                      |
| PP5 | **Composition semantic collisions were undetected** — the `availabilityTarget` clash existed for weeks; nothing composed two ISO facets until the `it` composites, and no collision policy caught either property aliasing or the proven incompatible-type intersection                                                                                                                                  | post-mortem 2026-08-22; now mitigated by conservative L8 policy (catalogue-side only, not a complete satisfiability proof) | Mitigated; DM-side policy gap closed by spec §2.3 `ARCHETYPE_ALLOF_PROPERTY_DISJOINTNESS` + `ARCHETYPE_ALLOF_PROPERTY_TYPE_STABILITY` (WP1) |
| PP6 | **Omitting `definitionId` can fork a published URI** — CREATE is generic (`null` → new Definition). After title is demoted, a second CREATE that omits `definitionId` but reuses the same stem would mint a second owner of that URI unless uniqueness-across-definitions is applied to the stem at CREATE, against **every** other Definition including ABANDONED/REJECTED                              | `AscriptionCreationDto.definitionId` optional; uniqueness today is in-effect `title` only                                  | High; closed by spec §2.3: one Definition owns a stem forever                                                                               |

## 4. Target model

### 4.1 Identity structure

```text
$id = gsmarc://{authority}/{namespacePath}/{title}/v{version}
   └─────────────── Archetype stem ───────────────┘ └ governance version ┘
```

Four DM-enforced invariants:

1. **Coherence** — the declared `title` MUST equal the `$id` path segment
   immediately before `/v{version}` (catalogue lint L5, lifted into runtime
   validation).
2. **Candidate before resolvable** — `$id`-minus-version is the Archetype
   Definition's **stem**. The producer authors a full Archetype URI, but in DRAFT it is only
   a candidate claim. On the `DRAFT → PROPOSED` transition (internally named
   `SUBMIT`, not a status), the DM requires `/v{n}` where
   `n = 1 + max(approved Ascription.version)`. This is not an exclusive reservation:
   ordinary GSM permits multiple PROPOSED siblings, including competitors carrying
   the same candidate URI. On `PROPOSED → APPROVED`, the DB assigns the governance
   version, generic approval convergence terminates the siblings, and the winner's
   Archetype URI becomes resolvable. A post-flush equality check is defensive; candidate
   URIs do not dereference.
3. **Stem is identity-bound _and_ a permanent URI owner** — two distinct
   contracts; do not collapse them.
   - **Identity-bound (same Definition):** every Ascription of one Definition
     carries the same stem (`AscriptionIdentityBoundValidationService`). A
     sibling with a different stem is 400. This _is_ the same contract as
     `purpose` / `function` (and stays even if those later lose uniqueness —
     see the parked [purpose-function-non-uniqueness-study.md](purpose-function-non-uniqueness-study.md)).
   - **Uniqueness _scope_ is not shared.** Purpose uniqueness is global among
     **in-effect** Structures. Function uniqueness is among **in-effect**
     Mechanisms of **one** Structure. There is **no** common uniqueness-scope
     rule for stem to copy or break. Stem uniqueness is **permanent URI
     ownership**: one Definition owns a stem forever, including after every
     Ascription of that Definition is ABANDONED or REJECTED. Another
     Definition cannot republish the family. This is intuitively right for a
     published versioned URI and is an owner decision, not an analogy.
   - **DB backup is “no other `definition_id` has this stem.”** Many
     Ascriptions of the _same_ Definition share the stem (`/v1`, `/v2`,
     competing DRAFT/PROPOSED). An unconditional `UNIQUE(stem)` as one-row-only
     is fatal. Spec §2.6 prescribes the trigger / exclusion, not that index.
   - No extra registry table and no column on `definition`. Titles stay unique
     only inside their namespace, as the local name inside the stem.
4. **CREATE is generic** — omit `definitionId` to create a Definition; send
   `definitionId` to attach another Ascription to that Definition. Same contract
   as the other seven types. No Archetype CREATE exception, no authority
   policy. A stem that already belongs to another Definition is a
   uniqueness-across-definitions conflict (409), including after the first
   owner’s candidates are all ABANDONED/REJECTED. A stem that disagrees with
   siblings of the supplied Definition is the existing identity-bound integrity
   failure (400). A failed candidate of the same Definition may be re-authored
   with the same next `/v{n}`; that is a new Ascription of the same Definition,
   not a new identity. SUBMIT is a transition, not a status; multiple PROPOSED
   siblings (including same candidate `$id`) remain valid.

### 4.2 Reference and expression semantics

- **`$ref` resolution by full `$id`** — version-pinned. Once the target Archetype URI is
  resolvable, a governed composite referencing
  `…/Iso25000ServiceReliability/v1` validates identically forever. "Floating latest"
  is deliberately inexpressible: adopting a facet's v2 requires re-issuing the
  composite as a new version, making upgrade propagation **explicit in the definitions**
  (definition-centrism honored; audit trail intact). All lookup paths (§2.1) get
  `$id` counterparts. An external `$ref` means a reference to another governed
  Archetype root `$id`; local fragment refs remain internal to the same schema.
  External `$ref` / `allOf` `$ref` targets become ordinary GSM References via
  `ArchetypeService.getRefereeReferences()` (schema walk). Archetype becomes a
  Referee in `gsm-ascription-lifecycle.puml`. **No Cascade. No Interaction
  primitive.** Derived static relation (property ∥ Mechanism, relation ∥
  Interaction) is parked in
  [archetype-static-composite-study.md](archetype-static-composite-study.md).
  **One `$ref` eligibility matrix** — the four surfaces are not uniform today
  and MUST NOT be specified as if they were:

  | Surface                                    | Lookup                                    | Eligibility                                                                     |
  | ------------------------------------------ | ----------------------------------------- | ------------------------------------------------------------------------------- |
  | Resolvable Archetype URI read              | `findResolvableByUri` (`version > 0`)     | none — RETIRED/SUSPENDED remain readable                                        |
  | Typing (`AscriptionCreationDto.archetype`) | resolvable Archetype URI                  | existing typing policy: `ACTIVE` \| `DEPRECATED`                                |
  | CREATE / SUBMIT referees                   | resolvable Archetype URI then Referee set | CREATE {DRAFT, PROPOSED, APPROVED, ACTIVE}; SUBMIT {PROPOSED, APPROVED, ACTIVE} |
  | APPROVE / ACTIVATE referees                | same                                      | APPROVE {APPROVED, ACTIVE}; ACTIVATE {ACTIVE}                                   |

  Historical pins need DEPRECATED (typing already allows it; APPROVE/ACTIVATE
  sets do not). WP1 publishes the table above as the matrix and tests it.
  Widening APPROVE to include DEPRECATED so a composite can pin a historical
  type is a later matrix edit (static-composite study), not a silent
  “unmodified sets” claim. The classpath
  schema mapper is replaced by an exact-match table of the 8 seeded base `$id`s
  (title-group or first-segment keying stays version/namespace-blind).

- **Archetype references use external identity at every authoring boundary** — the
  creation envelope's typing Archetype, Directive/Norm qualifiers, and
  Effector/Receptor data Archetypes carry full `$id`s externally and materialize
  exact Archetype Ascription UUID FKs internally. For reference-valued
  identity-bound fields, cross-Ascription equality is the referenced Archetype's
  Definition/stem, not raw `$id` text: a later Ascription may deliberately re-pin
  `/v1` to `/v2` of the same Archetype Definition, but may not switch Definitions.
- **CEL applicability roots** — target: in-expression `ref("$id")` calls (a raw
  `$id` is not a valid CEL identifier, so the URI travels as a string constant),
  making applicability self-contained and version-pinned with **no new Norm field**;
  assertions are already title-free. Frontends display the short title (derivable
  from the `$id`'s title segment) and may expand short names to `ref()` at input.
- **Governance chain, port derivation, Starlark names** — the ancestor/descendant
  machinery moves to definition-id strings; `MechanismPortDerivationService`'s
  `Effector`/`Receptor` anchors and rule-declared data-archetype names move to
  `$id`s (rule-declared names move to `ref("$id")`-style references); GSM-base detection anchors
  on the full `$id` (`gsmarc://gsm/{Title}/v1`), not the last segment — today
  `gsmarc://acme/x/Structure/v1` is misread as the sealed base.
- **Starlark dispatch** — `sys.receive`/`sys.effect` move from title strings to
  `$id` (or rule-declared `ref()` references), removing the last global-title dependency.

### 4.3 What `title` becomes

Not decorative — **the local name of a namespaced identifier** (like a class name in
a package): human-readable, identity-bound by construction, machine-validated against
`$id`, but no longer the global lookup key. The catalogue's framework-prefix rule
remains as a _filename/readability convention_ (mount keys, diffs, prose) — valuable,
no longer load-bearing.

## 5. Impact analysis

### 5.1 Current vs target

| Dimension          | Current (title-keyed)                                                    | Target (`$id`-anchored)                                              |
| ------------------ | ------------------------------------------------------------------------ | -------------------------------------------------------------------- |
| Namespace scale    | one flat global namespace; prefix workaround                             | unbounded namespaces; titles scoped                                  |
| Version addressing | impossible (order-dependent `findFirst`)                                 | exact (`/v{n}` in every ref)                                         |
| Reproducibility    | ref meaning changes when a new version activates                         | version-pinned Archetype URIs; frozen governed composition           |
| Coherence          | title and `$id` unchecked at runtime                                     | structurally validated invariant                                     |
| Upgrade semantics  | implicit (latest-in-effect wins silently)                                | explicit dependent re-issue (governed change)                        |
| Lookup simplicity  | one string, one index                                                    | URI parsing + namespace-aware index                                  |
| Human ergonomics   | short names _if_ no prefix needed (in practice prefixes required anyway) | names readable in context; full URIs verbose in refs                 |
| CEL expressions    | terse roots (`GdprProcessingPrinciple.…`)                                | `ref("$id")` wrapper (verbose in storage; frontends shorten display) |
| Migration cost     | zero                                                                     | four-repository migration; detailed in §5.3                          |

### 5.2 Positive impacts

1. **World-ontology readiness** (PP1 dissolved): frameworks own their namespace;
   sourcing a new standard can never collide with an existing one.
2. **Deterministic version resolution** (PP2, PP3 fixed): every resolvable Archetype URI names
   exactly one governed schema forever; concurrent in-effect versions coexist
   addressably — migration windows become safe.
3. **Runtime integrity** (PP4 fixed): the catalogue's L5 invariant becomes a DM
   guarantee; a whole class of silent identity drift becomes impossible.
4. **Governance-visible evolution**: upgrades are re-issued dependent definitions —
   reviewable, approvable, auditable through the ordinary Ascription lifecycle instead
   of happening implicitly at resolution time.
5. **Spec/impl convergence**: the divergent `gsm.puml` intents are resolved — version
   semantics are implemented as written, while `$id` authorship is **amended** to
   producer-authored/DM-governed (minting is incompatible with statement immutability),
   with deterministic candidate-version validation and approval-time URI resolvability
   replacing the unimplemented "assigned by DM".

### 5.3 Negative impacts and costs

1. **Four-repo migration**: defman (DDL + ≥6 services + repository + schema mappers),
   gsm-specifications (normative amendment: candidate-to-resolvable Archetype
   identity, semantic reference equality, CEL profile; vendored-schema sync +
   `GsmSchemaVendorSyncTest`), sie-operator (dispatch strings; its DTOs carry an
   `int version` the DM currently cannot supply — fixed by version materialization),
   gsm-ontology (lint L5 generalization to `/v[1-9][0-9]*`, composite re-pinning
   workflow) — staged, but each phase is a real change with review surface. Ripple:
   the workspace `gsm-knowledge` skill (`.github/skills/gsm-knowledge/SKILL.md`)
   still teaches "`$id` assigned by DM" and must be updated with the
   producer-claimed decision.
2. **Candidate validation and URI resolvability are new lifecycle machinery**: the DM has no governance
   version column today (§2.2), so trigger assignment at APPROVED (`0 = not yet
approved`, gapless per Definition) must be added across all 8 concrete tables.
   Archetypes additionally validate the next ordinal on `DRAFT → PROPOSED` under
   the stem lock while retaining ordinary multiple-PROPOSED convergence;
   `PROPOSED → APPROVED` verifies the trigger's assignment defensively. Concurrent
   approval races surface as translated conflicts rather than changing which
   candidate's URI becomes resolvable.
3. **CEL profile change** (WP2): applicability roots move from bare titles to
   `ref("$id")` calls — a mechanical rewrite of the 78 catalogue Norm expressions
   and of the applicability CEL profile prose; no Norm schema field, no
   declare-then-use habit change (the wrapper is the whole change).
4. **Verbosity**: full `$id`s in refs, qualifiers, and `ref()` calls are long.
   Mitigated by tooling/display shortening. They appear only where an Archetype is
   referenced: schema `$ref`, typing envelopes, qualifiers, port data types,
   Starlark declarations, and applicability expressions.
5. **Pinning burden**: version-pinned refs mean facet upgrades do not propagate until
   composites are re-issued. This is the intended governance property, but it _is_
   operational work — the planned catalogue generator (ontology.json → composites)
   must own re-pinning, and it has no owner yet (named open risk).
6. ~~Dual-key transition risk~~ — **removed by owner decision 2026-08-22**: the DM
   is pre-production; Archetype references resolve by exact `$id` without title
   fallback from day one (spec §2.5), so no lenient window exists.
7. **Index/query changes**: `uq_archetype_title` survives WP1 only to guard the one
   remaining title lookup (CEL applicability roots) and is deleted with WP2; the
   `$id` and stem uniqueness indexes are authored directly in `V1` (pre-production
   in-place edit).
8. **Per-stem locking adds bounded ingestion serialization**: CREATE,
   `DRAFT → PROPOSED`, and `PROPOSED → APPROVED` serialize only callers claiming the
   same stem (plus conservative `hashtext` collisions); distinct stems remain
   concurrent. A batch import that
   acquires multiple stem locks in one transaction must sort by stem, or use one
   transaction per claim, to avoid lock-order deadlocks.

### 5.4 Rejected alternatives

| Alternative                                                              | Why rejected                                                                                                                                                                                                                                  |
| ------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Keep global titles forever (status quo)                                  | PP1/PP2 are structural, not cosmetic; the prefix rule already concedes that names need namespaces — it just encodes them in the wrong layer                                                                                                   |
| Titles fully decorative, identity = opaque `$id` with no title relation  | loses the human-readable identity anchor; coherence validation (title ≡ segment) costs nothing and keeps names trustworthy                                                                                                                    |
| Qualified CEL roots (`iso25000.ServiceReliability.…`) instead of `ref()` | not syntactically expressible — CEL identifiers cannot carry `/`, `://`, `-`; and namespace-structure-in-identifiers would not pin versions                                                                                                   |
| `bindings` map on Norm (`{localName: archetypeRef}`)                     | initially adopted, then superseded 2026-08-22: new schema field + declare-then-use indirection + full expression rewrite, where `ref("$id")` achieves the same pinning and lifecycle referee semantics with no field and a mechanical wrapper |
| Extra `archetype_definition_identity` registry table                     | invents a second identity store; stem lives on the statement `$id`; uniqueness is “one Definition owns the stem”                                                                                                                              |
| Forbidding `definitionId` on Archetype CREATE                            | a CREATE exception for one subject type; attachment is already expressed by `definitionId`, and fork prevention is uniqueness, not a different API                                                                                            |
| Authority/channel policy in this identity model                          | `authority` is a grammar token, not an auth concept; seed/import/REST policy is out of scope                                                                                                                                                  |
| Un-pinned (version-less) refs with latest-in-effect resolution           | reintroduces PP2 at composition level; validation results become time-dependent — unacceptable for governed definitions                                                                                                                       |
| Copy purpose/function uniqueness _scope_ onto stem                       | those scopes already diverge (global in-effect vs per-Structure in-effect) and may be dropped later; a published URI is a permanent owner, not a live CMDB slot                                                                               |
| Unconditional `UNIQUE(stem)` as one-row-only                             | `/v1` and `/v2` of the same Definition share the stem; the constraint must allow N rows with the same `(stem, definition_id)` and forbid a second `definition_id`                                                                             |
| Mint a GSM Interaction for `$ref` / reverse-cascade Archetype retire     | references ≠ cascades; Confluent keeps schema id after soft-delete and does not cascade to consumers; parked in the static-composite study                                                                                                    |

## 6. Decision

Adopt the target model, organized as three **work packages** (see implementation
spec §1): DM core (stem as identity-bound property **and** permanent URI owner +
candidate-to-resolvable Archetype URI + governance version + external Archetype references
by `$id` + title demotion + one `$ref` eligibility matrix), CEL applicability
`ref("$id")`, operator dispatch.
Pre-production simplification (owner decision 2026-08-22): no compatibility
staging. No extra identity registry table.

Parked, do not implement in these WPs:

- [purpose-function-non-uniqueness-study.md](purpose-function-non-uniqueness-study.md)
  — keep identity-bound on purpose/function; uniqueness may later become a class
  selector / Definition-UUID bind.
- [archetype-static-composite-study.md](archetype-static-composite-study.md)
  — property ∥ Mechanism, derived relation ∥ Interaction; no Interaction
  primitive now.
