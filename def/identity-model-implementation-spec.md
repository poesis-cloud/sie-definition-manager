# Implementation Spec — `$id`-Anchored Archetype Identity

> Companion to the design study:
> [identity-model-study.md](identity-model-study.md). Normative for the
> implementation.
> Status: **reviewed — multi-model aligned** (Claude Opus 5, 7 adversarial rounds; GPT-5.6 Sol, 3 adversarial rounds; 2026-08-22). Residual non-blocking polish suggestions from the final sign-offs are tracked in the review record, not in-line.

## 1. Scope

Three phases across four repositories:

| Phase | Repo(s) | Deliverable | Gate |
|---|---|---|---|
| **1 — `$id` handling + version materialization** | sie-definition-manager (+ gsm-specifications prose, + gsm-frameworks lint L5 prerequisite §2.7) | version column + assignment-at-APPROVED, structural validation, coherence invariant, full-`$id` resolution with WARN fallback, uniqueness index, reconciliation | gsm-specifications prose amendment merged; non-breaking for resolution, breaking for authoring (§2.5) |
| **2 — CEL bindings** | gsm-specifications, sie-definition-manager | Norm `bindings` map replacing title CEL roots | Phase 1 stable |
| **3a — DM title demotion** | sie-definition-manager | drop global title uniqueness; hard-error on `$id` miss; retire title-keyed internals | zero `legacy-id-fallback` WARNs over an observation window; catalogue L9 adopted; operator `$id`-first dual-read deployed |
| **3b — Operator dispatch** | sie-operator | dual-read (title OR `$id`) frame matching, then `$id`/binding-based Starlark dispatch | Phase 1 API surface; cutover coordinated with 3a |

Out of scope: the catalogue generator (ontology.json → composites re-pinning) — tracked
separately in `gsm-frameworks`.

## 2. Phase 1 — `$id` handling and version materialization in the DM

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
title          = UPPER *(ALPHA / DIGIT)          ; PascalCase local name
version        = %x31-39 *DIGIT                  ; positive integer, no leading zero
definition-id  = gsmarc-id minus "/v" version    ; the Definition identity string
loweralnum-seq = 1*(%x61-7A / DIGIT)             ; [a-z0-9]+
UPPER          = %x41-5A                         ; [A-Z]
```

Notes:
- Empirically validated: all **236** distinct `gsmarc://` URIs in
  `gsm-frameworks/frameworks/**/*.json` match this regex (repo-wide grep yields 253;
  the extra 17 are prose/placeholder artifacts in markdown). Catalogue convention
  `gsmarc://gsm-frameworks/{framework}/{taxonomy}/…` maps to grammar-authority
  `gsm-frameworks`, framework = first namespace segment.
- The version production intentionally narrows the legacy `\d+` (rejects `v0`,
  `v007`); a data check over existing refs precedes V2 (expected clean — verified in
  the catalogue).
- **This grammar does NOT replace `isAllowedRef`/`GSMARC_URI_PATTERN` in Phase 1** —
  the `$ref` URI *policy* is unchanged; the grammar governs identity validation and
  `$id`-based resolution eligibility only. Tightening the ref policy to this grammar
  is a Phase 3 item behind its own data check.

### 2.2 Version materialization (prerequisite — new machinery)

The DM currently has **no version concept**: the column was dropped in design-phase
cleanup (`V1__gsm_init.sql` note 8) and `AscriptionEntity` has no field; ordering is
derivable only from `findAllByDefinitionIdOrderByTimestampDesc`. `gsm.puml`
(§Ascription) normatively specifies: `version` is a non-negative integer, `0 = not
yet approved`, **assigned atomically by a DB trigger at the APPROVED transition —
never by application code**, monotonically increasing and gapless within the APPROVED
lineage per Definition. Phase 1 implements **exactly that specification**:

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
  max+1 plus rollback of a race loser — or of a reconciliation failure, whose failed
  APPROVED transition rolls back leaving the row PROPOSED with its ordinal
  unconsumed — not from the index alone).
- Ascriptions terminal before APPROVED keep `version = 0` — they never consume an
  ordinal. (The transition machine makes the pre-APPROVED terminal set exactly
  {ABANDONED from DRAFT, REJECTED from PROPOSED}: `ABANDON` is the only edge into
  ABANDONED and it departs from DRAFT.)
- Backfill: per definition, number existing APPROVED-or-later ascriptions by
  `timestamp` ascending. The status set `('APPROVED','ACTIVE','DEPRECATED',
  'SUSPENDED','RETIRED')` is provably exhaustive: no post-APPROVED edge leads to
  ABANDONED or REJECTED.
- `AscriptionEntity` gains a **read-only** mapped `version` field
  (insertable/updatable = false), honoring "never set by application code".

### 2.3 Identity authorship and validation rules

**Authorship decision (amends `gsm.puml`)**: `$id` is **producer-claimed,
DM-verified** — never DM-minted. The statement is immutable post-creation, and the
version is only knowable at APPROVED, so minting would require mutating the statement;
instead the producer claims the full `$id` (including the version ordinal it expects
to earn) and the DM verifies every component. `gsm.puml`'s "`$id` assigned by DM" is
amended accordingly (acceptance criterion). The stable *internal* identity remains the
Definition UUID; the canonical *external* identity is the definition-id stem, mapped
one-to-one by a new registry:

