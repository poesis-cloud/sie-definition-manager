# Implementation Spec — `$id`-Anchored Archetype Identity

> Companion to the design study:
> [identity-model-study.md](../study/identity-model-study.md). Normative for the
> implementation.
> Status: **reviewed — multi-model aligned** (Claude Opus 5, 7 adversarial rounds; GPT-5.6 Sol, 3 adversarial rounds; 2026-08-22), then **revised post-alignment per owner decisions and source-grounded challenge closure** (2026-08-23): pre-production simplification; `ref("$id")`; all external Archetype references by `$id`; stem as identity-bound **and** permanent URI owner (not in-effect purpose/function uniqueness); generic `definitionId` CREATE; generic multiple-proposal convergence; exact Archetype URI resolution; one `$ref` eligibility matrix; table/function stem ownership, not `UNIQUE(stem)`. Parked: [purpose-function-non-uniqueness-study.md](../study/purpose-function-non-uniqueness-study.md), [archetype-static-composite-study.md](../study/archetype-static-composite-study.md). The alignment verdicts attach to the pre-revision text.

## 1. Scope

Three **work packages** across four repositories. The DM is pre-production — no
deployment holds governed data — so the packages are work sequencing (different
repos, different code), **not compatibility staging**: there is no dual-mode, no
fallback, no observation window, and no feature flag anywhere in this spec
(owner decision 2026-08-22; the reviewed pre-revision text carried that machinery
and it was deleted as protecting data that does not exist).

| WP                                | Repo(s)                                                                                                                       | Deliverable                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| --------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **1 — DM core**                   | sie-definition-manager (+ gsm-specifications prose/base schemas, + gsm-ontology prerequisites: L5 §2.10 and Starlark L9 §2.9) | version column + assignment on `PROPOSED → APPROVED`; stem as identity-bound property + **permanent** uniqueness across Definitions (all statuses; owner table + acquisition function, not `UNIQUE(stem)`); candidate Archetype URI at DRAFT/PROPOSED becoming resolvable at APPROVED; `$id` grammar, validation, exact Archetype URI resolution, and the §2.4 eligibility matrix; all external Archetype references by `$id` (§2.7); title demotion (§2.8); Starlark rule-reference validation by `$id` (§2.9) |
| **2 — CEL applicability refs**    | gsm-specifications, sie-definition-manager, gsm-ontology                                                                      | `ref("$id")` applicability profile replacing bare title roots (§3); catalogue expression migration; removal of the last title lookup + `uq_archetype_title`                                                                                                                                                                                                                                                                                                                                                     |
| **3 — Operator identity runtime** | sie-operator                                                                                                                  | `$id`-based frame matching + Starlark dispatch, plus the deterministic CEL `ref()` binding contract, consuming WP1's `version`/`$id` API surface (§4)                                                                                                                                                                                                                                                                                                                                                           |

WP2/WP3 depend on WP1's API surface, not on gates. Out of scope: the catalogue
generator (ontology.json → composites re-pinning) and the separate
Directive/Norm Structure-token migration required for full catalogue ingestion —
both tracked in `gsm-ontology`.

## 2. Phase 1 (WP1) — `$id` handling and version materialization in the DM

### 2.1 Identity grammar (normative)

The reference regex is authoritative; the ABNF is derived from it:

```java
^gsmarc://([a-z0-9]+(?:-[a-z0-9]+)*)/((?:[a-z0-9]+(?:-[a-z0-9]+)*/)*)([A-Z][A-Za-z0-9]*)/v([1-9][0-9]*)$
//         authority      namespace-path      title              version
```

```text
gsmarc-id      = "gsmarc://" authority "/" namespace-path title "/v" version
authority      = loweralnum-seq *("-" loweralnum-seq)   ; no edge/double hyphens
namespace-path = *(segment "/")                  ; zero or more segments, each slash-terminated;
                                                 ; EMPTY for the gsm authority (gsmarc://gsm/Directive/v1)
segment        = loweralnum-seq *("-" loweralnum-seq)     ; same shape as authority
title          = UPPER *(ALPHA / DIGIT)          ; uppercase-leading local token
version        = %x31-39 *DIGIT                  ; positive integer, no leading zero
archetype-stem = gsmarc-id minus "/v" version    ; permanent external identity of one Archetype Definition
loweralnum-seq = 1*(%x61-7A / DIGIT)             ; [a-z0-9]+
UPPER          = %x41-5A                         ; [A-Z]
```

Notes:

- Empirically validated: all **236** distinct `gsmarc://` URIs in
  `gsm-ontology/ontologies/**/*.json` match this regex (repo-wide grep yields 253;
  the extra 17 are prose/placeholder artifacts in markdown). Catalogue convention
  `gsmarc://gsm-ontology/{framework}/{taxonomy}/…` maps to grammar-authority
  `gsm-ontology`, framework = first namespace segment.
- The version production intentionally narrows the legacy `\d+` (rejects `v0`,
  `v007`); the catalogue was verified clean against this production.
- **This grammar replaces `isAllowedRef`/`GSMARC_URI_PATTERN` in WP1** (§2.5) —
  the catalogue is verified conformant (236/236), and there is no deployed data
  authored under the looser legacy pattern.

### 2.2 Version materialization (prerequisite — new machinery)

The DM currently has **no version concept**: the column was dropped in design-phase
cleanup (`V1__gsm_init.sql` note 8) and `AscriptionEntity` has no field; ordering is
derivable only from `findAllByDefinitionIdOrderByTimestampDesc`. `gsm.puml`
(§Ascription) normatively specifies: `version` is a non-negative integer, `0 = not
yet approved`, **assigned atomically by a DB trigger at the APPROVED transition —
never by application code**, monotonically increasing and gapless within the
APPROVED history of each Definition. Phase 1 implements **exactly that
specification**:

- Persistence is `TABLE_PER_CLASS`: there is no `ascription` relation. The column,
  default, trigger, and constraint are replicated across all **8 concrete ascription
  tables** (`archetype`, `structure`, `mechanism`, `effector`, `receptor`,
  `interaction`, `directive`, `norm`), following the existing 6-triggers-per-table
  pattern documented on `AscriptionEntity`.
- `version integer NOT NULL DEFAULT 0` (`0 = not yet approved`, per spec — not
  nullable).
- A per-table **DB trigger** on the APPROVED status transition assigns
  `1 + max(version)` over the same `definition_id` atomically; a
  `UNIQUE (definition_id, version) WHERE version > 0` partial constraint enforces
  uniqueness under concurrency (monotonicity/gaplessness come from the trigger's
  max+1 plus rollback of a race loser, not from the index alone). On each
  `DRAFT → PROPOSED` transition, an Archetype candidate must name this same next
  ordinal (§2.3), but the ordinal is not exclusively reserved: multiple PROPOSED
  siblings are allowed to compete under ordinary approval convergence. The trigger
  remains the sole writer of the persisted `version` fact.
- Ascriptions terminal before APPROVED keep `version = 0` — they never consume an
  ordinal. (The transition machine makes the pre-APPROVED terminal set exactly
  {ABANDONED from DRAFT, REJECTED from PROPOSED}: `ABANDON` is the only edge into
  ABANDONED and it departs from DRAFT.)
- Backfill: none — pre-production, the schema is authored in `V1` (§2.6); there
  are no pre-existing rows to number. The status set `('APPROVED','ACTIVE',
'DEPRECATED','SUSPENDED','RETIRED')` (APPROVED-or-later) is provably exhaustive:
  no post-APPROVED edge leads to ABANDONED or REJECTED.
- `AscriptionEntity` gains a **read-only** mapped `version` field
  (insertable/updatable = false), honoring "never set by application code". The
  database enforces the biconditional used by Archetype URI resolution:
  `version = 0` exactly for DRAFT/PROPOSED/ABANDONED/REJECTED, and `version > 0`
  exactly for APPROVED/ACTIVE/SUSPENDED/DEPRECATED/RETIRED. A direct write cannot
  manufacture either a resolvable candidate or an unversioned post-approval row.

### 2.3 Identity authorship and validation rules

**Subject-type scope (read this before implementing anything below)**: root `$id`,
the identity grammar as self-identity, the candidate claim, URI resolvability, the
stem, and the `ARCHETYPE_*` rules below apply to **Archetype ascriptions ONLY**.
The other 7 subject types (Structure, Mechanism, Effector, Receptor, Interaction,
Directive, Norm) carry no root `$id`, stem, or candidate claim of their own.
Separately, §2.7 applies one external-reference contract wherever an Archetype is
named: the creation envelope for **all 8 types**, Directive/Norm `qualifier`, and
Effector/Receptor `archetype`. Those boundaries carry a resolvable Archetype URI;
the DM resolves and materializes the exact Archetype Ascription UUID FK.

The one thing all 8 types share is the **`version` column** (§2.2): it is a property
of the _Ascription_ (the governance act), assigned identically by the trigger for
every subject type. The asymmetry is exposure, not semantics — only Archetypes
_surface_ the ordinal in an external identity, because only Archetypes circulate
detached from their ascription envelope (catalogue files, `$ref` targets, third-party
validation) and therefore must self-identify. Consumers of any type read the version
from the ascription record (`AscriptionDto.version`), never from the statement.

