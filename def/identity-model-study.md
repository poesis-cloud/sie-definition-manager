# Design Study — Archetype Identity Model: from Title-Keyed to `$id`-Anchored

> Design study. Companion implementation document:
> [identity-model-implementation-spec.md](identity-model-implementation-spec.md).
> Status: **reviewed — multi-model aligned** (Claude Opus 5, 7 adversarial rounds; GPT-5.6 Sol, 3 adversarial rounds; 2026-08-22). Residual non-blocking polish suggestions from the final sign-offs are tracked in the review record, not in-line.

## 1. Context and motivation

The `gsm-frameworks` catalogue has an explicit ambition: a **world-scale ontology** —
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

| Mechanism | Code location | Behavior |
|---|---|---|
| Identity property | `AscriptionUniquenessValidationService.validatePropertyAcrossDefinitions` | `title` is the Archetype's identity property, parallel to `Structure.purpose` and `Mechanism.function`: unique across *different* Definitions among in-effect ascriptions; versions of the same Definition share it; renaming = new Definition |
| DB invariant | `V1__gsm_init.sql` `uq_archetype_title` | unique partial index on `statement->>'title'` among ACTIVE |
| `$ref` resolution (3 DB lookups, 5 call sites) | `ArchetypeParsingService.extractTitleFromRef` call sites: `AscriptionParsingValidationService:294`, `ArchetypeService:323,343`, `ArchetypeCompositionValidationService:132,221` — feeding `findInEffectByTitle` (ACTIVE+DEPRECATED), `findResolvableByTitle` (complement of {RETIRED, ABANDONED, REJECTED} — "all non-terminal" only if RETIRED counts as terminal — authoring-time nested-`$ref` resolution), `ArchetypeService.resolveArchetypeSchema` (ACTIVE+DEPRECATED). Composition validation additionally keys its cycle-detection and base-convergence sets on titles | every path resolves by the **last path segment only**; the namespace path is discarded. Additionally, networknt `schemaMappers` route `gsmarc://gsm/…` refs to `classpath:gsm/schemas/{firstSegment}.schema.json` — structurally dependent on the `gsm` authority having an empty namespace path |
| CEL applicability roots | `NormApplicabilityValidationService:89` | root identifiers in applicability expressions **are titles**, resolved globally by title |
| CEL assertions | `NormAssertionValidationService` | **title-free** — bare property paths bound to the Norm's qualifier archetype |
| Governance-chain machinery | `ArchetypeService.getAncestorTitles` / `collectAncestorTitles` / `isDescendantOf` | ancestor chains and descendant checks are computed over **title strings** |
| Mechanism port derivation | `MechanismPortDerivationService:225-269` | hard-coded `"Effector"`/`"Receptor"` titles plus rule-declared data-archetype names |
| Starlark rule validation | `MechanismRuleValidationService:185,622` | archetype names in rules are titles |
| Starlark dispatch | Operator `sys.receive("…")` / `sys.effect("…")` | mechanisms receive/emit **by title string** |

### 2.2 What `version` is today