- `definition_external_identity (stem text PRIMARY KEY, definition_id uuid UNIQUE
  REFERENCES definition(id))` — row inserted at the Definition's **first APPROVED**
  ascription; never deleted (stems are permanently non-reusable, including after
  RETIRED). This table is the race-safe one-stem-one-Definition mapping and the
  Phase-3a replacement for `uq_archetype_title`.

New `AscriptionConsistencyRuleType` entries (error-code note: the DM maps consistency
rules to **400** and uniqueness rules to **409** today, `AbstractController:232-248`;
no 422 exists in the codebase — the table below follows that convention rather than
introducing a new status class):

| Rule id | Check | Failure |
|---|---|---|
| `ARCHETYPE_ID_STRUCTURE` | statement `$id` present on Archetype-typed ascriptions and matches the identity grammar | 400, field `$id` |
| `ARCHETYPE_ID_TITLE_COHERENCE` | `$id` title segment ≡ statement `title` (exact, case-sensitive) | 400, fields `$id`, `title` |
| `ARCHETYPE_AUTHORITY_RESERVED` | authorities `gsm` and `gsm-frameworks` are reserved: claims under them are accepted only via the bootstrap/admin channel. Full tenant↔authority binding requires authenticated tenancy, which the DM does not yet have (`SecurityConfig` permits all; `TenantMdcFilter` is untrusted telemetry) — reserved-authority rejection is what Phase 1 CAN enforce. Channels: `gsm` claims are accepted only from the in-process `ArchetypeSeedRunner` (not reachable via HTTP); `gsm-frameworks` claims are accepted only from the **trusted catalogue-import channel** — until authenticated tenancy lands, that is a dedicated operator-run in-process import job (seed-runner pattern), NEVER open REST; once authn exists it becomes an authenticated admin endpoint. This channel is a **prerequisite for any catalogue ingestion** (and therefore for the Phase-3a observation window, which counts catalogue ingestions) — without it the reserved rule would dead-end the gsm-frameworks contract that requires ITIP to register catalogue statements in the DM. Ordinary REST claims under either reserved authority are rejected; the general spoofing risk for non-reserved authorities remains a named dependency on the security roadmap (§5) | 400 |
| `ARCHETYPE_ID_CLAIM_UNIQUENESS` | at **CREATE**: the claimed full `$id` is not claimed by **any other ascription whose status is not in {ABANDONED, REJECTED}** — exactly the `uq_archetype_id` index predicate, and *including siblings of the same Definition* (GSM permits concurrent DRAFT/PROPOSED siblings; without this, one lineage could hold multiple resolvable rows under one `$id` and `LIMIT 1` turns nondeterministic). RETIRED rows therefore also block re-claims — deliberately: RETIRED is in resolvable-pinned (§2.4), so re-claiming its `$id` would let one `$id` denote two rows and break "validates identically forever". Re-claiming is possible only after the prior claimant is ABANDONED or REJECTED. The stem clause: not claimed by such an ascription of a different Definition nor registered to one. **DB-backed** by `uq_archetype_id` (§2.6): concurrent CREATEs racing past the service check lose on insert and are translated to 409 — no TOCTOU window, and the index predicate is only *enterable* at INSERT (ABANDONED/REJECTED have no outbound edges), so no status transition can later violate it | 409 |
| `ARCHETYPE_ID_DEFINITION_IMMUTABILITY` | definition-id stem identical across all Ascriptions of one Definition — implemented by extending `ArchetypeService.getIdentityBoundValues` with the stem, reusing the existing identity-bound machinery. Note: that validator reports the generic identity-bound rule (mapped at `AbstractController:242` today); the per-rule error-code mapping is part of this change | 400 |
| `ARCHETYPE_ID_VERSION_RECONCILIATION` | at APPROVED: the `$id` version claim ≡ the trigger-assigned version, compared **after** the transition flush by re-reading the assigned value. Mismatch → hard error naming both versions. Note the widened `uq_archetype_id` **preempts** under-claims at CREATE: by §2.2 gaplessness every ordinal ≤ max is held by an APPROVED-or-later row, which is permanence-sound and therefore inside the index predicate — so a re-ingested `/v1` against a lineage already holding `…/v1` dies as a 409 before any transition (no wasted approve transaction). Reconciliation is the residual backstop for **over-claims** (an ordinal ahead of `1 + max`, which no row holds and so passes CREATE). **Remediation**: the failed APPROVE transaction rolls back (row remains PROPOSED, ordinal unconsumed — this rollback is part of the §2.2 gaplessness argument); the ascription is then **REJECTED** as a separate remediation transition (the only terminal edge from PROPOSED) and re-authored with the corrected claim; the error message states this. (400 follows the codebase's consistency-rule convention deliberately — semantically this is conflict-family; do not "fix" to 409 later without changing the convention note above) | 400 |
| `ARCHETYPE_ID_UNIQUENESS` | full `$id` unique among in-effect ascriptions — retained as the activation-time restatement; post-widening it is strictly subsumed by CLAIM + `uq_archetype_id`, whose predicate is the wider resolvable set, not in-effect. New `PersistenceExceptionTranslationService` entries cover `uq_archetype_id`, the 8 `uq_t_definition_version` constraints, **and the `definition_external_identity` PK/UNIQUE** — the stem race that slips past the §CLAIM check between CREATE and first APPROVED surfaces as that constraint and must map to 409, not 500 | 409 |
| `ARCHETYPE_GSM_BASE_BY_ID` | GSM-base detection (`isGsmBaseTitle`, `resolveGsmBases` convergence checks) re-anchored on the full `$id` `gsmarc://gsm/{Title}/v{n}` — today `gsmarc://acme/x/Structure/v1` is misread as the sealed base by last-segment matching | 400 |
| `ARCHETYPE_COMPOSITION_PROPERTY_COLLISION` | DM-side mirror of catalogue lint **L8** (study §2.4/PP5 — today the check exists only in `gsm-frameworks/scripts/lint_layers.py`, so an archetype submitted directly to the DM bypasses it). On Archetype submission, after resolving the `allOf` entries (`ArchetypeCompositionValidationService` already walks them for cycle/base-convergence): (a) reject when two **sibling** `allOf` facets declare the same top-level property name — JSON Schema intersects, it does not namespace, so same-name siblings either conflate one JSON slot across two meanings or are unsatisfiable (proven: `availabilityTarget` string-enum ∩ number accepts no value); (b) reject when the host redeclares an inherited property with a **different `type`** — a silently unsatisfiable vertical redefinition. Same-type vertical narrowing remains legitimate refinement and is accepted; the facet **mount rule** (each facet under an object-valued property keyed `lowerCamelCase(facetTitle)`) is the sanctioned multi-facet form and composes cleanly under this rule. Scope matches L8: top-level property names of resolved in-repo/DM-resolvable facets; unresolvable external refs are outside this rule (covered by `ARCHETYPE_REF_NORM`) | 400, field `allOf` |

Implementation home: `ArchetypeParsingService` (grammar + component extraction),
`AscriptionParsingValidationService` (structure + coherence at parse), DB trigger
(version assignment, §2.2) with `AscriptionStatusTransitionService` performing the
post-flush reconciliation read, `AscriptionUniquenessValidationService` +
`ArchetypeService.validateActivationUniqueness` (activation rules),
`ArchetypeService` (identity-bound values, base detection),
`ArchetypeCompositionValidationService` (sibling-property collision, reusing its
existing `allOf` resolution walk), `PersistenceExceptionTranslationService` (new
constraint mappings).

Concurrency note: sibling auto-termination at approval (`handleApproval`: DRAFT →
ABANDONED, PROPOSED → REJECTED) removes *lifecycle* races for the ordinal, but the
trigger flush precedes sibling termination, so DB-level constraint races remain
possible; they surface as `(definition_id, version)` / `uq_archetype_id` violations
and are translated to 409 (not 500) via the exception-translation entries above.

**Phase-1 meta-schema amendment (gsm-specifications) — prose only, deliberately**:
`Archetype.schema.json` recursively applies at **every schema node**, not only the
statement root (`$dynamicAnchor: "meta"` + the 2020-12 meta-schema's subschema
positions recursing via `$dynamicRef: "#meta"`; normative per GSM-PROC-13). Adding
root-level `required: ["$id", "title"]` or constrained `properties.$id`/`.title`
would therefore demand them on every *nested* subschema of every archetype —
breaking all 92 catalogue archetypes and the 8 seeds. Root-only enforcement cannot be
expressed by adding root keywords to a dynamically-recursive meta-schema; mandatory
`$id`/`title` is enforced **solely by `ARCHETYPE_ID_STRUCTURE`** (which fully covers
it). Deliberate portability boundary: a third party validating a published
archetype against `Archetype.schema.json` alone gets no `$id` obligation — the
portable channel for that requirement is GSM-PROC conformance, not the meta-schema.
The meta-schema amendment is scoped to its `description` prose: remove "DM
assigns `$id` … `gsmarc://gsm/{title}/v{version}`" (contradicts producer-claimed AND
hard-codes the `gsm` authority), reword "title … globally unique" to defer to the
identity grammar, and remove "title is used as the root identifier in Norm CEL
applicability expressions" (false from Phase 2). While amending: the
`Norm.schema.json` assertion examples AND the `assertion` property's own description
("references exactly one Archetype by name as root identifier") use title-rooted
forms the assertion validator rejects — correct both. `conformance.md`: `GSM-PROC-10`
is amended in Phase 1; **`GSM-PROC-11` (global title uniqueness) stays normative
through Phases 1–2 and is amended only with Phase 3a**, when the DM actually stops
enforcing it. `GSM-PROC-4` says "monotonic per Definition" — §2.2 implements
monotonic **and gapless**, a deliberate strengthening to record in the same
conformance pass so the catalogue does not drift the other way.