| Concern                                                                                                 | Archetype ascriptions                                                                                                                                                                                                                                                   | Other 7 subject types                                                                                                                                                                                                              |
| ------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `version` column + trigger + `(definition_id, version)` uniqueness                                      | yes                                                                                                                                                                                                                                                                     | yes                                                                                                                                                                                                                                |
| `version` in `AscriptionDto` / REST                                                                     | yes                                                                                                                                                                                                                                                                     | yes                                                                                                                                                                                                                                |
| `$id` in statement (mandatory, grammar-checked)                                                         | yes                                                                                                                                                                                                                                                                     | **no** — statements carry no `$id`                                                                                                                                                                                                 |
| candidate version check on `DRAFT → PROPOSED` + APPROVED URI resolvability                              | yes                                                                                                                                                                                                                                                                     | **no** — the ordinal is fact-only                                                                                                                                                                                                  |
| identity-bound property                                                                                 | derived stem from `$id`                                                                                                                                                                                                                                                 | `purpose` / `function` / existing handler fields                                                                                                                                                                                   |
| uniqueness across Definitions                                                                           | **permanent** stem owner (all statuses, one `definition_id`)                                                                                                                                                                                                            | existing **in-effect** uniqueness (purpose global; function per-Structure) — different kind; parked if dropped                                                                                                                     |
| `definitionId` on CREATE                                                                                | **same generic contract**                                                                                                                                                                                                                                               | omit → new Definition; supply → attach                                                                                                                                                                                             |
| typing Archetype (`AscriptionCreationDto.archetype` write; `AscriptionDto.archetype` + HAL `type` read) | Archetype URI on write and read; UUID `archetype_id` FK internally. `AscriptionDto.version` is the **subject Ascription** version; `AscriptionDto.archetype` is the exact pinned **typing-Archetype** `$id`. HAL `type` remains the typing Archetype Definition locator | **same write/read contract** — every ascription is typed, including an Archetype ascription. Identical cells are intentional, not inverted                                                                                         |
| qualifier / data-Archetype **statement** fields                                                         | **n/a** — an Archetype statement has neither `qualifier` nor port `archetype`                                                                                                                                                                                           | write and stored statement carry the Archetype URI; same resolve → UUID FK as today (`qualifier_id`, `output_archetype_id`, `input_archetype_id`). Read DTO is the statement as stored, so those fields come back as `$id` strings |
| extra identity registry / `definition.external_identity`                                                | **no**                                                                                                                                                                                                                                                                  | **no**                                                                                                                                                                                                                             |
| `ARCHETYPE_*` rules below                                                                               | yes                                                                                                                                                                                                                                                                     | **no**                                                                                                                                                                                                                             |

Implementors: do not generalize any `ARCHETYPE_*` rule to `AbstractAscriptionService`
— they belong to `ArchetypeService` / the archetype parse path exclusively.

**Authorship decision (amends `gsm.puml`)**: `$id` is **producer-authored and
DM-governed**, never filled into the immutable statement later. During DRAFT and
PROPOSED it is a candidate Archetype URI, not yet a resolvable lookup key. On
`DRAFT → PROPOSED` (the transition named `SUBMIT`), the DM verifies that the suffix
is the next governance ordinal. This is validation, not reservation: multiple
PROPOSED siblings may carry the same candidate `$id`, exactly as generic GSM permits.
On `PROPOSED → APPROVED`, generic approval convergence selects one winner and
terminates its DRAFT/PROPOSED siblings; the DB assigns `Ascription.version`, and the
DM defensively verifies equality with the candidate suffix after flush. `gsm.puml`'s
unqualified "`$id` assigned by DM" is amended to express this split of
responsibilities.

The stable internal identity remains the Definition UUID. The **stem** (`$id` minus
`/v{n}`) is two contracts, not one:

- **Identity-bound (same Definition):** every Ascription of one Definition carries
  the same stem. Same _kind_ of check as `purpose` / `function`. Keep even if
  those later lose uniqueness (parked study).
- **Uniqueness _scope_ is not the purpose/function helper.** Purpose is global
  among in-effect Structures; function is unique among in-effect Mechanisms of
  one Structure. Stem uniqueness is **permanent URI ownership**: one Definition
  owns the stem forever, including ABANDONED/REJECTED history. Another
  Definition cannot republish the family.

CREATE stays generic for all 8 types (`DefinitionService.resolve(definitionId,
type)`). No registry, no `definition.external_identity`, no authority policy, no
Archetype CREATE exception. The stem is not a second identity store.

Rule ids follow the existing enum grammar (`SUBTYPE_PROPERTY_CONSTRAINT` in
`AscriptionConsistencyRuleType`; transition ids in
`AscriptionStatusTransitionRuleType`). Each id is a **constraint that must
hold**, not a description of the forbidden act. HTTP codes are the
`AbstractController` mapping the client actually receives: consistency /
parse / reference → **400**; uniqueness / identity-bound / every status
transition → **409**. No 422.

**Not a new rule — GSM-base detector.** Rename `isGsmBaseTitle` to
`isGsmBaseId`; it is a helper, not a `RuleType`. After WP1 it matches the eight seed `$id`s
(`gsmarc://gsm/Structure/v1`, …), not the last path segment. Client-visible
failures stay on the existing ids: `ARCHETYPE_ALLOF_EXCLUSIVE_BASE_CONVERGENCE`,
`ARCHETYPE_ALLOF_NON_SEALED`, `ASCRIPTION_ARCHETYPE_BASED_ON_GSM_ARCHETYPE`.
This re-key applies to **every** base-classification path: the composition walker,
`ArchetypeService.resolveSubjectType` (which must not call
`DefinitionSubjectType.fromArchetypeTitle` until an exact seed `$id` has been
recognized), ancestry traversal, and both NetworkNT schema mappers. A tenant URI
ending in `/Structure/v1` is never a GSM seed.

**Not a new rule — leftover siblings.** Same-Definition DRAFT/PROPOSED
siblings of an APPROVED winner **cannot** reach APPROVED or ACTIVE:
terminals are immutable
(`ASCRIPTION_STATUS_TRANSITION_TERMINAL_IMMUTABILITY`). They are already
terminated in the same `APPROVE` transaction by generic
`ASCRIPTION_STATUS_TRANSITION_APPROVAL_CONVERGENCE`
(`handleApproval`: other DRAFT → ABANDONED, other PROPOSED → REJECTED,
`version` stays 0). No extra Archetype terminator. `uq_archetype_resolvable_uri`
is only a race/corruption guard if a second row would still receive
`version > 0`.

Each new value below is a complete public `RuleType` contract. Its enum constant is
the RFC 9457 `rule` extension; `type` is the stable URI shown below; title is the
sentence-case constant name; description is the normative Constraint cell. Add
every consistency value to `AbstractController.mapRuleTypeToHttpStatus`; add every
transition value to `AscriptionStatusTransitionRuleType` (the existing family map
already returns 409).

| Rule id / enum home                                                          | Stable `type` URI                                                         | Constraint                                                                                                                                                                                                                                   | Client use case                        | HTTP                       |
| ---------------------------------------------------------------------------- | ------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------- | -------------------------- |
| `ARCHETYPE_ID_GRAMMAR` / consistency                                         | `gsm:rules/archetype/id/grammar`                                          | root statement `$id` is present and matches the identity grammar                                                                                                                                                                             | `POST` ascription CREATE (parse)       | 400, field `$id`           |
| `ARCHETYPE_ID_TITLE_COHERENCE` / consistency                                 | `gsm:rules/archetype/id/title-coherence`                                  | `$id` title segment ≡ statement `title` (exact, case-sensitive)                                                                                                                                                                              | `POST` CREATE (parse)                  | 400, fields `$id`, `title` |
| _(reuse)_ `ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION`                  | existing URI                                                              | `ArchetypeService.getIdentityBoundValues` returns derived `stem` + `title`; the generic validator compares that normalized map. Same Definition, different stem is this existing rule. Do **not** add `ARCHETYPE_ID_DEFINITION_IMMUTABILITY` | `POST` CREATE attaching `definitionId` | 409                        |
| `ARCHETYPE_STEM_UNIQUENESS_ACROSS_DEFINITIONS` / consistency                 | `gsm:rules/archetype/stem/uniqueness-across-definitions`                  | one owning Definition forever, **all statuses**. Same stem + same Definition is required. DB: `archetype_stem_owner`, not `UNIQUE(archetype stem)`                                                                                           | `POST` CREATE                          | 409                        |
| `ASCRIPTION_ARCHETYPE_IN_EFFECT` / consistency                               | `gsm:rules/ascription/archetype/in-effect`                                | a typing Archetype is ACTIVE or DEPRECATED; this is a new WP1 rule replacing current DRAFT-permissive UUID resolution                                                                                                                        | `POST` CREATE                          | 400, field `archetype`     |
| `ASCRIPTION_STATUS_TRANSITION_ARCHETYPE_CANDIDATE_VERSION` / transition      | `gsm:rules/ascription/status-transition/archetype-candidate-version`      | on `DRAFT → PROPOSED`, candidate `/v{n}` equals `1 + max(version)` for that Definition; it does not reserve the suffix                                                                                                                       | `POST` SUBMIT                          | 409                        |
| `ASCRIPTION_STATUS_TRANSITION_ARCHETYPE_VERSION_RECONCILIATION` / transition | `gsm:rules/ascription/status-transition/archetype-version-reconciliation` | on `PROPOSED → APPROVED`, refresh the winner after flush; trigger-assigned `version` equals the candidate suffix. Sibling termination is not this rule                                                                                       | `POST` APPROVE                         | 409                        |
| `ASCRIPTION_STATUS_TRANSITION_VERSION_UNIQUENESS` / transition               | `gsm:rules/ascription/status-transition/version-uniqueness`               | one positive version per Definition; translates all eight `uq_{table}_definition_version` constraints                                                                                                                                        | concurrent `POST` APPROVE              | 409                        |
| `ARCHETYPE_URI_RESOLUTION_UNIQUENESS` / consistency                          | `gsm:rules/archetype/uri/resolution-uniqueness`                           | an Archetype URI is resolvable by at most one row with `version > 0`; candidates may duplicate                                                                                                                                               | concurrent `POST` APPROVE / DB guard   | 409                        |
| `ARCHETYPE_ID_ROOT_EXCLUSIVITY` / consistency                                | `gsm:rules/archetype/id/root-exclusivity`                                 | `$id` appears only on the statement root; data-valued annotations are ignored                                                                                                                                                                | `POST` CREATE (parse)                  | 400, JSON Pointer          |
| `ARCHETYPE_REF_INTEGRITY` / consistency                                      | `gsm:rules/archetype/ref/integrity`                                       | every grammar-valid `gsmarc://` `$ref` resolves to one registered Archetype. `ARCHETYPE_REF_NORM` remains URI-form validation                                                                                                                | `POST` CREATE (parse)                  | 400, field `$ref`          |
| `ARCHETYPE_ALLOF_PROPERTY_DISJOINTNESS` / consistency                        | `gsm:rules/archetype/allof/property-disjointness`                         | sibling `allOf` facets have disjoint resolved top-level property names                                                                                                                                                                      | `POST` CREATE (parse)                  | 400, field `allOf`         |
| `ARCHETYPE_ALLOF_PROPERTY_TYPE_STABILITY` / consistency                      | `gsm:rules/archetype/allof/property-type-stability`                       | a host does not redeclare an inherited property with a different explicit JSON Schema type set                                                                                                                                               | `POST` CREATE (parse)                  | 400, property pointer      |

