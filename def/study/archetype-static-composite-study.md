# Deferred Study — Archetype as Static Structure Composite

> Parked 2026-08-23. Not in the `$id` identity work packages.
> Companion: [identity-model-study.md](identity-model-study.md).
> Sibling park: [purpose-function-non-uniqueness-study.md](purpose-function-non-uniqueness-study.md).
> Vendor-neutral semantics:
> [GSM Archetype referential model](https://github.com/poesis-cloud/gsm-research-lab/blob/main/docs/archetype-referential-model.md#5-static-composition-is-not-operational-composition).
>
> Owner decisions already taken for this park:
>
> - Do **not** mint a GSM Interaction for schema `$ref` / `allOf`.
> - Do **not** reverse-cascade Archetype retire onto dependents.
> - Current identity WP: `getRefereeReferences()` from a schema walk; ordinary
>   Referee consistency; no Cascade; no new primitive.

## 1. Why this processor study is parked

The GSM study owns the vendor-neutral semantics of static composition,
references, lifecycle eligibility, and the no-Interaction/no-reverse-cascade
owner direction. This companion keeps the Definition Manager design questions
needed to realize or query those semantics.

An Archetype is already a **static composite**: a JSON Schema whose `$ref` /
`allOf` graph names other Archetypes. That graph is **structurally parallel**
to a Structure's Mechanisms and Interactions, but it is **not** those
primitives.

The identity WP only needs:

1. Discover References by walking schema-valued `$ref` (extend) and `allOf`
   `$ref` (facets).
2. Treat those targets as ordinary Referees on the dependent’s CREATE /
   SUBMIT / APPROVE / ACTIVATE.
3. One `$ref` **eligibility matrix** (today the four surfaces disagree).

Everything else — property as a first-class static slot, derived relation
table, `referencedby` index, retire policy beyond “no reverse cascade” — waits
here.

## 2. Dynamic vs static

| Dynamic (runtime Structure) | Static (Archetype statement)                         |
| --------------------------- | ---------------------------------------------------- |
| Structure                   | Archetype (the composite model)                      |
| Mechanism                   | **Property** (a named slot / capability of the type) |
| Interaction                 | **Relation** (a typed edge to another Archetype)     |
| Ports derived from rule AST | `$ref` / `allOf` already in the statement            |
| DNA (Directive / Norm)      | not analogous — DNA governs instances                |

This is a **Definition-Manager derived view**, like ports from Starlark AST.
It is **not** a new GSM primitive and **not** an Interaction object. GSM
Interaction is an operational coupling (effector → receptor) between
Structures. A type-graph edge is not that.

Optional later materialization:

- `property` rows parallel to Mechanism (name, type, required, …)
- `relation` rows parallel to Interaction (`extend` vs `facet`, target stem /
  `$id`, JSON Pointer)

Both are a **pure function of the sealed statement**. If they exist, they are
recomputed, not independently authored.

## 3. Consistency vs cascade

Already true without this study:

- **Referee**: the dependent cannot APPROVE / ACTIVATE unless each referenced
  Archetype is in the allowed target-status set.
- Already-ACTIVE snapshots stay. A later retire of the target does **not**
  rewrite history.
- **No reverse cascade** on Archetype retire (owner decision). Consumers keep
  their pin. New dependents cannot compose against a target that the
  eligibility matrix rejects.

Contrast with existing cascade kinds:

| Kind            | Example                                  | On parent terminal        |
| --------------- | ---------------------------------------- | ------------------------- |
| Constitutive    | Mechanism → ports                        | **blocks**                |
| Governing       | Structure → Mechanism / Directive / Norm | **no-op** (children live) |
| Dependent       | ports → Interaction                      | degrade / terminal only   |
| Archetype graph | `$ref` / `allOf`                         | **none** (this park)      |

References ≠ Cascades. Discovering a `$ref` does not create a Cascade role.

## 4. Confluent vs GSM

Useful analogy, not a transplant.

| Confluent Schema Registry         | GSM Archetype                           |
| --------------------------------- | --------------------------------------- |
| subject + version                 | Definition (stem) + Ascription version  |
| schema id stays after soft-delete | Archetype URI remains resolvable        |
| `referencedby` lookup             | optional later index                    |
| compatibility check on evolve     | identity-bound + composition validation |
| no consumer cascade on delete     | no retire → dependent cascade           |
| subjects are registry entries     | Definitions are GSM primitives          |

Do **not** import Confluent’s subject registry as a 9th table. Stem lives on
the Ascription `$id`; uniqueness is “one Definition owns the stem.”

## 5. `$ref` eligibility tension (feeds the identity WP)

Four surfaces are **not** uniform today:

| Surface                                    | Lookup                  | Allowed target status (code / current spec)                                     |
| ------------------------------------------ | ----------------------- | ------------------------------------------------------------------------------- |
| Exact Archetype URI read                   | version-pinned row      | any **resolvable** row (version > 0), including RETIRED                         |
| Typing (`AscriptionCreationDto.archetype`) | in-effect               | `ACTIVE` \| `DEPRECATED`                                                        |
| CREATE / SUBMIT referees                   | Definition + status set | CREATE {DRAFT, PROPOSED, APPROVED, ACTIVE}; SUBMIT {PROPOSED, APPROVED, ACTIVE} |
| APPROVE / ACTIVATE referees                | same                    | APPROVE {APPROVED, ACTIVE}; ACTIVATE {ACTIVE}                                   |

Historical pins need to name a **DEPRECATED** (sometimes RETIRED) `$id`.
Typing already allows DEPRECATED. APPROVE/ACTIVATE referee sets do **not**.

Identity WP must publish **one matrix** and test it. This study may later
justify a wider authoring set (pin DEPRECATED for composition) without
widening ACTIVATE if activation should require a live type. That split is
allowed; pretending the four surfaces already match is not.

## 6. What this study must decide later

1. Persist derived `property` / `relation` rows, or keep them as a read-time
   walk of the statement?
2. If persisted: recompute on every APPROVED statement; never independently
   authored; delete/rebuild is fine (not an audit ledger).
3. `referencedby` index for impact analysis — read model only.
4. Whether RETIRED is pin-eligible for **authoring** vs **activation**.
5. Whether a retired target should **warn** new dependents or only fail the
   matrix.
6. Still no Interaction primitive; still no reverse cascade unless a later
   owner decision reverses §1.

## 7. Out of scope here

- Purpose / function uniqueness (sibling park).
- Identity registry / authority policy / Archetype CREATE exception
  (rejected in the identity study).
- Changing GSM’s eight primitives.