### 2.4 Resolution

The DM has **three** title lookups with distinct status sets; each gets an `$id`
counterpart:

| Legacy | Status set | `$id` counterpart |
|---|---|---|
| `findInEffectByTitle` (CEL roots — untouched until Phase 2) | ACTIVE+DEPRECATED | `findInEffectById` |
| `findResolvableByTitle` (authoring-time nested-`$ref` resolution) | all non-terminal | `findResolvableById` — deterministic a fortiori: `uq_archetype_id` covers a superset of the non-terminal set, so at most one claimant per full `$id` (§2.3) |
| `ArchetypeService.resolveArchetypeSchema` ($ref chain walking) | ACTIVE+DEPRECATED | two modes: new-typing selection via `findInEffectById` (in-effect only); pinned re-validation of governed statements via `findResolvablePinnedById` (APPROVED-or-later, below) |

**Resolution status semantics** (fixes the "validates forever" guarantee): a
**fourth status set** is introduced — *resolvable-pinned* = **APPROVED-or-later** =
{APPROVED, ACTIVE, DEPRECATED, SUSPENDED, RETIRED}. This set is permanence-sound by
the §2.2 proof: no post-APPROVED edge leads to ABANDONED or REJECTED, so membership
can never be lost — whereas DRAFT/PROPOSED have live edges into the terminal-failed
statuses and are deliberately excluded (pinned refs never resolve ungoverned
content). SUSPENDED membership is *forced* by permanence (ACTIVE → SUSPENDED is a
live edge); APPROVED as the floor is exact because reconciliation verifies the `$id`
claim *at* APPROVED. Pinned-`$ref` re-validation of existing compositions uses
resolvable-pinned via a third repository method, `findResolvablePinnedById` —
deterministic directly: `uq_archetype_id` (`WHERE status NOT IN
('ABANDONED','REJECTED')`, §2.6) covers the whole resolvable set, so at most one
row per full `$id` exists there — this alone is sufficient for `LIMIT 1` soundness.
Independently (belt-and-braces, not load-bearing): the stem registry +
`UNIQUE (definition_id, version)` + reconciliation-at-APPROVED imply the same for
APPROVED-or-later rows (GSM-PROC-5 forbids a second APPROVED per Definition), and
pre-V2 APPROVED rows get claim ≡ ordinal from the §2.6 step-0 guard.
**Selection of a NEW typing archetype** (ascription creation,
`archetype_id` resolution) remains restricted to in-effect — the enforcement point
that prevents typing new statements on a RETIRED base. Stems are permanently
non-reusable (`definition_external_identity`, §2.3), so a resolved `$id` can never
silently change meaning. (Status-set footnote: the legacy `findResolvableByTitle`
set is the complement of {RETIRED, ABANDONED, REJECTED} — "all non-terminal" reads
correctly only if RETIRED counts as terminal; resolvable-pinned differs from it by
exactly {RETIRED in, DRAFT/PROPOSED out}.)