Implementation home: `ArchetypeParsingService` (grammar + component extraction),
`AscriptionParsingValidationService` (structure + coherence at parse),
`AscriptionService.create` (generic `definitionId` resolve for all 8 types),
`ArchetypeService.getIdentityBoundValues` (stem + title),
`AscriptionIdentityBoundValidationService` (same-Definition integrity),
`ArchetypeService.validateCreationUniqueness` (permanent stem owner check at
CREATE — **all statuses**, not in-effect-only),
`AscriptionStatusTransitionService` (candidate-version
check, Definition lock, post-flush refresh + reconciliation), DB trigger (version assignment,
§2.2), `archetype_stem_owner` (§2.6), `ArchetypeService` (external `$ref`
Referees, base detection),
`ArchetypeCompositionValidationService` (schema-position walker, nested `$id`,
sibling disjointness, inherited type stability),
`PersistenceExceptionTranslationService` (URI-resolution and 8 Definition-version
constraints). Stem-owner conflicts are returned by the acquisition function and
mapped explicitly by `ArchetypeService`, not inferred from a SQL constraint name.

Concurrency contract: permanent stem ownership is acquired through
`gsm_acquire_archetype_stem_owner(stem, definition_id)`. The function takes a
stem-scoped transaction advisory lock, inserts the first owner, and on conflict
performs a non-transferring update (`definition_id = archetype_stem_owner.definition_id`)
that returns the existing `definition_id`; a returned different Definition fails
`ARCHETYPE_STEM_UNIQUENESS_ACROSS_DEFINITIONS`. The stem primary key is the final
guard even when application locking is bypassed. Archetype CREATE additionally
takes the lock through this function before ownership comparison. Bootstrap seeds
use the same function immediately after each Archetype insert in the same transaction;
rollback therefore leaves neither the Archetype nor an orphan owner claim.
SUBMIT and APPROVE for **all eight subject types** take a transaction-scoped lock
keyed by `definition_id`; Archetype transitions do not substitute a stem lock for
the Definition lock. Use disjoint key namespaces and `pg_advisory_xact_lock`
(stable hash of `"stem:" + stem` or `"definition:" + UUID`), released by commit /
rollback. After acquiring a transition lock, reload the subject row and its latest
status before validation; then read `max(version)` and siblings. This prevents a
request that waited behind a winning approval from acting on a stale PROPOSED row.
The entire winner transition, trigger assignment, explicit
`EntityManager.refresh(winner)`, reconciliation, and sibling convergence stays in
the existing `AscriptionStatusTransitionService` transaction. Multiple candidates
remain legal; the DB indexes/triggers are final guards. Batch paths acquire lock
keys in unsigned numeric order.

**Phase-1 lifecycle amendment (gsm-specifications)**: every `gsmarc://` `$ref`
in an Archetype statement (extend or facet) becomes an Archetype Referee. Amend
`gsm-ascription-lifecycle.puml` where it currently says Archetype is not a
Referee, without adding a status, transition, allowed-status exception, or
Cascade. Local `#…` fragments are not Referees. Reference discovery and Cascade
declaration remain separate subtype contracts. This is deliberate static-dependency
semantics: once an exact immutable resolved schema is admitted, a later
SUSPEND/DEPRECATE/RETIRE of that target does not invalidate or transition existing
dependants. Target status constrains new dependant transitions only; no reverse
cascade is added.

**Phase-1 meta-schema amendment (gsm-specifications) — prose only, deliberately**:
`Archetype.schema.json` recursively applies at **every schema node**, not only the
statement root (`$dynamicAnchor: "meta"` + the 2020-12 meta-schema's subschema
positions recursing via `$dynamicRef: "#meta"`; normative per GSM-PROC-13). Adding
root-level `required: ["$id", "title"]` or constrained `properties.$id`/`.title`
would therefore demand them on every _nested_ subschema of every archetype —
breaking all 92 catalogue archetypes and the 8 seeds. Root-only enforcement cannot be
expressed by adding root keywords to a dynamically-recursive meta-schema; mandatory
root `$id`/`title` is enforced by `ARCHETYPE_ID_GRAMMAR`, while
`ARCHETYPE_ID_ROOT_EXCLUSIVITY` rejects an `$id` at every non-root JSON Pointer.
Deliberate portability boundary: a third party validating a published
archetype against `Archetype.schema.json` alone gets no `$id` obligation — the
portable channel for that requirement is GSM-PROC conformance, not the meta-schema.
**Cannot put the identity grammar on `Archetype.schema.json` as a root
`properties.$id.pattern`.** The meta-schema is dynamically recursive
(`$dynamicAnchor: "meta"`): a root `properties.$id` / `required: ["$id"]` would
demand `$id` on every nested subschema. Root-only `$id` stays an application
rule (`ARCHETYPE_ID_GRAMMAR`). Directive/Norm/Effector/Receptor **reference
fields** (`qualifier`, port `archetype`) are ordinary string properties of those
subject schemas — those **can** take the §2.1 pattern. The meta-schema amendment is scoped to its `description` prose: remove "DM
assigns `$id` … `gsmarc://gsm/{title}/v{version}`" (contradicts producer-claimed AND
hard-codes the `gsm` authority), reword "title … globally unique" to defer to the
identity grammar, and remove "title is used as the root identifier in Norm CEL
applicability expressions" (false once WP2 lands). While amending: the
`Norm.schema.json` assertion examples AND the `assertion` property's own description
("references exactly one Archetype by name as root identifier") use title-rooted
forms the assertion validator rejects — correct both, plus the same stale assertion
contract in `specification.md` §14.1.2 and its canonical-interchange example.
`conformance.md`: `GSM-PROC-10`
is amended with the identity work; **`GSM-PROC-11` is amended in the same combined
WP1/WP2 delivery** because title-based applicability and `uq_archetype_title` are
removed together (§2.5). `GSM-PROC-4` says "monotonic per Definition"
— §2.2 implements monotonic **and gapless**, a deliberate strengthening to record in
`specification.md` §6.2/§12.3 and the same conformance pass so the catalogue does not
drift the other way.

#### Shared Draft 2020-12 schema-position walker

`ARCHETYPE_ID_ROOT_EXCLUSIVITY`, reference discovery, and composition validation
use one walker over **schema-valued positions**, not a generic recursive JSON scan.
It visits schema objects reached through Draft 2020-12 keywords: direct-schema
`additionalProperties`, `additionalItems`, `contains`, `contentSchema`, `else`,
`if`, `items`, `not`, `propertyNames`, `then`, `unevaluatedItems`, and
`unevaluatedProperties`; array-of-schema `allOf`, `anyOf`, `oneOf`, and
`prefixItems`; and map-of-schema `$defs`, `definitions`, `dependentSchemas`,
`patternProperties`, and `properties`. Boolean schemas are terminal schemas. It
does not descend through data-valued keywords such as `const`, `default`, `enum`,
`examples`, or annotation extension payloads.

At each visited schema object the walker reports its JSON Pointer and handles `$id`
and `$ref`; authored `$dynamicRef` is rejected as specified in §2.5. A local `#`
`$ref` resolves within the current document using Draft 2020-12 base-URI rules and
is not a lifecycle Referee. A resolvable `gsmarc://` `$ref` resolves through
`findResolvableByUri`; no other external URI scheme is admitted. Cycle keys
are the exact Archetype URI for external nodes and
`(document $id, canonical JSON Pointer)` for local nodes.

#### Resolved-property composition algorithm

The two `allOf` rules are conservative and deterministic:

1. Resolve each facet as an inline schema, local `$ref`, or exact resolvable
   `gsmarc://` `$ref`; traverse transitive `allOf` ancestry with the shared cycle
   keys.
2. A facet's effective top-level properties are its direct `properties` plus all
   transitively inherited effective properties. Nested object properties are not
   promoted.
3. `ARCHETYPE_ALLOF_PROPERTY_DISJOINTNESS` compares every sibling facet's effective
   property-name set. Any intersection fails, including a transitive duplicate. A
   mount-style wrapper remains disjoint because its mounted name is its only
   top-level property.