**Nothing — like `$id` (§2.3).** The `version` column was dropped in design-phase cleanup
(`V1__gsm_init.sql` header note 8: "version column dropped … never used for
queries"); `AscriptionEntity` has no version field. The only derivable ordering is
`findAllByDefinitionIdOrderByTimestampDesc`. `gsm.puml` still documents a version
column and "version assigned at APPROVED" — divergent design intent, like `$id`
minting. The target model must therefore *introduce* version materialization, not
merely re-key it (see spec §2.2).

### 2.3 What `$id` is today

**Nothing, at runtime — with one nuance.** The DM's main code contains no `$id`
minting, parsing, or validation, and `gsm.puml`'s "*`$id` assigned by DM*" is
unimplemented design intent. However, the **8 seeded GSM base schemas already carry
`$id`** (`gsmarc://gsm/{Title}/v1`, loaded by `ArchetypeSeedRunner` from
`classpath:gsm/schemas/*.json`) — they are simply never read. Title↔`$id` coherence is
enforced **only** by the catalogue lint (`gsm-frameworks/scripts/lint_layers.py`,
rule L5: filename stem ≡ `title` ≡ `$id` segment before the version suffix).

### 2.4 Companion statement-level model (already executed in the catalogue)

Two decisions taken in the catalogue during 2026-08 frame this study and are treated
as fixed inputs:

- **Statement closure belongs to the DM** — author-side top-level
  `additionalProperties/unevaluatedProperties: false` removed from 92 archetypes
  (mirroring defman commit `e2f4760` which removed it from the GSM base schemas);
  the DM applies `unevaluatedProperties: false` at the root of the *resolved* typing
  archetype at ascription time (GSM §5 "Statement closure"). Lint L7 prevents
  regression.
- **Facet mount rule** — JSON Schema does not namespace instance properties; sibling
  `allOf` facets sharing a property name conflate data or produce an unsatisfiable
  intersection (proven: `availabilityTarget` is a string enum in
  `Iso25000ServiceReliability` and a number in `Iso25000DataAvailability`; their bare
  `allOf` accepts **no** value). Composites therefore mount each facet under an
  optional property keyed `lowerCamelCase(facetTitle)`. Lint L8 statically rejects
  sibling-facet name duplication and vertical type-changing redefinitions.

## 3. Pain points of the current model

| # | Pain point | Evidence | Severity |
|---|---|---|---|
| PP1 | **Global title namespace** — every framework author on earth competes for names in one flat namespace; the catalogue's framework-prefix rule (`Iso25000…`, `Togaf…`) is a *workaround* encoding the namespace into the name | prefix rule adopted 2026-08-21 precisely to resolve cross-framework collisions | High at world scale |
| PP2 | **Version non-addressability** — a newer ACTIVE and an older DEPRECATED ascription of the same Definition are both in-effect under one title; `findFirstByStatementTitleAndStatusIn` is order-dependent; no way to name a specific revision by title. (There is no version *numbering* at all today — §2.2 — which makes the ambiguity strictly worse: revisions are distinguishable only by timestamp.) | `ArchetypeRepository` query; in-effect = {ACTIVE, DEPRECATED}; `V1__gsm_init.sql` note 8 | High |
| PP3 | **Lossy `$ref` resolution** — all three resolution lookups discard the namespace they are given; correctness silently depends on PP1's global uniqueness. Acutest form: authoring-time nested-`$ref` resolution registers a title-resolved schema under the *requested* URI (`findResolvableByTitle`) | `extractTitleFromRef`; `AscriptionParsingValidationService:294` | High (latent defect) |
| PP4 | **Zero runtime coherence** — a tenant can submit `title: Foo` with `$id: …/Bar/v1` and the DM accepts it; the invariant lives only in a catalogue lint the DM never runs | absence of `$id` validation in main code | Medium |
| PP5 | **Composition unsatisfiability was undetected** — the `availabilityTarget` clash existed for weeks; nothing composed two ISO facets until the `it` composites, and no satisfiability check existed | post-mortem 2026-08-22; now mitigated by L8 (catalogue-side only) | Mitigated; DM-side gap closed by spec §2.3 `ARCHETYPE_COMPOSITION_PROPERTY_COLLISION` (Phase 1) |

## 4. Target model

### 4.1 Identity structure

```text
$id = gsmarc://{authority}/{namespacePath}/{title}/v{version}
      └──────────────── definition identity ─────────┘ └─ ascription version ─┘
```

Three DM-enforced invariants:

1. **Coherence** — the declared `title` MUST equal the `$id` path segment
   immediately before `/v{version}` (catalogue lint L5, lifted into runtime
   validation).
2. **Identity** — `$id`-minus-version is the Definition's identity string (its
   **stem**), registered one-to-one against the Definition at first approval and
   permanently non-reusable. It is identity-bound **by construction**; consequently
   `title` is identity-bound structurally, not merely by `$gsm:identityBound`
   annotation. `version` is the ascription version: **producer-claimed in the `$id`,
   DM-verified** against the trigger-assigned ordinal at the APPROVED transition
   (the DM never mints — statements are immutable, so claim-and-verify is the only
   coherent authorship; this amends `gsm.puml`'s "assigned by DM" intent). A
   catalogue file claiming `/v1` is a **design-time claim** reconciled at ingestion
   (accepted iff it equals the version the DM assigns).
3. **Uniqueness** — the full `$id` is unique across the whole resolvable set
   (every status except ABANDONED/REJECTED — DB-enforced), and the stem is unique
   across Definitions (registry). Titles are therefore unique **within their
   namespace**, not globally.

### 4.2 Reference and expression semantics

- **`$ref` resolution by full `$id`** — version-pinned. A composite referencing
  `…/Iso25000ServiceReliability/v1` validates identically forever. "Floating latest"
  is deliberately inexpressible: adopting a facet's v2 requires re-issuing the
  composite as a new version, making upgrade cascades **explicit in the definitions**
  (definition-centrism honored; audit trail intact). All lookup paths (§2.1) get
  `$id` counterparts — including a new *resolvable-pinned* status set
  (APPROVED-or-later) so pinned refs keep resolving read-only across APPROVED-not-yet-ACTIVE,
  SUSPENDED, and RETIRED, while new-typing selection stays restricted to in-effect; the classpath
  schema mapper is replaced by an exact-match table of the 8 seeded base `$id`s
  (title-group or first-segment keying stays version/namespace-blind).
- **CEL applicability roots** — target: an optional `bindings` map on Norm
  (`{localName: archetypeRef}`) making applicability self-contained and
  version-pinned; assertions are already title-free.
- **Governance chain, port derivation, Starlark names** — the ancestor/descendant
  machinery moves to definition-id strings; `MechanismPortDerivationService`'s
  `Effector`/`Receptor` anchors and rule-declared data-archetype names move to
  `$id`s (rule-local bindings mirror the Norm mechanism); GSM-base detection anchors
  on the full `$id` (`gsmarc://gsm/{Title}/v1`), not the last segment — today
  `gsmarc://acme/x/Structure/v1` is misread as the sealed base.
- **Starlark dispatch** — `sys.receive`/`sys.effect` move from title strings to
  `$id` (or rule-local bindings), removing the last global-title dependency.

### 4.3 What `title` becomes

Not decorative — **the local name of a namespaced identifier** (like a class name in
a package): human-readable, identity-bound by construction, machine-validated against
`$id`, but no longer the global lookup key. The catalogue's framework-prefix rule
remains as a *filename/readability convention* (mount keys, diffs, prose) — valuable,
no longer load-bearing.

## 5. Impact analysis

### 5.1 Current vs target

| Dimension | Current (title-keyed) | Target (`$id`-anchored) |
|---|---|---|
| Namespace scale | one flat global namespace; prefix workaround | unbounded namespaces; titles scoped |
| Version addressing | impossible (order-dependent `findFirst`) | exact (`/v{n}` in every ref) |
| Reproducibility | ref meaning changes when a new version activates | version-pinned refs; frozen composition |
| Coherence | title↔`$id` unchecked at runtime | structurally validated invariant |
| Upgrade semantics | implicit (latest-in-effect wins silently) | explicit re-issue cascade (governed change) |
| Lookup simplicity | one string, one index | URI parsing + namespace-aware index |
| Human ergonomics | short names *if* no prefix needed (in practice prefixes required anyway) | names readable in context; full URIs verbose in refs |
| CEL expressions | terse roots (`GdprProcessingPrinciple.…`) | binding indirection (declare-then-use) |
| Migration cost | zero | 4 repos: defman, gsm-specifications, sie-operator, gsm-frameworks (L5 generalization + composite re-pinning) |

### 5.2 Positive impacts

1. **World-ontology readiness** (PP1 dissolved): frameworks own their namespace;
   sourcing a new standard can never collide with an existing one.
2. **Deterministic version resolution** (PP2, PP3 fixed): every ref names exactly one
   schema forever; concurrent in-effect versions coexist addressably — migration
   windows become safe.
3. **Runtime integrity** (PP4 fixed): the catalogue's L5 invariant becomes a DM
   guarantee; a whole class of silent identity drift becomes impossible.
4. **Governance-visible evolution**: upgrade cascades are re-issued definitions —
   reviewable, approvable, auditable through the ordinary Ascription lifecycle instead
   of happening implicitly at resolution time.
5. **Spec/impl convergence**: the divergent `gsm.puml` intents are resolved — version
   semantics are implemented as written, while `$id` authorship is **amended** to
   producer-claimed/DM-verified (minting is incompatible with statement immutability),
   with a precise reconciliation rule replacing the unimplemented "assigned by DM".

### 5.3 Negative impacts and costs

1. **Four-repo migration**: defman (DDL + ≥6 services + repository + schema mappers),
   gsm-specifications (normative amendment: Archetype identity + version
   materialization, CEL profile, reconciliation; vendored-schema sync +
   `GsmSchemaVendorSyncTest`), sie-operator (dispatch strings; its DTOs carry an
   `int version` the DM currently cannot supply — fixed by version materialization),
   gsm-frameworks (lint L5 generalization to `/v[1-9][0-9]*`, composite re-pinning
   workflow) — staged, but each phase is a real change with review surface. Ripple:
   the workspace `gsm-knowledge` skill (`.github/skills/gsm-knowledge/SKILL.md`)
   still teaches "`$id` assigned by DM" and must be updated with the
   producer-claimed decision.
2. **Version materialization is new machinery, not a re-keying**: the DM has no
   version column today (§2.2); implementing `gsm.puml`'s normative semantics
   (trigger-assigned at APPROVED, `0 = not yet approved`, gapless per Definition)
   across the 8 concrete ascription tables is genuinely new lifecycle behavior
   (spec §2.2). Note: sibling auto-termination at approval removes *lifecycle*
   ordinal races, but the version trigger flushes before sibling termination, so
   DB-level races remain possible and surface as translated constraint violations
   (409); stale re-ingestion under-claims die at CREATE against the resolvable-set
   index, leaving reconciliation as the over-claim backstop at APPROVED (spec §2.3).
3. **CEL grammar extension** (Phase 2): `bindings` is a Norm grammar amendment — new
   field, new validation, documentation, and authoring habit change (declare-then-use
   replaces bare title roots). Existing Norms keep working during transition but must
   eventually migrate.
4. **Verbosity**: full `$id`s in refs and bindings are long. Mitigated by tooling and
   by the fact that statements never carry them (only archetypes and Norm bindings do).
5. **Pinning burden**: version-pinned refs mean facet upgrades do not propagate until
   composites are re-issued. This is the intended governance property, but it *is*
   operational work — the planned catalogue generator (ontology.json → composites)
   must own re-pinning, and it has no owner yet (named open risk).
6. **Dual-mode transition risk**: during Phase 1–2, title-based and `$id`-based
   resolution coexist; the fallback-on-miss rule (spec §2.5) keeps Phase 1
   non-breaking at the cost of a window in which version-claim drift resolves
   leniently (with WARN telemetry as the exit criterion).
7. **Index/query changes**: `uq_archetype_title` is load-bearing for activation-time
   uniqueness; the `$id` index is additive in Phase 1, and the title index is dropped
   only in Phase 3 after the observation window.

### 5.4 Rejected alternatives

| Alternative | Why rejected |
|---|---|
| Keep global titles forever (status quo) | PP1/PP2 are structural, not cosmetic; the prefix rule already concedes that names need namespaces — it just encodes them in the wrong layer |
| Titles fully decorative, identity = opaque `$id` with no title relation | loses the human-readable identity anchor; coherence validation (title ≡ segment) costs nothing and keeps names trustworthy |
| Qualified CEL roots (`iso25000.ServiceReliability.…`) instead of bindings | leaks namespace *structure* into every expression; bindings pin versions too, qualified roots do not; bindings mirror a familiar import idiom |
| Un-pinned (version-less) refs with latest-in-effect resolution | reintroduces PP2 at composition level; validation results become time-dependent — unacceptable for governed definitions |

## 6. Decision

Adopt the target model, staged in three phases (see implementation spec). Phase 1 is
self-contained, fixes PP2/PP3/PP4 without touching titles or CEL, and is the
recommended immediate step. Phases 2–3 complete the demotion of the global title and
are gated on Phase 1 stabilization.