Repository query shape (matching the existing native-query conventions):

```java
@Query(nativeQuery = true, value =
    "SELECT * FROM archetype WHERE statement->>'$id' = :id"
    + " AND status::text IN (:statuses) LIMIT 1")
Optional<ArchetypeEntity> findFirstByStatementIdAndStatusIn(
    @Param("id") String id, @Param("statuses") Collection<String> statuses);
// name deliberately avoids shadowing Spring Data's derived query on the UUID id
```

plus a plain (non-partial) expression index `ix_archetype_id ON archetype
((statement->>'$id'))` for the all-status lookups — the partial unique index (§2.6)
serves the constraint, not these queries.

Additionally the networknt `schemaMappers` in `AscriptionParsingValidationService`
(two sites) currently map `gsmarc://gsm/…` to
`classpath:gsm/schemas/{firstSegmentAfterAuthority}.schema.json` — correct only
because the `gsm` authority has an empty namespace path, and version/namespace
blind (`gsmarc://gsm/Structure/v99` or `gsmarc://gsm/x/Structure/v1` would resolve
the bundled v1). Replace both with an **exact-match table of the 8 seeded base
`$id`s** → classpath resources; any other `gsm`-authority ref is an error, not a
fuzzy match.

**REST/DTO surface (Phase 1)**: `AscriptionDto` (+ mapper + the hand-built OpenAPI
response schema in `AscriptionController`) exposes the new `version`; the ascription
list endpoint gains an exact-`$id` filter alongside the existing title filter. This
is what lets sie-operator's `ArchetypeAscriptionDto.version` finally be supplied.

### 2.5 Dual-mode resolution rule (transition invariant)

**Breaking-change disclosure**: Phase 1 is non-breaking **for resolution** (§2.5
steps below guarantee nothing that resolves today stops resolving) but IS breaking
**for authoring**: `ARCHETYPE_ID_STRUCTURE` rejects new Archetype submissions without
a grammar-conformant `$id` — input the DM accepts today (PP4). This is the point of
the rule; it is stated here so "self-contained" is not read as "invisible". The V2
assert-guard (§2.6) similarly aborts deployment on `$id`-less legacy rows.

For any `$ref` during Phases 1–2:

1. If the ref matches the identity grammar → resolve by full `$id` first.
2. **On miss** → fall back to legacy last-segment title resolution, logged at WARN
   with a `legacy-id-fallback` marker carrying the requested `$id` and the resolved
   archetype's actual `$id`.
3. Refs not matching the grammar → legacy title resolution directly (unchanged
   `$ref` URI policy — rule `ARCHETYPE_REF_NORM`, enforced via `isAllowedRef`).

Rationale: the WARN telemetry then *measures something real* — refs whose pinned
version (or namespace) does not literally resolve, i.e. version-claim or namespace
drift between authored refs and stored `$id`s. Phase 3 flips step 2 to a hard error,
gated on a zero-WARN observation window. This keeps Phase 1 strictly additive
(nothing that resolves today stops resolving), at the documented cost of a lenient
window (study §5.3.6).

### 2.6 Storage and migration

`V2__ascription_version_and_archetype_id.sql` — replicated over the **8 concrete
ascription tables** (TABLE_PER_CLASS; there is no `ascription` relation). Shape, per
table `t`:

```sql
-- 0. preflight guard, FIRST (the $id assertions are ARCHETYPE-ONLY per the §1b
--    scope note; only the version materialization below is ×8): ASSERT $id
--    presence, grammar conformance, title coherence, no duplicate stems across
--    definitions AND no multiple stems within one Definition, no duplicate full $ids within a lineage, and claim ≡ ordinal —
--    the ordinal recomputed with the same
--    ROW_NUMBER() ... ORDER BY timestamp, id expression (the version column does
--    not exist yet at step 0). Guard status scope = the index predicate
--    (NOT IN ('ABANDONED','REJECTED')): narrower would let step 2's index creation
--    fail with the opaque error the guard exists to prevent; wider would abort on
--    legitimately REJECTED rows. Cross-definition full-$id duplicates are covered
--    transitively by the stem check. Illustrative presence check:
DO $$ BEGIN
  IF EXISTS (SELECT 1 FROM archetype WHERE statement->>'$id' IS NULL) THEN
    RAISE EXCEPTION 'archetype rows without $id require manual review';
  END IF;
END $$;

-- 1. version materialization (§2.2): column, backfill, trigger, constraint — ×8 tables
ALTER TABLE t ADD COLUMN version integer NOT NULL DEFAULT 0;
UPDATE t SET version = n.rn
FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY definition_id
                                    ORDER BY timestamp, id) rn   -- deterministic tie-break
      FROM t
      WHERE status IN ('APPROVED', 'ACTIVE', 'DEPRECATED', 'SUSPENDED', 'RETIRED')) n
WHERE t.id = n.id;
CREATE UNIQUE INDEX uq_t_definition_version ON t (definition_id, version)
  WHERE version > 0;
-- trigger assign_version_on_approved: BEFORE UPDATE (the WHEN clause dereferences
--   OLD, legal only on UPDATE — precisely why the seed runner's INSERT never fires it)
--   WHEN (OLD.status <> 'APPROVED' AND NEW.status = 'APPROVED')
--   version := 1 + max(version) over definition_id
-- (same install pattern as the existing 6 per-table triggers; its WHEN scoping is
--  why the seed runner's direct INSERT-as-ACTIVE never fires it — no runner
--  disable-list extension needed)

-- 1b. identity registry (§2.3) + backfill from approved archetypes
--    (scope note: only Archetype-typed Definitions ever receive a stem — $id is
--    mandated on Archetype statements only; partial population is by design)
CREATE TABLE definition_external_identity (
  stem          text PRIMARY KEY,
  definition_id uuid NOT NULL UNIQUE REFERENCES definition(id)
);

-- 2. exact-$id claim + uniqueness (archetype table only; additive —
--    uq_archetype_title REMAINS until Phase 3a; runs AFTER the step-0 guard so a
--    violating dataset aborts with the reviewed message, not an opaque index error).
--    Scope = everything resolvable (all statuses except the terminal-failed two):
--    this is the durable DB backing that makes the CREATE-time claim race-safe.
CREATE UNIQUE INDEX IF NOT EXISTS uq_archetype_id
  ON archetype ((statement->>'$id'))
  WHERE status NOT IN ('ABANDONED', 'REJECTED');
CREATE INDEX IF NOT EXISTS ix_archetype_id ON archetype ((statement->>'$id'));

-- (guard context: the 8 gsm seeds already carry $id — loaded by
--  ArchetypeSeedRunner from classpath:gsm/schemas/*.json; no tenant data predates
--  this migration in any deployment. On failure the migration aborts: minting $id
--  into an immutable statement is a manual, reviewed operation, not a blind
--  backfill.)
```

Also updated in V2: the `ascription_all` union projection gains the `version`
column. The grammar's unbounded version production is bounded operationally: the DM
rejects claims exceeding the `integer` range at parse (no `bigint` migration — a
lineage approaching 2^31 versions is not a realistic governance object).