4. Union the disjoint inherited maps, then compare a direct host redeclaration with
   its inherited property for `ARCHETYPE_ALLOF_PROPERTY_TYPE_STABILITY`. Normalize
   scalar `type` to a singleton set and array `type` to a set. Equal sets pass;
   different sets fail. If either side omits `type`, do not infer one from `const`,
   `enum`, applicators, or vocabularies, so this rule does not reject. Boolean
   schemas likewise have no explicit type set.
5. Same-type narrowing is allowed. The algorithm is not a general satisfiability
   proof and does not synthesize a merged schema.

### 2.4 Archetype URI resolution and lifecycle eligibility

Identity lookup and lifecycle eligibility are separate concerns:

- **CREATE attachment** uses the generic `definitionId` contract;
- **stem uniqueness** is permanent URI ownership (one `definition_id` per stem),
  not a lookup API and not in-effect uniqueness;
- **Archetype URI lookup** resolves one governed Archetype Ascription by exact string,
  independent of its current post-approval status;
- **operation eligibility** is checked after resolution by the owning operation.
  The four surfaces below are **not** the same set — implement and test this
  matrix, do not invent a fifth “uniform” set.

| Use                                                     | Identity lookup                       | Eligibility after lookup                                                        |
| ------------------------------------------------------- | ------------------------------------- | ------------------------------------------------------------------------------- |
| Archetype statement CREATE                              | generic `definitionId` resolve        | same-Definition stem identity-bound; other-Definition stem owner (all statuses) |
| resolvable Archetype URI read                           | `findResolvableByUri` (`version > 0`) | none — APPROVED, ACTIVE, DEPRECATED, SUSPENDED, RETIRED remain readable         |
| typing Archetype in `AscriptionCreationDto`             | resolvable Archetype URI              | new WP1 typing policy (`ACTIVE` or `DEPRECATED`)                                |
| CREATE / SUBMIT `$ref` and qualifier/data-role referees | resolvable Archetype URI              | `{APPROVED, ACTIVE}` at both operations                                         |
| APPROVE `$ref` and qualifier/data-role referees         | resolvable Archetype URI              | `{APPROVED, ACTIVE}`                                                            |
| ACTIVATE `$ref` and qualifier/data-role referees        | resolvable Archetype URI              | `{ACTIVE}`                                                                      |

WP1 replaces UUID-based typing resolution with exact Archetype URI resolution and
the `ASCRIPTION_ARCHETYPE_IN_EFFECT` policy:
APPROVED-but-not-ACTIVE cannot type a new Ascription; a tenant Archetype becomes a
usable type only once in effect. This does not narrow Archetype URI read access.

The generic state-machine maxima remain CREATE
`{DRAFT, PROPOSED, APPROVED, ACTIVE}` and SUBMIT
`{PROPOSED, APPROVED, ACTIVE}` for UUID-addressable Referees. They are **not** the
effective sets for Archetype-valued public fields: those fields resolve Archetype
URIs first, so DRAFT and PROPOSED are unreachable by construction. Do not add a
candidate resolver to make the broader generic sets reachable. A lifecycle mismatch
after successful resolution uses the existing status-transition Referee rule and
409; malformed or unresolved identities use the consistency rules and 400.

WP1 does **not** widen APPROVE/ACTIVATE to DEPRECATED so a new composite can pin a
historical type. That tension is documented; a later matrix edit belongs in
[archetype-static-composite-study.md](../study/archetype-static-composite-study.md).

An Archetype URI is resolvable when its Ascription has `version > 0`; this set is
exactly the APPROVED-or-later history. DRAFT and PROPOSED `$id`s are candidate
statement values, may be duplicated by competing siblings, and are never returned
by URI resolution. An authorized direct-by-UUID read may expose a candidate inside
its statement, but that does not make it a referenceable identity.

Resolution repository query shape:

```java
@Query(nativeQuery = true, value = """
    SELECT * FROM archetype
    WHERE statement->>'$id' = :uri
      AND version > 0
    """)
Optional<ArchetypeEntity> findResolvableByUri(@Param("uri") String uri);
```

`uq_archetype_resolvable_uri` (§2.6) makes the `Optional` cardinality a DB invariant.
Stem ownership is **not** a resolver and is **not** `UNIQUE(stem)`: `/v1` and `/v2`
of the same Definition share the stem string. See
`gsm_acquire_archetype_stem_owner` in §2.6.

After exact resolution, schema traversal, governance ancestry, and
`isDescendantOf` retain the resolved row. They do not select a newer version.
Transition readiness comes from the eligibility matrix above, not from resolver
modes.

Additionally the networknt `schemaMappers` in `AscriptionParsingValidationService`
(two sites) currently map `gsmarc://gsm/…` to
`classpath:gsm/schemas/{firstSegmentAfterAuthority}.schema.json` — correct only
because the `gsm` authority has an empty namespace path, and version/namespace
blind (`gsmarc://gsm/Structure/v99` or `gsmarc://gsm/x/Structure/v1` would resolve
the bundled v1). Replace both with an **exact-match table of the 8 seeded base
`$id`s** → classpath resources; any other `gsm`-authority ref is an error, not a
fuzzy match.

**REST/DTO surface (Phase 1)**: `AscriptionCreationDto.UUID archetypeId` is replaced
by required `String archetypeUri`, containing the resolvable Archetype URI of an eligible
typing Archetype. The create path validates the grammar, resolves with
`findResolvableByUri`, applies the existing typing-status policy, and
persists the resolved Archetype Ascription UUID in the unchanged `archetype_id` FK.
`AscriptionDto` (+ mapper + the hand-built OpenAPI response schema in
`AscriptionController`) adds required `int version`. `version` is the returned subject
Ascription's version. The HAL `type` link continues to identify that typing Archetype's
Definition. No Archetype identity body field or second link to the exact pinned typing
Ascription is emitted. For an Archetype subject, its
own Archetype URI — candidate or resolvable — remains in `statement.$id`: a direct-by-UUID read of a
DRAFT/PROPOSED row exposes the candidate there but never through URI resolution.
The list endpoint uses exact `archetypeUri` filtering for the typing Archetype and retains
generic statement filters. Archetype subjects may combine the system-owned root filters
`statement.$id` and `statement.title` without a typing selector; additional statement properties
remain gated by `$gsm:queryable` and require `archetypeUri`. This supplies
sie-operator's `ArchetypeAscriptionDto.version` and removes the internal UUID from
the public typing contract.

### 2.5 Exact Archetype URI keys only (no title fallback)

Archetype identity, `gsmarc://` `$ref`s, and the §2.7 authoring-boundary
references are `$id`-only from day one — no title fallback, no WARN telemetry,
no transition invariant. Local `#…` fragments stay intra-schema. Pre-production,
nothing existing can break; the break lands only on in-flight integrations, which
adapt alongside (§2.6).

1. Every archetype `$ref` MUST match the identity grammar: `isAllowedRef` /
   `GSMARC_URI_PATTERN` (`ARCHETYPE_REF_NORM`) is tightened to the §2.1 grammar
   directly (catalogue verified conformant, 236/236).
2. A grammar-conformant ref that does not resolve is a **hard validation error**
   (the existing unresolvable-ref rule), naming the requested `$id`. No last-segment
   title fallback exists.
3. Authoring is likewise `$id`-mandatory (`ARCHETYPE_ID_GRAMMAR`): the DM stops
   accepting `$id`-less archetype statements, full stop.

Authored external `$dynamicRef` is forbidden in tenant Archetypes under
`ARCHETYPE_REF_NORM`, whether it contains `gsmarc://`, another URI, or a local
fragment. The sole permitted `$dynamicRef` is the internal
`Archetype.schema.json` meta-schema recursion `$dynamicRef: "#meta"`, which is a
validator resource and never an authored statement node. This avoids a second
runtime resolution model whose dynamic-anchor semantics cannot be reduced safely to
the exact pinned Referee contract. `$ref` remains the only authored reference
keyword.

WP1 and WP2 are delivered together: CEL applicability uses exact `ref("$id")`, so
no title-based resolution remains and `uq_archetype_title` is removed from `V1` in
the same pre-production in-place edit (§2.6). Title remains display metadata and an
explicit list filter only; it is never an identity, typing, composition, governance
ancestry, or applicability lookup key.

### 2.6 Storage

**No new migration. Edit `V1__gsm_init.sql` in place.** The DM is pre-production —
no deployment holds governed data, and the few integrations under way are adapted
alongside this change. A `V2` carrying an `ALTER TABLE` + backfill + preflight guard
would be pure ceremony: machinery to migrate data that does not exist, permanently
frozen into the schema history of a system that never had a prior state. The same
applies to the WP2 removal of `uq_archetype_title` — a `V1` in-place edit, not a
`V3`.

Consequently **no backfill, no preflight assert-guard, no `$id` data check** appears
anywhere: every object below is authored as part of initial schema creation, so
there are never pre-existing rows to number, validate, or repair. (Reinstate a
versioned migration the moment the first environment holds data you cannot drop —
that is the only trigger for reverting this decision.)

Objects added to `V1`, per concrete ascription table `t` (TABLE_PER_CLASS: 8 tables —
`archetype`, `structure`, `mechanism`, `effector`, `receptor`, `interaction`,
`directive`, `norm`; there is no `ascription` relation):

```sql
-- 1. version materialization (§2.2) — ×8 tables, inline in each CREATE TABLE
version integer NOT NULL DEFAULT 0,           -- 0 = not yet approved
...
CREATE UNIQUE INDEX uq_t_definition_version ON t (definition_id, version)
  WHERE version > 0;
ALTER TABLE t ADD CONSTRAINT ck_t_status_version
  CHECK (
    (status IN ('DRAFT','PROPOSED','ABANDONED','REJECTED') AND version = 0)
    OR
    (status IN ('APPROVED','ACTIVE','SUSPENDED','DEPRECATED','RETIRED') AND version > 0)
  );
-- trigger trg_t_version_materialization / tgf_materialize_ascription_version:
--   BEFORE UPDATE (OLD is legal; the seed runner's INSERT never fires it)
--   IF OLD.status <> 'APPROVED' AND NEW.status = 'APPROVED' THEN
--     NEW.version := 1 + max(version) over NEW.definition_id; -- overwrite input
--   ELSIF NEW.version <> OLD.version THEN
--     RAISE integrity_constraint_violation with
--       CONSTRAINT = 'ck_t_version_writer';
--   END IF
-- The existing direct-status-update guard still rejects callers that bypass the
-- transition ledger. This function is the sole non-seed writer of version.
-- (same install pattern as the existing 6 per-table triggers; no runner
--  disable-list extension needed)
-- 2. Stem owner. NOT UNIQUE(archetype stem): /v1 and /v2 of the same Definition
--    share the stem. A separate relation supplies one atomic owner row per stem.
CREATE TABLE archetype_stem_owner (
  stem text PRIMARY KEY,
  definition_id uuid NOT NULL REFERENCES definition(id) ON DELETE RESTRICT
);
CREATE INDEX ix_archetype_stem
  ON archetype ((regexp_replace(statement->>'$id', '/v[1-9][0-9]*$', '')));
CREATE OR REPLACE FUNCTION gsm_acquire_archetype_stem_owner(
  p_stem text,
  p_definition_id uuid
) RETURNS uuid AS $$
-- Take pg_advisory_xact_lock(hashtextextended('stem:' || p_stem, 0)), then:
-- INSERT ... ON CONFLICT (stem) DO UPDATE
--   SET definition_id = archetype_stem_owner.definition_id
-- RETURNING definition_id.
$$ LANGUAGE plpgsql;
-- Archetype CREATE and seed bootstrap acquire/read the owner atomically by stem.
-- A returned conflicting Definition is translated to 409
-- ARCHETYPE_STEM_UNIQUENESS_ACROSS_DEFINITIONS. Same-Definition versions reuse
-- the row. A BEFORE UPDATE/DELETE trigger on archetype_stem_owner rejects key
-- changes, owner transfer, and deletion while permitting the function's no-op
-- UPSERT. Do not add uq_archetype_stem or an Archetype-write ownership trigger.

-- 3. Resolution uniqueness of the exact Archetype URI (archetype table only).
--    Candidates have version = 0 and may duplicate; an APPROVED-or-later URI
--    resolves permanently.
CREATE UNIQUE INDEX uq_archetype_resolvable_uri
  ON archetype ((statement->>'$id'))
  WHERE version > 0;
CREATE INDEX ix_archetype_id ON archetype ((statement->>'$id'));
```

Stable database-site names are part of error translation, not incidental DDL:

| DB site                                                                                      | Rule mapping                                      | HTTP                                                        |
| -------------------------------------------------------------------------------------------- | ------------------------------------------------- | ----------------------------------------------------------- |
| `uq_{table}_definition_version` (8)                                                          | `ASCRIPTION_STATUS_TRANSITION_VERSION_UNIQUENESS` | 409                                                         |
| acquisition function returns another owner `definition_id`                                   | `ARCHETYPE_STEM_UNIQUENESS_ACROSS_DEFINITIONS`    | 409                                                         |
| `uq_archetype_resolvable_uri`                                                                | `ARCHETYPE_URI_RESOLUTION_UNIQUENESS`             | 409                                                         |
| `ck_{table}_status_version` (8)                                                              | internal integrity failure                        | 500; no client action can repair trigger/status incoherence |
| `trg_{table}_version_materialization` raised with `CONSTRAINT = 'ck_{table}_version_writer'` | internal integrity failure                        | 500                                                         |

`PersistenceExceptionTranslationService` returns the shared `RuleType` interface,
not only `AscriptionConsistencyRuleType`, and matches PostgreSQL constraint names from
the server error (`ServerErrorMessage.constraint`), including trigger-raised names;
SQLSTATE alone is insufficient because all uniqueness sites share `23505`. Existing
`uq_{table}_approved` races map to
`ASCRIPTION_STATUS_TRANSITION_APPROVAL_CONVERGENCE`; new Definition-version races
map to `ASCRIPTION_STATUS_TRANSITION_VERSION_UNIQUENESS`. Tests
prove every named user-reachable guard becomes its declared 409 rather than the
generic 500 path. Coherence/writer violations remain 500 because reaching them
means application/trigger bypass or corruption, not an authoring conflict.

The `ascription_all` union projection gains the `version` column. The grammar's
unbounded version production is bounded operationally: the DM rejects claims
exceeding the `integer` range at parse (no `bigint` — a Definition approaching 2^31
versions is not a realistic governance object).

**Seed-runner interaction**: the 8 GSM base seeds are inserted directly as ACTIVE
with status-sync triggers disabled, so the APPROVED trigger never fires for them
(its `WHEN` clause is UPDATE-only). The seed insert therefore creates one Definition
per seed and sets `version = 1` explicitly on the seeded Archetype. Two known
limitations are accepted and documented: (a) the
runner's global skip-if-any-exists means amended base schemas (e.g. the §2.3
meta-schema amendment) do NOT reach already-initialized databases via seeding —
base-schema evolution needs the normal governance path, named as a Phase-2 work
item; (b) the runner's title-keyed load map is single-version by construction —
acceptable until a base reaches v2, at which point (a)'s mechanism applies anyway.

No statement is ever mutated: `$id` is producer-claimed (§2.3), never written by
the DM or by schema management.

### 2.7 Archetype references at authoring boundaries — `$id` outside, exact UUID FK inside

One identity model applies to all three Archetype roles. Public authors must not
know row UUIDs, while the relational model must retain exact referential integrity:

| Surface                                                  | External value                     | Resolution at creation                            | Materialized FK                                                     |
| -------------------------------------------------------- | ---------------------------------- | ------------------------------------------------- | ------------------------------------------------------------------- |
| typing Archetype for every `AscriptionCreationDto`       | `String archetype` = Archetype URI | exact URI resolver + typing-status policy         | `archetype_id` → exact `archetype.id`                               |
| Directive/Norm `statement.qualifier`                     | Archetype URI                      | exact URI resolver + generic CREATE Referee check | `qualifier_id` → exact `archetype.id`                               |
| Effector/Receptor `statement.archetype` (port data type) | Archetype URI                      | exact URI resolver + generic CREATE Referee check | `output_archetype_id` / `input_archetype_id` → exact `archetype.id` |

`Directive.schema.json`, `Norm.schema.json`, `Effector.schema.json`, and
`Receptor.schema.json` replace `format: uuid` on those Archetype-reference fields
with the §2.1 identity pattern and update examples/descriptions. **Same
implementation path as today:** `DirectiveService` / `NormService` already
`extractRequiredUuid("qualifier")` then `archetypeService.findEntityById` and
store `qualifier_id`. WP1 only changes the extracted token from UUID to `$id`
and the lookup to `findResolvableByUri`. Effector/Receptor port
`archetype` is the same seam. Other UUID references (`structure`, `mechanism`,
`purpose`, `effector`, `receptor`) are not Archetype identities and remain
unchanged. The known catalogue use of vocabulary tokens in Directive/Norm
`structure` fields is a separate migration blocker; this spec neither legitimizes
nor fixes it.

All three surfaces preserve the exact selected version through the existing FK to
the resolved Archetype Ascription row. Cross-version **identity-bound equality** is
different: it compares the referenced row's `Definition.id` (equivalently, its
stem), not raw `$id` text or the exact Ascription UUID. A later Ascription
may therefore re-pin `/v1` to `/v2` of the same Archetype Definition, but cannot
switch to another Definition.

Candidates cannot create stale governed references because exact `$id` resolution
excludes `version = 0`. For Referee-valued surfaces, the generic CREATE and
transition checks then enforce lifecycle eligibility on the resolved row.
No materialized FK created through these surfaces can point to an ABANDONED,
REJECTED, DRAFT, or PROPOSED candidate.

Implementation uses the existing handler-normalization seam rather than adding a
new GSM keyword:

- `DirectiveService` and `NormService` already expose `qualifier` as the referenced
  Archetype's Definition UUID in `getIdentityBoundValues`.
- `EffectorService` and `ReceptorService` add `archetype` with the output/input
  Archetype's Definition UUID to their handler-declared identity maps.
- `AscriptionService` applies the same Definition-UUID comparison to the typing
  Archetype when creating a sibling Ascription of an existing Definition.
- `AscriptionIdentityBoundValidationService` treats handler-declared values as the
  canonical representation and skips duplicate raw JSON comparison for those field
  names. Other `$gsm:identityBound` properties retain lexical JSON equality.

A malformed Archetype `$id` fails with 400 under `ARCHETYPE_REF_NORM`; a
grammar-valid unresolved or not-yet-resolvable value fails with 400 under
`ARCHETYPE_REF_INTEGRITY`; a resolved but operation-ineligible value fails under the
existing lifecycle Referee transition rule with 409. Surface-specific typing,
qualifier, and port integrity rules remain responsible for role mismatch after
resolution. Every error names the surface and requested value. No path falls back
to UUID or title.

### 2.8 Title demotion (part of WP1 — no gate, no waiting)

With exact Archetype URI resolution (§2.5), the title-keyed internals are re-keyed in the
same work package:

1. Governance-chain machinery (`getAncestorTitles`/`collectAncestorTitles`/
   `isDescendantOf`) → stems.
2. `ArchetypeCompositionValidationService` — its cycle-detection (`visited`) and
   base-convergence (`resolvedBases`) sets are title-keyed and would collapse
   same-titled cross-namespace archetypes into one node (spurious acyclicity/
   convergence failures) → re-key on `$id`/stem.
3. `MechanismPortDerivationService` anchors and rule-declared data-archetype names,
   `MechanismRuleValidationService` archetype names — **detailed in §2.9** — and
   `AscriptionService:495`. `extractTitleFromRef` survives only as a display-name
   helper.
4. `ArchetypePropertyIndexationService`: dynamic index names derive today from
   truncated title + property with `IF NOT EXISTS` — duplicate cross-namespace local
   names would silently share an index name and drop each other's indexes on
   deactivation → collision-resistant hash of the full Archetype URI. The version
   remains part of the hash because ACTIVE and DEPRECATED versions can coexist with
   different indexed-property sets; stem hashing would collapse those indexes.
5. The ascription list endpoint's `archetypeUri` filter resolves one exact Archetype URI
   to an in-effect typing Archetype. It does not perform title fallback or cross-namespace union.
6. `title` remains: required, identity-bound by construction, display + local name.

Exception (WP2, §2.5): the CEL applicability root lookup and `uq_archetype_title`.

### 2.9 Mechanism rule references (Starlark) — `$id` strings, no wrapper

**Today — three title surfaces** (inventoried in the study §2.1): (1) rule sources
declare data archetypes by bare title string — `sys.receive("ItAppraisalTrigger")` /
`sys.effect("ItAppraisalMeasure", …)`; (2) `MechanismRuleValidationService`
(L185, 622) validates the declared names as titles via global title lookup;
(3) `MechanismPortDerivationService` (L225–269) derives the Mechanism's
Effector/Receptor port statements from those declarations, anchored on the
hard-coded titles `"Effector"`/`"Receptor"`.

**Target (WP1, DM-side)**:

- Rule declarations use **full `$id` strings**:
  `sys.receive("gsmarc://gsm-ontology/it/meta-governance/ItAppraisalTrigger/v1")`.
  Unlike CEL (§3), Starlark needs **no wrapper**: declarations are ordinary string
  arguments, and a URI is valid string content — no grammar amendment, drop-in.
- `MechanismRuleValidationService`: declared references are validated against the
  identity grammar and resolved via `findResolvableByUri`, followed by the
  existing in-effect policy; bare-title declarations → 400. Rename title-specific
  public rules rather than retaining misleading aliases:
  `MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_TITLE` becomes
  `MECHANISM_RULE_TRIGGER_ARGUMENT_AS_ARCHETYPE_ID`, with stable URI
  `gsm:rules/mechanism/rule/trigger-argument-as-archetype-id`; apply the same
  `*_TITLE` → `*_ID` replacement to receive/effect/by/on rules and their
  `gsm:rules/...` URIs. Rule titles, descriptions, and fluent API diagnostics say
  “Archetype URI”, never “name” or “title”. This is a deliberate
  pre-production public-contract replacement; do not keep duplicate old values.
- `MechanismPortDerivationService`: the `Effector`/`Receptor` anchors move to the
  exact base `$id`s `gsmarc://gsm/Effector/v1` / `gsmarc://gsm/Receptor/v1`
  (exact eight seed `$id`s, same `isGsmBaseId` detector); derived port statements reference
  their data archetypes by `$id`.
- **Version-pinning semantics**: a rule receiving `…/v1` does not match statements
  typed by `/v2`; adopting v2 = re-issuing the Mechanism as its next version — the
  same explicit upgrade propagation as `$ref`, Archetype references (§2.7), and CEL `ref()` (§3):
  **version-pinning is uniform** (no latest-in-effect fallback). Eligibility
  after lookup is **not** uniform — see the §2.4 matrix.
- **Catalogue boundary**: no live `.star` catalogue exists in the current workspace;
  the eight historical files are archive-only and remain untouched. Any future live
  Mechanism catalogue must declare exact `$id`s before ingestion and be covered by
  L9 at its owning repository boundary. The stricter DM validation intentionally has
  no title compatibility window.
- **Operator (WP3)** consumes the same strings for dispatch — DM validation (WP1)
  and operator dispatch (WP3) share one authored representation; no operator-side
  translation layer.

### 2.10 Catalogue prerequisite (gsm-ontology) — **landed**

Lint L5 hard-coded `…/{stem}/v1`, which would have forbidden the catalogue's own
documented `/v1 → /v2` bump policy and made later candidate claims unpublishable.
Generalized to `/{stem}/v[1-9][0-9]*` (anchored `re.fullmatch`) on
2026-08-22; catalogue lint clean.

### 2.11 Tests (JaCoCo ≥ 95% maintained)

- grammar accept/reject table (uppercase authority → reject, edge/double-hyphen
  authority (`-acme`, `acme-`, `a--b`) → reject, empty namespace path for `gsm` →
  accept, lowercase-leading or punctuation-bearing title, `v0`, `v007`, missing
  `/v`, version beyond integer range → reject). Every new RuleType has at least one
  direct passing case and one direct failing case asserting rule id, stable `type`
  URI, HTTP status, field/JSON Pointer, and description;
- coherence positive/negative; same-Definition stem identity-bound across versions;
  a `DRAFT → PROPOSED` transition accepts exactly the next ordinal while allowing
  multiple PROPOSED siblings with the same candidate `$id`; approving one assigns
  that ordinal and generic convergence terminates the other DRAFT/PROPOSED siblings;
  a forced assignment mismatch rolls back and reports both values;
- candidate/resolvable identity: two same-Definition DRAFT/PROPOSED siblings may
  carry the same candidate Archetype URI; neither resolves by URI. After one wins
  approval, the resolution-uniqueness index prevents any second resolvable row with
  that URI, including after RETIRED. An ABANDONED/REJECTED candidate URI may be re-authored
  only as another Ascription of the same Definition;
- version materialization: trigger assignment at APPROVED, `version = 0` before,
  pre-APPROVED terminals stay 0, `(definition_id, version)` uniqueness under
  concurrent approval attempts (race loser → 409, not 500) — **across all 8 concrete
  tables**. For every table, direct positive-version insertion in a pre-approval
  status, zero-version insertion in a post-approval status, and arbitrary UPDATE of
  `version` fail the named coherence/writer guard. Ordinary trigger transition and
  explicit base-seed `version = 1` insertion pass;
- transition locking: concurrent SUBMIT/APPROVE requests for one Definition across
  each subject type serialize on the same Definition key. A request waiting behind
  a winner reloads status and rejects the now-terminal sibling rather than using a
  stale entity. Different Definitions proceed independently. APPROVE proves
  `flush()` + `EntityManager.refresh(winner)` occurs before candidate/version
  reconciliation and that reconciliation sees the trigger value;
- CREATE + identity-bound uniqueness (§2.3): omit `definitionId` creates a new
  Definition; supply `definitionId` attaches to that Definition. Same stem on a
  different Definition → 409, including after the first Definition's candidates
  are all ABANDONED/REJECTED. Same Definition, different stem → 409
  `ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION`. Concurrent
  first creates of `S/v1` and `S/v2` that omit `definitionId` produce two
  Definitions and the second create observes the first owner returned by
  `gsm_acquire_archetype_stem_owner` and fails. To
  author `/v2`, the caller supplies the existing Definition id. Same-stem CREATE
  requests serialize on the stem key; unrelated stems proceed independently;
- nested `$id`: any non-root `$id` → 400 `ARCHETYPE_ID_ROOT_EXCLUSIVITY` at its
  JSON Pointer; `$id` inside `examples`/`default`/`const`/`enum` data is ignored;
  a `gsmarc://` target's own root `$id` remains valid. Parameterized walker tests
  cover each direct-, array-, and map-valued schema keyword from §2.3 plus boolean
  schemas; data-valued lookalikes are not traversed. Authored local and external
  `$dynamicRef` both fail `ARCHETYPE_REF_NORM`, while the internal meta-schema's
  `$dynamicRef: "#meta"` continues to validate catalogue schemas;
- composition (L8): sibling `allOf` facets sharing a property name → 400
  `ARCHETYPE_ALLOF_PROPERTY_DISJOINTNESS`; host redeclaring an inherited property
  with a different type → 400 `ARCHETYPE_ALLOF_PROPERTY_TYPE_STABILITY`; same-type
  vertical narrowing → accepted; mount-style composite → accepted. Repeat these
  cases for inline facets, local `$ref`, resolvable external `$ref`, and transitive
  inheritance. Scalar/array `type` normalization treats `"string"` and
  `["string"]` as equal, differing sets fail, and absent/boolean-schema types are
  not inferred or rejected;
- resolution: an older resolvable version resolves exactly while a newer one is ACTIVE;
  RETIRED, SUSPENDED, and APPROVED-not-yet-ACTIVE versions remain directly readable
  by their Archetype URI; a candidate row with the requested value does not count
  as resolution; a grammar-conformant unknown `$id` is a hard error; a non-grammar
  `$ref` fails `ARCHETYPE_REF_NORM`. External `$ref` tests exercise the **§2.4
  eligibility matrix** (exact read vs typing ACTIVE|DEPRECATED vs CREATE/SUBMIT
  vs APPROVE/ACTIVATE sets). Specifically, DRAFT/PROPOSED candidate `$id`s fail
  CREATE/SUBMIT resolution even though those statuses occur in the generic
  UUID-Referee maxima; APPROVED/ACTIVE pass CREATE/SUBMIT/APPROVE and only ACTIVE
  passes ACTIVATE;