**Seed-runner interaction (ordering matters)**: Flyway V2 runs **before** the
`ApplicationRunner`, so on a clean database the backfill sees zero rows and cannot
number the seeds; and the seeds are inserted directly as ACTIVE with status-sync
triggers disabled, so the APPROVED trigger never fires for them either. Phase 1
therefore **changes the seed insert** to set `version = 1` explicitly and to insert
the 8 `definition_external_identity` stem rows in the same bootstrap transaction.
The V2 backfill covers only pre-existing rows on already-initialized databases. Two known
limitations are accepted and documented: (a) the runner's global skip-if-any-exists
means amended base schemas (e.g. the §2.3 meta-schema amendment) do NOT reach
already-initialized databases via seeding — base-schema evolution needs the normal
governance path or a dedicated migration, named as a Phase-2 work item; (b) the
runner's title-keyed load map is single-version by construction — acceptable until a
base reaches v2, at which point (a)'s mechanism applies anyway.

No statement mutation: the previous draft's backfill both referenced a non-existent
`version` column and violated statement immutability; superseded by the assert-guard
above.

### 2.7 Catalogue prerequisite (gsm-frameworks) — **landed**

Lint L5 hard-coded `…/{stem}/v1`, which would have forbidden the catalogue's own
documented `/v1 → /v2` bump policy and made every re-ingestion unpublishable under
reconciliation. Generalized to `/{stem}/v[1-9][0-9]*` (anchored `re.fullmatch`) on
2026-08-22; catalogue lint clean.

### 2.8 Tests (JaCoCo ≥ 95% maintained)

- grammar accept/reject table (uppercase authority → reject, edge/double-hyphen
  authority (`-acme`, `acme-`, `a--b`) → reject, empty namespace path for `gsm` →
  accept, non-PascalCase title, `v0`, `v007`, missing `/v`, version beyond integer
  range → reject);
- coherence positive/negative; stem immutability across versions (identity-bound
  machinery); reconciliation (claim = assigned; claim ≠ assigned → 400 naming both +
  the REJECT-and-re-author remediation message);
- authority reservation: REST claim under `gsm`/`gsm-frameworks` → 400; positive
  paths: seed-runner `gsm` claim accepted; catalogue-import channel `gsm-frameworks`
  claim accepted (a reject-all implementation must fail these);
- claim uniqueness at CREATE: duplicate full-`$id` claim by a different Definition →
  409; duplicate claim by a **same-Definition DRAFT/PROPOSED sibling** → 409;
  re-claim after prior claimant is ABANDONED or REJECTED → accepted; re-claim
  against a RETIRED claimant → 409; stem registered to another Definition → 409;
  DB race (two concurrent CREATEs) → loser 409 via index;
- version materialization: trigger assignment at APPROVED, `version = 0` before,
  pre-APPROVED terminals stay 0, `(definition_id, version)` uniqueness under
  concurrent approval attempts (race loser → 409, not 500), backfill numbering with
  `(timestamp, id)` tie-break — **across all 8 concrete tables**;
- registry: stem inserted at first APPROVED; permanently blocks reuse after RETIRED;
- composition collision (L8 mirror): sibling `allOf` facets sharing a property name
  → 400; host redeclaring an inherited property with a different type → 400;
  same-type vertical narrowing → accepted; mount-style composite (facets under
  object-valued properties) → accepted;
- resolution: pinned older version resolves while a newer one is ACTIVE; RETIRED,
  SUSPENDED, and APPROVED-not-yet-ACTIVE versions still resolve read-only for pinned
  refs; grammar-miss falls back with WARN marker (asserted present); non-grammar ref
  bypasses `$id` path;
- schema-mapper exact-match: each of the 8 base `$id`s maps to its classpath seed;
  `gsmarc://gsm/Structure/v99` errors instead of resolving v1;
- bootstrap: clean database → seeds ACTIVE with version 1 (set explicitly by the
  seed insert, §2.6) + stems registered in the same transaction;
  already-initialized database → seeding skipped, V2 backfill covers existing rows;
- REST: `AscriptionDto`/OpenAPI expose `version`; exact-`$id` filter returns the
  pinned ascription;
- flag-off invariant (§5): approval of an `$id`-less archetype with
  `gsm.identity.id-validation.enabled=false` → rejected by the registry insertion;
  conformant one → approved with registry row present;
- migration test: version backfill numbering + preflight guard (both branches).

## 3. Phase 2 — Norm `bindings`

### 3.1 Grammar amendment (gsm-specifications)

`Norm.schema.json` gains:

```json
"bindings": {
  "type": "object",
  "description": "Local-name bindings for applicability roots: {localName: archetype $id}. Assertions never use bindings (qualifier-bound).",
  "additionalProperties": { "type": "string", "pattern": "^gsmarc://" },
  "propertyNames": { "pattern": "^[a-z][A-Za-z0-9]*$" }
}
```

Prose amendments: Norm section (bindings semantics; applicability roots resolve
local→`$id`→schema), CEL applicability profile (roots MUST be bound local names once
Phase 3 lands; title roots deprecated from Phase 2), Archetype section (identity
grammar from §2.1, title = local-name component, uniqueness scoped by namespace after
Phase 3) — including `specification.md` §9.1's title bullet "is the root identifier
used in Norm CEL expressions (§14.1)", the normative twin of the schema-description
sentence removed in Phase 1 (its "globally unique" clause correctly stays until 3a). Sync to defman vendor via `make sync-gsm-schemas`;
`GsmSchemaVendorSyncTest` gates drift.

### 3.2 DM changes

- `NormApplicabilityValidationService`: root resolution order — (1) binding local
  name → `findFirstById` (in-effect); (2) legacy title lookup (WARN, removed Phase 3).
  Property-path validation unchanged (resolves against the bound schema).
- Binding validation: every binding value must satisfy the identity grammar and
  resolve in-effect at Norm activation; unused bindings are a validation warning,
  unbound roots falling back to title lookup are logged.
- **Lifecycle coupling**: bound Archetypes become governed **References** —
  `NormService.getRefereeReferences` (today: Structure + qualifier only) extends to
  include every binding target, so suspension/retirement cascades treat bound
  archetypes exactly like the qualifier. This keeps referee semantics uniform instead
  of inventing a weaker historical-resolution mode for bindings.
- Axis semantics: the existing one-predicate-per-`(Archetype, propertyPath)` rule
  keys its axis on the **resolved `$id`**, not the local name — two local names
  binding the same `$id` still form one axis. The applicability profile's
  lowercase-root convention is preserved by the `bindings` key pattern
  (`^[a-z][A-Za-z0-9]*$`), and does not collide with the assertion profile's
  uppercase-root rejection.

### 3.3 Catalogue adoption

`gsm-frameworks` statement authors migrate applicability expressions
(`GdprControllerProcessorRole.role …` → binding `role: gsmarc://…/GdprControllerProcessorRole/v1`
+ `role.role …`); descriptor-level tooling + lint L9 (roots must be bound) added at
adoption time — not part of this spec's DM scope.

## 4. Phase 3 — title demotion (3a: DM-side; 3b: Operator-side — coordinated, not independent)

### 4.1 — Phase 3a, DM-side