- Archetype references (§2.7): typing, qualifier, and Effector/Receptor data-role
  URIs resolve to exact Archetype Ascription FKs; UUID-shaped, unresolvable, and
  not-yet-resolvable references → 400; resolvable but operation-ineligible references →
  the existing transition Referee rule / 409; identity-bound `/v1` → `/v2` re-pin
  within one target Definition is accepted, while a switch to another Definition
  is rejected;
- mechanism rule references (§2.9): bare-title declaration → 400;
  grammar-conformant resolvable declaration → accepted; unresolvable `$id`
  declaration → 400; port derivation anchored on the exact base `$id`s
  (`gsmarc://gsm/Effector/v1` / `Receptor/v1`); problem responses expose the new
  `*_AS_ARCHETYPE_ID` rule ids/URIs and no old `*_AS_ARCHETYPE_TITLE` metadata;
- schema-mapper exact-match: each of the 8 base `$id`s maps to its classpath seed;
  `gsmarc://gsm/Structure/v99`, `gsmarc://gsm/x/Structure/v1`, and
  `gsmarc://tenant/Structure/v1` do not classify or map as GSM Structure v1.
  Parameterized tests cover every former `isGsmBaseTitle`,
  `DefinitionSubjectType.fromArchetypeTitle`, and `extractTitleFromRef`
  classification call site;
- bootstrap: clean database → seeds ACTIVE with version 1 (set explicitly by the
  seed insert, §2.6);
  already-initialized database → seeding skipped;
- REST: `AscriptionDto`/OpenAPI expose subject `version` and Definition-level `type`
  for every subject type, with no Archetype identity body field.
  Archetype direct reads distinguish candidate `statement.$id` from service-side
  Archetype URI resolution. The generic list API has no Archetype-subject-only exact-ID
  filter. Norm responses have the same envelope as every other subject type and
  expose no derived applicability metadata;
- exception translation: force each of the eight Definition-version indexes and the
  URI-resolution index and assert its declared rule/409. Exercise a conflicting owner
  returned by the stem acquisition function and assert its declared rule/409. The same SQLSTATE
  with an unknown constraint remains the generic 500 path, proving translation is
  constraint-name-specific;
- schema test: fresh `V1` creates the version column/trigger/constraint on all 8
  tables, including `ck_{table}_status_version` and version-writer guards, plus
  `ix_archetype_stem`, `archetype_stem_owner`,
  `gsm_acquire_archetype_stem_owner`, and
  `trg_archetype_stem_owner_immutable`,
  `uq_archetype_resolvable_uri`, and `ix_archetype_id`; it creates neither
  `uq_archetype_stem`, an identity registry table, nor a
  `definition.external_identity` column.

## 3. WP2 — CEL applicability `ref("$id")` (no new Norm field)

**Decision (2026-08-22, supersedes the reviewed `bindings` design)**: applicability
expressions reference archetypes **in-expression** via a `ref()` wrapper. A raw
`$id` cannot be a CEL root — CEL identifiers are `[_a-zA-Z][_a-zA-Z0-9]*`; `://`,
`/`, `-` are unparseable — so the URI travels as a string constant:

```text
ref("gsmarc://gsm-ontology/gdpr/transfers/TransferMechanism/v1").internationalTransfer == true
&& ref("gsmarc://gsm-ontology/gdpr/controller-processor/ControllerProcessorRole/v1").role in ['CONTROLLER']
```

Compared to `bindings`: no schema field, no declare-then-use indirection, the
statement is self-contained (its meaning is entirely in the expression), and
version-pinning sits at the point of use. Verbosity is a presentation concern:
frontends display the short title — derivable, since the grammar guarantees the
title segment — and may accept short names at input, expanding to `ref()` on save;
the **stored governed statement is always fully qualified**.

### 3.1 Profile amendment (gsm-specifications — prose only, no schema change)

- CEL **applicability profile**: roots are `ref("<identity-grammar $id>")` calls;
  bare title roots removed from the profile. The **assertion profile is untouched**
  (bare qualifier properties; the `self.`/uppercase-root rejections stay).