Gate (single definition, referenced everywhere): zero `legacy-id-fallback` WARNs over
the §5 observation window, **and** catalogue lint L9 adopted (owned by the
gsm-frameworks maintainers; "independent timeline" in §5 means L9 work can *start*
anytime — 3a still waits for it), **and** the operator's dual-read deployed with
`$id`-first precedence (title compared only when no `$id` is present — "title OR
`$id`" without precedence is undefined under title collisions).

1. `V3__drop_global_title_uniqueness.sql`: drop `uq_archetype_title` — sound because
   the `definition_external_identity` registry + `ARCHETYPE_ID_CLAIM_UNIQUENESS`
   (§2.3) already guarantee one stem per Definition (full-`$id` uniqueness alone
   would not: it is version-inclusive).
2. Flip §2.5 step 2 from WARN-fallback to hard error; tighten the `$ref` URI policy
   (`ARCHETYPE_REF_NORM` / `isAllowedRef`) to the §2.1 grammar behind a data check.
3. Retire the remaining title-keyed internals: governance-chain machinery
   (`getAncestorTitles`/`collectAncestorTitles`/`isDescendantOf` → stems),
   `ArchetypeCompositionValidationService` — its cycle-detection (`visited`) and
   base-convergence (`resolvedBases`) sets are title-keyed and would collapse
   same-titled cross-namespace archetypes into one node (spurious acyclicity/
   convergence failures) → re-key on `$id`/stem,
   `MechanismPortDerivationService` anchors and rule-declared data-archetype
   names (→ `$id`s / rule-local bindings), `MechanismRuleValidationService` archetype
   names, `AscriptionService:495`. `extractTitleFromRef` survives only as a
   display-name helper. The ascription list endpoint's title filter gains explicit
   namespace-ambiguity semantics (returns all namespaces' matches; `$id` filter is
   the precise contract).
4. `ArchetypePropertyIndexationService`: dynamic index names derive today from
   truncated title + property with `IF NOT EXISTS` — duplicate cross-namespace local
   names would silently share an index name and drop each other's indexes on
   deactivation. Index naming moves to a collision-resistant hash of the stem before
   the title-uniqueness drop.
5. `title` remains: required, identity-bound by construction, display + local name.

### 4.2 — Phase 3b, Operator-side (coordinated with 3a via dual-read)

sie-operator `sys.receive`/`sys.effect` dispatch and `OperationFrameDto` frame
matching compare **titles** today, and derived Effector/Receptor statements carry
archetype **UUIDs** (not titles or `$id`s) — so this is NOT a string-format-only
change. Contract: a dual-read window in which the operator matches on title OR `$id`
(both supplied by the DM API from Phase 1, which also finally supplies the operator
DTOs' `version` field), then a coordinated cutover with 3a's rule-name migration.
Rule-local binding representation for Starlark (mirroring Norm `bindings`) is
specified operator-side.

## 5. Rollout, risks, rollback

| Risk | Mitigation |
|---|---|
| `$id`-less legacy rows at migration time | V2 preflight guard aborts; minting into an immutable statement is a manual reviewed operation, never a blind backfill |
| Authority spoofing (endpoints are public; tenant identity untrusted) | Phase 1 enforces reserved-authority rejection (§2.3); general tenant↔authority binding is a **named dependency on the security roadmap** — until authn lands, non-reserved namespaces are first-claim-wins via the registry |
| Dual-mode divergence (title path vs `$id` path resolving differently) | §2.5 fallback-on-miss + WARN telemetry; Phase 3a gated on zero WARN occurrences over an observation window — defined as: one full CI suite + 30 days (or the interval between two catalogue ingestions, whichever is longer) in the reference deployment, evidenced by a counter metric on the `legacy-id-fallback` log marker paired with a successful exact-`$id` resolution counter as denominator (zero fallbacks + zero resolutions = broken telemetry, not success) |
| Catalogue claims `/v1` but DM assigns ≠ 1 (re-ingestion) | catalogue policy already bumps `/v1 → /v2` per published-version discipline; §2.7 (landed) makes the bump expressible in lint; the widened `uq_archetype_id` rejects the stale claim at CREATE (409, before any transition); reconciliation remains the over-claim backstop at APPROVED |
| CEL migration stalls (Phase 2→3 gap) | title roots keep working with WARN until Phase 3; catalogue-side L9 lint (roots must be bound) is owned by the gsm-frameworks maintainers and drives migration independently of the DM timeline |

Rollback: **forward-repair, not destructive**. New validation rules are switchable
via config flag `gsm.identity.id-validation.enabled` (default on; Phase 1 only) —
disabling it restores pre-Phase-1 authoring behavior without schema surgery.
Gated: the service-level consistency rules (§2.3). Ungated (DB-side, always on):
`uq_archetype_id`, the 8 `uq_t_definition_version` constraints, the version trigger,
and the first-APPROVED registry insertion — which **rejects the approval** when the
statement lacks a grammar-conformant `$id` even with the flag off (otherwise a
flag-off window could mint registry-less Definitions and re-enabling WOULD require a
stem backfill; this rejection is the invariant that keeps re-enable clean). Error
code: the missing-`$id` condition is caught before insert and mapped 400 (it is a
consistency failure, not a conflict); only genuine registry PK/UNIQUE races map 409.
(Postgres treats NULLs as distinct, so `$id`-less rows created flag-off never collide
on `uq_archetype_id` — the ungated index coexists with the gated rules, and the
first-APPROVED registry rejection is the real gate.)
Mixed-version API consumers see `version` as an additive DTO field (older clients
ignore it).

## 6. Acceptance criteria

- [ ] All §2.3 rules enforced with tests; JaCoCo ≥ 95% module level.
- [ ] Version materialization demonstrated per §2.2 (trigger-assigned, 0-before,
      gapless per Definition, backfill correct) — across **all 8** concrete tables.
- [ ] `definition_external_identity` registry: first-APPROVED insertion, permanent
      non-reuse (incl. post-RETIRED), claim-uniqueness integration.
- [ ] Meta-schema amendment: `Archetype.schema.json` `description` prose de-minting
      **only** — no root keywords added (see §2.3: per-node recursion makes
      `required`/`properties` fatal); `Norm.schema.json` assertion examples AND
      `assertion` property description; `conformance.md` GSM-PROC-10 (amend) +
      GSM-PROC-4 (strengthen to gapless); **not** GSM-PROC-11; plus the
      normative processor/producer `$id`-assignment clauses in `specification.md`
      (§processor obligations ~L287, §producer obligations ~L516) and `GSM-PRD-2`
      in `conformance.md` — all say the DM assigns `$id` and/or hard-code the `gsm`
      authority; left unamended they contradict producer-claimed. Merged in
      gsm-specifications + vendored sync green — hard Phase-1 dependency.
- [ ] `conformance.md` **GSM-PROC-11** (global title uniqueness) amended **with
      Phase 3a only** — it stays true and DM-enforced through Phases 1–2 (§2.3).
- [ ] Pinned-version resolution demonstrated: an older version resolves by `$id`
      while a newer one is ACTIVE; RETIRED resolves read-only.
- [ ] Claim lifecycle demonstrated on catalogue-shaped input: stale `/v1` re-claim
      rejected at CREATE (409); over-claim (gap-skip ordinal) rejected by
      reconciliation at APPROVED (400) including the REJECT remediation message.
- [ ] WARN fallback path exercised by test (grammar-matching ref, absent `$id`),
      proving the telemetry fires; zero WARNs across the rest of the suite.
- [ ] Schema mappers replaced by the exact-match base-`$id` table, covered by test.
- [ ] REST/OpenAPI surface exposes `version` + exact-`$id` filter; sie-operator DTO
      `version` suppliable.
- [x] Lint L5 generalized in gsm-frameworks (§2.7) — landed 2026-08-22.
- [ ] `gsm.puml` amendments: §Identity matches §2.1; `$id` authorship changed to
      producer-claimed/DM-verified (§2.3); §Ascription version semantics unchanged
      (§2.2 implements them as written).
- [ ] gsm-specifications Phase 2 amendments merged + vendored sync green —
      including `specification.md` §9.1's title bullet ("root identifier used in
      Norm CEL expressions"), the normative twin of the Phase-1 schema-description
      de-mint.