- `specification.md` §9.1 title bullet ("root identifier used in Norm CEL
  expressions (§14.1)") replaced by the `ref()` rule; `Norm.schema.json`'s
  `applicability` description updated to state the profile — **no new field**.
- `conformance.md` `GSM-PROC-11` (global title uniqueness) amended here (§2.5).
- Sync to defman vendor via `make sync-gsm-schemas`; `GsmSchemaVendorSyncTest`
  gates drift.

### 3.2 DM changes

- `NormApplicabilityValidationService`: `collectAxes`/`extractAxis` extended to
  treat `ref("…")` CALL nodes (single string-constant argument, validated against
  the identity grammar) as roots; resolution uses
  `findResolvableByUri`, followed by ordinary CREATE/transition Referee
  checks; property-path validation is unchanged against the resolved schema. Bare
  title roots become a validation error (same rule id, new message).
- `AscriptionDto` remains structurally uniform across all eight subject types. Norm
  applicability references are not projected into subtype-specific response
  metadata. Unknown top-level create properties are rejected rather than silently
  discarded.
- **Lifecycle referee references need no declared field**:
  `NormService.getRefereeReferences` (today: Structure + qualifier) extends to every
  distinct `ref()` target extracted from the parsed expression. The ordinary
  Referee precondition is checked on CREATE and every Norm transition. No Cascade
  role or target finder is added; Reference discovery alone does not propagate a
  later Archetype transition to the Norm.
- Axis semantics: the one-predicate-per-`(Archetype, propertyPath)` rule keys on
  the **resolved `$id`** — two `ref()` occurrences of the same `$id` form one axis.
- `findInEffectByTitle` loses its last consumer → deleted; `uq_archetype_title`
  deleted from `V1` (§2.5).
- Runtime evaluation is a blocking Operator contract, specified in §4.1; WP2 is not
  complete merely because the DM can parse and validate the call.

### 3.3 Catalogue migration (gsm-ontology)

Mechanical rewrite of all Norm applicability expressions (78 statements):
`Title.path` → `ref("<$id>").path`, the `$id` taken from the framework's own
`elements[]` (every referenced archetype is a catalogue member). Lint **L9**:
applicability roots must be `ref()` calls whose argument is an L5-coherent `$id`.

## 4. WP3 — Operator identity runtime (sie-operator)

`sys.receive`/`sys.effect` dispatch and `OperationFrameDto` frame matching compare
**titles** today, and derived Effector/Receptor statements carry archetype
**UUIDs** — not a string-format-only change. WP3 moves matching and dispatch to the
**same full-`$id` strings the rules already declare after §2.9** (supplied,
together with `version`, by WP1's API surface). Sequenced after WP1; **no dual-read
window** — operator and DM are co-deployed design-time components.

### 4.1 CEL `ref()` runtime contract (blocking)

The Operator has no CEL applicability evaluator today. WP3 therefore includes this
new contract; it MUST NOT be deferred behind syntax-only WP2 completion:

1. WP3 introduces `NormApplicabilityEvaluationService.evaluate(norm,
observations)`. Its orchestration caller supplies
   `List<ArchetypeBindingDto> observations`, where each record contains
   `(String archetype, JsonNode value, JsonNode schema)`; sourcing domain
   observations and their exact schemas remains the caller's responsibility, not
   CEL's. During evaluation preparation, the service derives the expected exact
   target set from the compiled applicability expression, validates that supplied
   binding keys match it exactly, validates each schema `$id` against its binding
   key, validates every supplied value, rejects duplicate Archetype `$id`s while
   constructing an immutable map, and retains that map only for this invocation.
   The CEL function itself performs no repository or network I/O.
2. The CEL environment exposes one pure function, `ref(string) -> object`. The
   argument is the compile-time string literal admitted by §3.2. It returns the
   corresponding JSON object converted with the existing JSON-to-CEL adapter.
3. Lookup is exact and version-pinned: `/v1` never falls through to `/v2`, a title,
   a stem, or a latest-in-effect lookup.
4. A missing binding is an evaluation error naming the `$id`; it is never `null`,
   `{}`, or implicit `false`. More than one candidate value for the same `$id` is a
   context-construction error before CEL execution.
5. The runtime requires a binding for every distinct `ref()` target extracted from
   the applicability expression; unexpected extra bindings are rejected. The
   extracted target set and the runtime key set are compared before evaluation.
6. Each invocation receives a fresh binding map. No binding or compiled expression
   may retain payload state across operations.

### 4.2 Starlark frame matching and dispatch

`OperationFrameDto` keys resolved data Archetypes by Archetype URI rather
than title. Every `sys.receive`/`sys.effect`/`by`/`on` literal is matched exactly
against the corresponding data- or port-Archetype `$id`; no title, suffix, stem, or
latest-version fallback exists. Existing ambiguity rules for multiple matching
ports remain, but their comparison key changes to full `$id`.

## 5. Risks

| Risk                                                                     | Mitigation                                                                                                                                                                                           |
| ------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Concurrent first creates of `S/v1` and `S/v2` that omit `definitionId`   | the stem acquisition function serializes both claims and returns the first owner to the loser; to author `/v2` the caller supplies the existing Definition id (§2.3)                                 |
| Competing proposals carry the same candidate `$id`                       | intended GSM convergence: candidates are not exact-ID resolvable, one `PROPOSED → APPROVED` wins, and generic convergence terminates the siblings (§2.3–§2.4)                                        |
| Stale or gap-skipping candidate                                          | rejected on `DRAFT → PROPOSED`; the immutable DRAFT is ABANDONED and re-authored. Approval reconciliation remains a defensive invariant only                                                         |
| In-flight integrations break (authoring + `$ref` policy tighten at once) | accepted by decision — pre-production (§2.6); integrations adapt alongside; the catalogue's 236 distinct `$id`/`$ref` URIs are grammar-conformant, which does not imply full statement ingestibility |
| A deployment acquires real data mid-implementation                       | reinstate versioned migrations + a compatibility plan at that moment (§2.6 names this as the single revert trigger)                                                                                  |

Rollback: **git revert plus drop/recreate the pre-production database**. Reverting
source alone cannot undo an already-applied in-place `V1` checksum/schema change.
There is no feature flag or mixed-schema mode; DB constraints and triggers are
always on. The creation DTO change from UUID to `$id` is intentionally breaking,
not additive, and clients are co-updated in this pre-production work package.

## 6. Acceptance criteria

- [x] All §2.3 rules have complete enum metadata (stable URI, title, description),
      explicit controller status mapping, and positive + negative contract tests;
      JaCoCo ≥ 95% module level.
- [x] Version materialization demonstrated per §2.2 (trigger-assigned, 0-before,
      gapless per Definition) — across **all 8** concrete tables. Named DB guards
      enforce status/version biconditional coherence and reject direct version
      mutation; the transition trigger is the sole non-seed writer.
- [x] SUBMIT/APPROVE serialize on a Definition-scoped transaction advisory lock for
      all 8 types; Archetype CREATE serializes permanent ownership on a stem-scoped
      lock. Reads occur after lock acquisition, stale waiters reload status, and
      APPROVE flushes + refreshes before version reconciliation.
- [x] Every user-reachable DB race guard is translated by exact PostgreSQL
      constraint name: 8 Definition-version indexes and the URI-resolution index
      return their declared 409 RuleType; a conflicting owner returned by the stem
      acquisition function maps explicitly to its declared 409 RuleType;
      unknown/coherence failures remain 500 rather than being misclassified by SQLSTATE.
- [x] Stem identity-bound + permanent owner (§2.3/§2.6): CREATE stays generic
      (`definitionId` omit/attach). Same stem on another Definition → 409
      (`ARCHETYPE_STEM_UNIQUENESS_ACROSS_DEFINITIONS`), including after
      ABANDONED/REJECTED-only history. Same Definition, different stem → 409
      (`ASCRIPTION_PROPERTY_INTEGRITY_WITHIN_DEFINITION`).
      Same Definition `/v1` and `/v2` share the stem and both persist.
      `archetype_stem_owner` and `gsm_acquire_archetype_stem_owner` exist;
      `trg_archetype_stem_owner_immutable` prevents key changes, owner transfer,
      and deletion; `trg_archetype_stem_owner` and `uq_archetype_stem` do **not**.
      All eight bootstrap seeds own their stems through the same function.
      No identity registry table and no `definition.external_identity` column.
- [x] Candidate lifecycle (§2.3): a DRAFT Archetype URI is not resolvable;
      `DRAFT → PROPOSED` (`SUBMIT`) accepts exactly
      `1 + max(approved version)` and permits multiple PROPOSED siblings with the same
      candidate URI; `PROPOSED → APPROVED` (`APPROVE`) assigns that version,
      makes one winner's URI resolvable, and invokes ordinary sibling convergence; a forced
      mismatch rolls back as a 409
      `ASCRIPTION_STATUS_TRANSITION_ARCHETYPE_VERSION_RECONCILIATION`.
- [x] Nested identity / Referee (§2.3–§2.4): any non-root `$id` → 400
      `ARCHETYPE_ID_ROOT_EXCLUSIVITY` with its JSON Pointer; a `gsmarc://` `$ref`
      resolves only an exact resolvable target (`ARCHETYPE_REF_INTEGRITY`) and is
      returned by `ArchetypeService.getRefereeReferences`; eligibility is the §2.4
      effective matrix (typing may pin DEPRECATED; CREATE/SUBMIT/APPROVE =
      {APPROVED, ACTIVE}, ACTIVATE = {ACTIVE}). Generic UUID-Referee maxima remain
      unchanged but do not make candidates externally referenceable; no candidate
      resolver, Archetype Cascade, or Interaction primitive is added.
- [x] The shared Draft 2020-12 schema-position walker covers every keyword listed in
      §2.3, ignores data-valued annotations, resolves local fragments without
      creating Referees, and rejects authored `$dynamicRef`; the internal
      meta-schema `$dynamicRef: "#meta"` remains valid.
- [x] Meta-schema amendment: `Archetype.schema.json` `description` prose de-minting
      **only** — no root keywords added (see §2.3: per-node recursion makes
      `required`/`properties` fatal); `Norm.schema.json` assertion examples AND
      `assertion` property description; `conformance.md` GSM-PROC-10 (amend) +
      GSM-PROC-4 (strengthen to gapless); **not** GSM-PROC-11; plus the
      normative processor/producer `$id`-assignment clauses in `specification.md`
      (§processor obligations ~L287, §producer obligations ~L516) and `GSM-PRD-2`
      in `conformance.md` — all say the DM assigns `$id` and/or hard-code the `gsm`
      authority; left unamended they contradict producer-claimed. The local
      gsm-specifications source is amended and the vendored byte-sync gate is green.
- [x] `conformance.md` **GSM-PROC-11** demotes title from global identity in the
      combined WP1/WP2 delivery; the DM has no title uniqueness guard or identity
      lookup (§2.5).
- [x] Archetype references (§2.7): `AscriptionCreationDto.archetype`,
      Directive/Norm `qualifier`, and Effector/Receptor `archetype` resolve only exact
      resolvable Archetype URIs and persist exact Archetype Ascription FKs; the typing operation
      applies its status policy and Referee-valued fields use the generic lifecycle
      preconditions. Handler normalization compares target Definition identity, so
      `/v1` → `/v2` within one Definition passes while a switch to another Definition
      fails. Full catalogue
      Directive/Norm ingestion is **not** claimed: their separate Structure-token
      drift remains out of scope.
- [x] `specification.md` and `conformance.md` define identity-bound equality for an
      Archetype-valued reference as equality of the referenced Definition/stem,
      while preserving the exact `$id` pin in each Ascription; lexical equality
      remains the rule for ordinary identity-bound values.
- [x] Mechanism rule references by `$id` (§2.9): `MechanismRuleValidationService`
      and `MechanismPortDerivationService` moved off titles; title-specific public
      rule ids/URIs/descriptions renamed to `$id` semantics. No live `.star`
      catalogue exists in this workspace; archive copies remain read-only, and any
      future catalogue must add L9 at its owning repository boundary.
- [x] Exact Archetype URI resolution demonstrated: an older resolvable version resolves
      by URI while a newer one is ACTIVE; RETIRED resolves read-only; DRAFT and
      PROPOSED candidate values never resolve by URI.
- [x] Exact-key policy (§2.5): grammar-conformant unresolvable ref → hard error;
      non-grammar ref → rejected by the tightened `ARCHETYPE_REF_NORM`; no title,
      stem, latest-version, or candidate fallback exists in code.
- [x] Every base-detection/classification path is exact-ID based: schema mappers,
      `resolveSubjectType`, composition, and ancestry accept only the 8 seed `$id`s.
      Wrong namespace and wrong version with a primitive-looking title are negative
      tests and never classify as GSM base.
- [x] REST/OpenAPI creation uses required `String archetypeUri` `$id` instead of UUID;
      every response exposes subject `version` and its stable Definition link, with no
      Archetype identity body field. The generic list endpoint has no subtype-only exact
      Archetype filter. Norm responses retain the generic Ascription envelope with no
      derived top-level metadata. sie-operator obtains exact port typing identity from
      the existing per-ascription composed schema resource.
- [x] Lint L5 generalized in gsm-ontology (§2.10) — landed 2026-08-22.
- [x] `gsm.puml` amendments: §Identity matches §2.1; `$id` authorship changed to
      producer-claimed/DM-verified (§2.3); §Ascription version semantics unchanged
      (§2.2 implements them as written).
- [x] `gsm-ascription-lifecycle.puml` identifies `gsmarc://` `$ref` targets
      as Archetype Referees and removes the contradictory "Archetype is not a
      Referee" statement; it adds no status, transition, Cascade, or Interaction.
      Allowed-status text points at the §2.4 matrix rather than claiming one
      unmodified set. Local `#…` fragments are not Referees.
- [ ] Commit and merge the local gsm-specifications WP1/WP2 amendments; vendored
      sync is already green. The remaining normative content includes the
      `ref("$id")` applicability profile (§3.1), including `specification.md`
      §9.1's title bullet ("root identifier used in Norm CEL expressions"), the
      normative twin of the WP1 schema-description de-mint.
- [x] Catalogue applicability migration (§3.3): 78 Norm expressions rewritten to
      `ref()`; lint L9 green.
- [x] Norm `ref()` targets are ordinary lifecycle Referees; CREATE/transition
      preconditions are covered against the effective resolvable target-status matrix,
      and no candidate resolver, Cascade role, or target finder is introduced.
- [x] WP3 CEL runtime (§4.1): exact-version binding returns the validated object;
      missing and duplicate bindings fail explicitly; title/stem/latest fallbacks
      are absent; validation/runtime target-set mismatch fails before evaluation;
      invocation state is isolated.
- [x] L8 parity is tested and documented as the conservative composition policy in
      §2.3: inline/local/external/transitive facets, effective-property disjointness,
      scalar/array type-set normalization, absent type, boolean schemas, same-type
      narrowing, and mount wrappers. It is not represented as a complete JSON Schema
      satisfiability proof.
