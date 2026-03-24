I've now read both files in their entirety. Here is the comprehensive rule extraction.

---

# GSM Rule Extraction — Complete Catalog

## FILE 1: gsm.puml

---

### 1. Definition

#### Validation Rules

| ID                | Rule                                                       |
| ----------------- | ---------------------------------------------------------- |
| **Definition-V1** | `id` MUST NOT be null.                                     |
| **Definition-V2** | `subjectType` MUST be a valid DefinitionSubjectType value. |
| **Definition-V3** | `subjectType` MUST NOT be modified after creation.         |

#### Assignment Rules

| ID                | Rule                                                                 |
| ----------------- | -------------------------------------------------------------------- |
| **Definition-A1** | `id` is assigned at creation time and MUST NOT be modified.          |
| **Definition-A2** | `subjectType` is assigned at creation time and MUST NOT be modified. |

---

### 2. Ascription

#### Validation Rules

| ID                | Rule                                                                                                                                                                                                                                                                                                                                       |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Ascription-V1** | `statement` MUST validate against the JSON Schema stored in the referenced Archetype's `schema` property (`archetype.statement.schema`).                                                                                                                                                                                                   |
| **Ascription-V2** | **Definition identity invariant**: identity-bound `statement` fields MUST equal the value from the Definition's first Ascription. To change an identity-bound field, author a new Definition. Identity-bound fields are declared via `$gsm:identityBound` annotation. The identity-bound set itself is immutable per Archetype Definition. |
| **Ascription-V3** | `$gsm:identityBound` — **all subtypes**: rejects if value differs from first Ascription of same Definition.                                                                                                                                                                                                                                |
| **Ascription-V4** | `$gsm:unique` — **extensible subtypes**: validates value uniqueness among in-effect (ACTIVE/DEPRECATED) Ascriptions of same Archetype. Rejects on violation. Also DB-enforced via partial unique expression index.                                                                                                                         |
| **Ascription-V5** | `$gsm:validation` — **extensible subtypes**: evaluates all CEL expressions declared in the keyword with `statement` as `this`. All MUST return true. Rejects on failure.                                                                                                                                                                   |
| **Ascription-V6** | `$gsm:sensitive` — **extensible subtypes**: (a) masks value in audit logs and `statusTransitions` context at authoring time, (b) encrypts at rest, (c) redacts in API responses unless caller holds explicit access scope/role.                                                                                                            |
| **Ascription-V7** | `$gsm:queryable` — **no Ascription authoring impact**. Indexes provisioned at Archetype activation.                                                                                                                                                                                                                                        |
| **Ascription-V8** | `$gsm:sealed` — **no Ascription authoring impact**. Enforced at Archetype authoring time.                                                                                                                                                                                                                                                  |

#### Assignment Rules

| ID                | Rule                                                                                                                                        |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| **Ascription-A1** | When creating a new Ascription for the same subject, reference the same Definition.                                                         |
| **Ascription-A2** | Immutability (snapshot): once an Ascription row is created, `id`, `definition`, `archetype`, and `statement` MUST NOT be modified in place. |
| **Ascription-A3** | Once `version` is assigned at APPROVED transition, it MUST NOT be modified.                                                                 |
| **Ascription-A4** | `version` is auto-assigned atomically by a DB trigger when the APPROVED transition is recorded — never set by application code.             |
| **Ascription-A5** | Before APPROVED transition, `version` MUST be 0.                                                                                            |
| **Ascription-A6** | `version` MUST be monotonically increasing (starting from 1) within the APPROVED lineage for each Definition.                               |
| **Ascription-A7** | `status` is auto-assigned atomically by a DB trigger when a transition is recorded — never set by application code directly.                |
| **Ascription-A8** | `timestamp` is auto-assigned by the database at insert time — never set manually. Immutable once assigned.                                  |

#### State Rules

| ID                | Rule                                                                                                                           |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| **Ascription-S1** | See `gsm-ascription-lifecycle` state machine diagram for valid transitions, preconditions, cascades, and audit-log invariants. |
| **Ascription-S2** | At most one in-effect Ascription per Definition (status = ACTIVE or DEPRECATED).                                               |

---

### 3. AscriptionStatusTransition

#### Validation Rules

| ID         | Rule                                                     |
| ---------- | -------------------------------------------------------- |
| **AST-V1** | `postStatus` MUST NOT be null.                           |
| **AST-V2** | `ascription` MUST NOT be null.                           |
| **AST-V3** | `ascription` MUST reference an existing Ascription `id`. |

#### Assignment Rules

| ID         | Rule                                                        |
| ---------- | ----------------------------------------------------------- |
| **AST-A1** | `id` is assigned at creation time and MUST NOT be modified. |

#### State Rules

| ID         | Rule                                                                                                                           |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------ |
| **AST-S1** | See `gsm-ascription-lifecycle` state machine diagram for valid transitions, preconditions, cascades, and audit-log invariants. |

---

### 4. AscriptionStatus

#### State Rules

| ID                      | Rule                                                                                                                           |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| **AscriptionStatus-S1** | See `gsm-ascription-lifecycle` state machine diagram for valid transitions, preconditions, cascades, and audit-log invariants. |

---

### 5. Structure

#### Validation Rules

| ID               | Rule                                                                                                               |
| ---------------- | ------------------------------------------------------------------------------------------------------------------ |
| **Structure-V1** | `StructureArchetype.purpose` MUST be non-empty (enforced by base schema).                                          |
| **Structure-V2** | `StructureArchetype.purpose` MUST be globally unique among in-effect (ACTIVE or DEPRECATED) Structure Ascriptions. |
| **Structure-V3** | `statement.purpose` is **identity-bound**: MUST NOT change across Ascriptions of the same Definition.              |

#### State Rules

| ID               | Rule                                                                                                                                                                                                                                                                      |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Structure-S1** | See `gsm-ascription-lifecycle` state machine diagram for valid transitions, preconditions, cascades, and audit-log invariants.                                                                                                                                            |
| **Structure-S2** | The definition-manager MUST reject activation of a Structure whose purpose duplicates an already in-effect Structure's purpose.                                                                                                                                           |
| **Structure-S3** | **Governing cascade source**: Structure lifecycle transitions cascade to Mechanisms, Interfaces, Directives, and Norms belonging to this Structure (all transitions, no-op on failure — governed elements have independent identity and their own referee preconditions). |
| **Structure-S4** | **Not a referee**: Structure has no References; its transitions have no referee preconditions.                                                                                                                                                                            |

---

### 6. Mechanism

#### Validation Rules

| ID                | Rule                                                                                                                                                                       |
| ----------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Mechanism-V1**  | `statement.structure` MUST reference an existing Structure `id`.                                                                                                           |
| **Mechanism-V2**  | `statement.structure` is **identity-bound**: MUST NOT change across Ascriptions of the same Definition.                                                                    |
| **Mechanism-V3**  | `statement.function` MUST be non-empty.                                                                                                                                    |
| **Mechanism-V4**  | `statement.function` is **identity-bound**: MUST NOT change across Ascriptions of the same Definition.                                                                     |
| **Mechanism-V5**  | `statement.function` MUST be unique among all in-effect Mechanisms belonging to the same Structure.                                                                        |
| **Mechanism-V6**  | _(Generative)_ `statement.rule` MUST be syntactically valid Starlark (parseable by the Starlark parser).                                                                   |
| **Mechanism-V7**  | _(Generative)_ `statement.rule` MUST begin with exactly one `on("...")` call as its first executable statement.                                                            |
| **Mechanism-V8**  | _(Generative)_ The `on()` argument MUST be a string literal resolving to a defined Archetype in the governed scope.                                                        |
| **Mechanism-V9**  | _(Generative)_ String literals in `sys.*` calls (first argument = archetype name) MUST be string literals (enables static analysis and Effector/Receptor auto-derivation). |
| **Mechanism-V10** | _(Generative)_ String literals in `sys.*` calls MUST resolve to declared Archetypes in the governed scope.                                                                 |
| **Mechanism-V11** | _(Generative)_ Dict literals in `sys.emit/create/modify` SHOULD conform to the target Archetype schema (best-effort for dynamic values).                                   |
| **Mechanism-V12** | _(Generative)_ Only `sys`, injected host functions (`on`, `now`, `uuid7`, `fullmatch`, `search`), and Starlark built-ins are allowed globals. Any other global → error.    |
| **Mechanism-V13** | _(Generative)_ No `load()` statements.                                                                                                                                     |
| **Mechanism-V14** | _(Generative)_ Execution budget: configurable max steps (default 100k).                                                                                                    |
| **Mechanism-V15** | _(Generative)_ Explicitly authored Effector/Receptor Ascriptions for this Mechanism Definition MUST NOT exist.                                                             |
| **Mechanism-V16** | _(Declarative)_ At least 1 Effector and 1 Receptor MUST be explicitly authored as separate Ascriptions referencing this Mechanism Definition.                              |

#### State Rules

| ID               | Rule                                                                                                                                                                                |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Mechanism-S1** | See `gsm-ascription-lifecycle` state machine diagram for valid transitions, preconditions, cascades, and audit-log invariants.                                                      |
| **Mechanism-S2** | Mechanism.function: NOT unique globally, not within Structure (uniqueness only among in-effect siblings).                                                                           |
| **Mechanism-S3** | The definition-manager MUST reject activation of a Mechanism whose function duplicates an already in-effect sibling Mechanism within the same Structure.                            |
| **Mechanism-S4** | **Referee**: References Structure. On every transition, the referenced Structure MUST satisfy the lifecycle referential integrity precondition for the target status.               |
| **Mechanism-S5** | **Constitutive cascade source**: Mechanism lifecycle transitions cascade to its Effectors and Receptors (all transitions, blocks source on failure — ports are constitutive parts). |
| **Mechanism-S6** | **Governing cascade target**: receives cascades from its owning Structure (no-op on failure).                                                                                       |

#### Usage Rules

| ID                | Rule                                                                                                                                                                                                                                                                                                                         |
| ----------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Mechanism-U1**  | Rule body executes within a sandboxed Starlark environment that exposes exactly: the `sys` namespace, injected host functions (`on`, `now`, `uuid7`, `fullmatch`, `search`), and Starlark built-ins. No other globals are permitted.                                                                                         |
| **Mechanism-U2**  | `sys.id` — read-only property: the Mechanism's `id`.                                                                                                                                                                                                                                                                         |
| **Mechanism-U3**  | `var = sys.create(...)` (assigned) → **closed-loop**: the DM auto-derives a feedback Receptor, enabling verification.                                                                                                                                                                                                        |
| **Mechanism-U4**  | `sys.create(...)` (unassigned) → **open-loop** / fire-and-forget: no feedback Receptor is derived. Same pattern applies to `sys.modify`, `sys.delete`, `sys.acquire`.                                                                                                                                                        |
| **Mechanism-U5**  | `on(archetype)` — event trigger declaration (first executable statement).                                                                                                                                                                                                                                                    |
| **Mechanism-U6**  | `now()` → ISO 8601 timestamp.                                                                                                                                                                                                                                                                                                |
| **Mechanism-U7**  | `uuid7()` → UUIDv7 string (RFC 9562, time-sortable).                                                                                                                                                                                                                                                                         |
| **Mechanism-U8**  | `fullmatch(pattern, string)` → boolean (full regex match).                                                                                                                                                                                                                                                                   |
| **Mechanism-U9**  | `search(pattern, string)` → first regex match or None.                                                                                                                                                                                                                                                                       |
| **Mechanism-U10** | **Mutual exclusivity invariant**: within a single Ascription, either `rule` is present (Generative) or `rule` is absent (Declarative). DM MUST reject: a Generative Ascription with explicitly authored Effector/Receptor Ascriptions; a Declarative Ascription with zero explicitly authored Effector/Receptor Ascriptions. |
| **Mechanism-U11** | **Enrichment independence**: updating a Mechanism Ascription (new version) does NOT cascade to its Effectors/Receptors.                                                                                                                                                                                                      |
| **Mechanism-U12** | **Port Definition reuse protocol (Generative mode)**: When auto-deriving Effectors/Receptors, DM reuses existing port Definitions by matching on the triple (Mechanism Definition, data Archetype, direction). If match exists, new derived Ascription is under that Definition. If no match, new Definition created.        |

---

### 7. Effector

#### Validation Rules

| ID              | Rule                                                                                                                                                                                       |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Effector-V1** | `statement.mechanism` reference MUST NOT be null.                                                                                                                                          |
| **Effector-V2** | `statement.mechanism` is **identity-bound**: MUST NOT change across Ascriptions of the same Definition.                                                                                    |
| **Effector-V3** | `statement.archetype` reference MUST NOT be null.                                                                                                                                          |
| **Effector-V4** | `statement.archetype` (data archetype) is **identity-bound**: MUST NOT change across Ascriptions of the same Definition. To change the data type a port produces, author a new Definition. |

#### State Rules

| ID              | Rule                                                                                                                                                                                   |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Effector-S1** | See `gsm-ascription-lifecycle` state machine diagram for valid transitions, preconditions, cascades, and audit-log invariants.                                                         |
| **Effector-S2** | **Referee**: References Archetype (data archetype). On every transition, the referenced Archetype MUST satisfy the lifecycle referential integrity precondition for the target status. |
| **Effector-S3** | **Constitutive cascade target**: receives cascades from its owning Mechanism (blocks Mechanism on failure).                                                                            |
| **Effector-S4** | **Dependent cascade source**: Effector degradation/terminal transitions cascade to Interactions and Interfaces that reference this Effector (no-op on failure).                        |

---

### 8. Receptor

#### Validation Rules

| ID              | Rule                                                                                                                                                                                       |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Receptor-V1** | `statement.mechanism` reference MUST NOT be null.                                                                                                                                          |
| **Receptor-V2** | `statement.mechanism` is **identity-bound**: MUST NOT change across Ascriptions of the same Definition.                                                                                    |
| **Receptor-V3** | `statement.archetype` reference MUST NOT be null.                                                                                                                                          |
| **Receptor-V4** | `statement.archetype` (data archetype) is **identity-bound**: MUST NOT change across Ascriptions of the same Definition. To change the data type a port consumes, author a new Definition. |

#### State Rules

| ID              | Rule                                                                                                                                                                                   |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Receptor-S1** | See `gsm-ascription-lifecycle` state machine diagram for valid transitions, preconditions, cascades, and audit-log invariants.                                                         |
| **Receptor-S2** | **Referee**: References Archetype (data archetype). On every transition, the referenced Archetype MUST satisfy the lifecycle referential integrity precondition for the target status. |
| **Receptor-S3** | **Constitutive cascade target**: receives cascades from its owning Mechanism (blocks Mechanism on failure).                                                                            |
| **Receptor-S4** | **Dependent cascade source**: Receptor degradation/terminal transitions cascade to Interactions and Interfaces that reference this Receptor (no-op on failure).                        |

---

### 9. Interaction

#### Validation Rules

| ID                 | Rule                                                                                                   |
| ------------------ | ------------------------------------------------------------------------------------------------------ |
| **Interaction-V1** | `statement.effector` MUST NOT be null.                                                                 |
| **Interaction-V2** | `statement.effector` is **identity-bound**: MUST NOT change across Ascriptions of the same Definition. |
| **Interaction-V3** | `statement.receptor` MUST NOT be null.                                                                 |
| **Interaction-V4** | `statement.receptor` is **identity-bound**: MUST NOT change across Ascriptions of the same Definition. |
| **Interaction-V5** | `statement.effector.archetype` MUST be schema-compatible with `statement.receptor.archetype`.          |

#### State Rules

| ID                 | Rule                                                                                                                                                                                           |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Interaction-S1** | See `gsm-ascription-lifecycle` state machine diagram for valid transitions, preconditions, cascades, and audit-log invariants.                                                                 |
| **Interaction-S2** | **Referee**: References Effector and Receptor. On every transition, BOTH referenced Effector and Receptor MUST satisfy the lifecycle referential integrity precondition for the target status. |
| **Interaction-S3** | **Dependent cascade target**: receives cascades from its referenced Effector and Receptor on degradation/terminal transitions (no-op on failure).                                              |

---

### 10. Interface

#### Validation Rules

| ID               | Rule                                                                                                                                                                                          |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Interface-V1** | `statement.structure` is **identity-bound**: MUST NOT change across Ascriptions of the same Definition.                                                                                       |
| **Interface-V2** | If `statement.effectorIds`/`statement.receptorIds` are present, they MUST reference Effector/Receptor `id`s that belong to Mechanisms within the same Structure as the Interface's Structure. |

#### State Rules

| ID               | Rule                                                                                                                                                                                           |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Interface-S1** | See `gsm-ascription-lifecycle` state machine diagram for valid transitions, preconditions, cascades, and audit-log invariants.                                                                 |
| **Interface-S2** | **Referee**: References Structure, Effectors, and Receptors. On every transition, ALL referenced elements MUST satisfy the lifecycle referential integrity precondition for the target status. |
| **Interface-S3** | **Governing cascade target**: receives cascades from its owning Structure (no-op on failure).                                                                                                  |
| **Interface-S4** | **Dependent cascade target**: receives cascades from its referenced Effectors and Receptors on degradation/terminal transitions (no-op on failure).                                            |

#### Usage Rules

| ID               | Rule                                                                                                                                                                                                          |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Interface-U1** | Group Effectors/Receptors by external exposure semantics (e.g., "admin", "monitoring", "public API").                                                                                                         |
| **Interface-U2** | Apply boundary-level governance Norms to Interfaces rather than individual Effectors/Receptors when the constraint is uniform across all grouped Effectors/Receptors, (AND) relative to the Interface itself. |

---

### 11. Archetype

#### Validation Rules

| ID                | Rule                                                                                                                                                                                                                                                                                                                            |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Archetype-V1**  | `statement.schema` MUST NOT be null.                                                                                                                                                                                                                                                                                            |
| **Archetype-V2**  | `statement.schema` MUST validate against the GSM meta-schema (`gsm://meta/v1`, defined in `GsmMeta.schema.json`). This validates standard JSON Schema 2020-12 structure AND `$gsm:*` vocabulary keyword value shapes. Semantic checks (allOf chain, uniqueness, type-compatibility, mutual exclusion) are DM application-level. |
| **Archetype-V3**  | `statement.schema.title` MUST NOT be null or empty (DM-enforced identity rule).                                                                                                                                                                                                                                                 |
| **Archetype-V4**  | `statement.schema.title` is **identity-bound** (DM-enforced): MUST NOT change across Ascriptions of the same Definition.                                                                                                                                                                                                        |
| **Archetype-V5**  | `statement.schema.title` MUST be globally unique among in-effect (ACTIVE or DEPRECATED) Archetype Ascriptions.                                                                                                                                                                                                                  |
| **Archetype-V6**  | `statement.schema` MUST declare an `allOf` chain that ultimately roots at the GSM base archetype schema. Intermediate tenant archetypes are permitted (depth-N chains) as long as all `allOf` paths converge to the same GSM base.                                                                                              |
| **Archetype-V7**  | Schemas whose `allOf` chain does not root at the GSM base archetype MUST be rejected by the definition-manager.                                                                                                                                                                                                                 |
| **Archetype-V8**  | The set of `$gsm:identityBound`-annotated properties in `statement.schema` MUST NOT differ from the first Ascription's schema for this Definition. Changing the identity-bound set = new Archetype Definition.                                                                                                                  |
| **Archetype-V9**  | _(Vocabulary)_ `$gsm:queryable` — annotated property type MUST be indexable (string, number, integer, boolean, or array of scalars).                                                                                                                                                                                            |
| **Archetype-V10** | _(Vocabulary)_ Maximum queryable properties per Archetype: DM-configurable cap (default: 8).                                                                                                                                                                                                                                    |
| **Archetype-V11** | _(Vocabulary)_ `$gsm:sensitive` + `$gsm:queryable` — mutual exclusion. Same property carrying both keywords is rejected (sensitive data MUST NOT be indexed).                                                                                                                                                                   |
| **Archetype-V12** | _(Vocabulary)_ `$gsm:validation` — each expression MUST be parseable CEL, deterministic, and side-effect-free.                                                                                                                                                                                                                  |
| **Archetype-V13** | _(Vocabulary)_ `$gsm:identityBound` — the set of annotated properties MUST NOT differ from the first Ascription's schema for this Definition (immutable set).                                                                                                                                                                   |
| **Archetype-V14** | _(Vocabulary)_ Unknown `$gsm:*` keywords — DM rejects unrecognized vocabulary keywords (sealed vocabulary).                                                                                                                                                                                                                     |

#### Relational Consistency Rules

| ID                | Rule                                                                                                                              |
| ----------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| **Archetype-RC1** | Any Ascription that references this Archetype MUST have `statement` compatible with the JSON Schema stored in `statement.schema`. |

#### Assignment Rules

| ID               | Rule                                                                                                                                                                          |
| ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Archetype-A1** | Authors provide `schema` in the Archetype Ascription (with `schema.title` as the identity).                                                                                   |
| **Archetype-A2** | DM validates the schema, assigns `$id` inside the schema document using the convention `gsm://archetypes/{schema.title}/v{version}`, and persists the Ascription as-is (1:1). |

#### State Rules

| ID               | Rule                                                                                                                                                                                                                                                 |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Archetype-S1** | See `gsm-ascription-lifecycle` state machine diagram for valid transitions, preconditions, cascades, and audit-log invariants.                                                                                                                       |
| **Archetype-S2** | The definition-manager MUST reject activation of an Archetype whose `schema.title` duplicates an already in-effect Archetype's `schema.title`.                                                                                                       |
| **Archetype-S3** | _(Vocabulary infra)_ `$gsm:queryable` — DM auto-provisions a PostgreSQL expression index on each annotated JSONB path (B-tree scalar / GIN array) scoped to this Archetype at ACTIVE transition. Index dropped when no in-effect Ascription remains. |
| **Archetype-S4** | _(Vocabulary infra)_ `$gsm:unique` — DM auto-provisions a PostgreSQL partial unique expression index at ACTIVE transition. Index dropped when no in-effect Ascription remains.                                                                       |
| **Archetype-S5** | Other vocabulary keywords (`$gsm:identityBound`, `$gsm:validation`, `$gsm:dataProtection`, `$gsm:sensitive`) are enforced at application level and do NOT require database infrastructure provisioning.                                              |

---

### 12. Directive

#### Validation Rules

| ID               | Rule                                                                                                    |
| ---------------- | ------------------------------------------------------------------------------------------------------- |
| **Directive-V1** | `statement.structure` MUST reference an existing Structure.                                             |
| **Directive-V2** | `statement.structure` is **identity-bound**: MUST NOT change across Ascriptions of the same Definition. |
| **Directive-V3** | `statement.modal` MUST be a valid DirectiveModal value.                                                 |
| **Directive-V4** | `statement.verb` MUST be a valid DirectiveVerb value.                                                   |
| **Directive-V5** | `statement.qualifier` MUST reference an existing Archetype.                                             |
| **Directive-V6** | `statement.qualifier` is **identity-bound**: MUST NOT change across Ascriptions of the same Definition. |
| **Directive-V7** | `statement.purpose` MUST reference the purpose of an existing Structure.                                |
| **Directive-V8** | `statement.purpose` is **identity-bound**: MUST NOT change across Ascriptions of the same Definition.   |

#### Consistency Rules

| ID               | Rule                                                                                                                                                                             |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Directive-C1** | Two directives with ENSURE and PREVENT on the same qualifier AND same Purpose → contradiction (error).                                                                           |
| **Directive-C2** | Two directives with opposing verb directions on the same qualifier AND same Purpose → conflict. Resolved by DirectiveModal precedence (MUST/MUST_NOT > SHOULD/SHOULD_NOT > MAY). |
| **Directive-C3** | A positive modal and its negation (e.g., MUST + MUST_NOT) on the same verb + qualifier + Purpose → contradiction (error).                                                        |
| **Directive-C4** | Different Purposes on the same qualifier are NOT conflicts.                                                                                                                      |

#### State Rules

| ID               | Rule                                                                                                                                                                                                               |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Directive-S1** | See `gsm-ascription-lifecycle` state machine diagram for valid transitions, preconditions, cascades, and audit-log invariants.                                                                                     |
| **Directive-S2** | **Referee**: References Structure (definedBy), Archetype (qualifier), and Structure (purpose). On every transition, ALL three MUST satisfy the lifecycle referential integrity precondition for the target status. |
| **Directive-S3** | **Governing cascade target**: receives cascades from its owning Structure (no-op on failure).                                                                                                                      |

---

### 13. Norm

#### Validation Rules

| ID           | Rule                                                                                                        |
| ------------ | ----------------------------------------------------------------------------------------------------------- |
| **Norm-V1**  | `statement.structure` MUST reference an existing Structure.                                                 |
| **Norm-V2**  | `statement.structure` is **identity-bound**: MUST NOT change across Ascriptions of the same Definition.     |
| **Norm-V3**  | `statement.qualifier` MUST reference an existing Archetype.                                                 |
| **Norm-V4**  | `statement.qualifier` is **identity-bound**: MUST NOT change across Ascriptions of the same Definition.     |
| **Norm-V5**  | `statement.guard` MUST conform to the **applicability-guard CEL profile**.                                  |
| **Norm-V6**  | `statement.predicate` MUST conform to the **property-assertion CEL profile**.                               |
| **Norm-V7**  | `statement.predicate` is **identity-bound**: MUST NOT change across Ascriptions of the same Definition.     |
| **Norm-V8**  | `statement.toleranceMode` MUST NOT be null.                                                                 |
| **Norm-V9**  | `statement.temporalWindow` required when `statement.toleranceMode` is time-based (AGGREGATED or SUSTAINED). |
| **Norm-V10** | `statement.sustainedThreshold` required when `statement.toleranceMode` = SUSTAINED, MUST be in [0, 1].      |

#### Assignment Rules

| ID          | Rule                                                                         |
| ----------- | ---------------------------------------------------------------------------- |
| **Norm-A1** | When `guard` is omitted, the definition-manager MUST default it to `"true"`. |

#### State Rules

| ID          | Rule                                                                                                                                                                                    |
| ----------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Norm-S1** | See `gsm-ascription-lifecycle` state machine diagram for valid transitions, preconditions, cascades, and audit-log invariants.                                                          |
| **Norm-S2** | **Referee**: References Structure (definedBy) and Archetype (qualifier). On every transition, BOTH MUST satisfy the lifecycle referential integrity precondition for the target status. |
| **Norm-S3** | **Governing cascade target**: receives cascades from its owning Structure (no-op on failure).                                                                                           |

---

## FILE 2: gsm-ascription-lifecycle.puml

---

### 14. Valid Status Transitions

| ID        | Transition               |
| --------- | ------------------------ |
| **LT-1**  | `[*] → DRAFT`            |
| **LT-2**  | `DRAFT → PROPOSED`       |
| **LT-3**  | `PROPOSED → APPROVED`    |
| **LT-4**  | `APPROVED → ACTIVE`      |
| **LT-5**  | `ACTIVE → SUSPENDED`     |
| **LT-6**  | `SUSPENDED → ACTIVE`     |
| **LT-7**  | `ACTIVE → DEPRECATED`    |
| **LT-8**  | `DEPRECATED → SUSPENDED` |
| **LT-9**  | `SUSPENDED → DEPRECATED` |
| **LT-10** | `DEPRECATED → RETIRED`   |
| **LT-11** | `DRAFT → ABANDONED`      |
| **LT-12** | `PROPOSED → REJECTED`    |

---

### 15. Definition Identity Invariant (lifecycle file)

| ID       | Rule                                                                                                                                                                                                                                                                                     |
| -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **LI-1** | Certain `statement` fields are **identity-bound** — they MUST NOT change across Ascriptions of the same Definition. DM MUST reject any Ascription whose identity-bound fields differ from the Definition's first Ascription. To change an identity-bound field, author a new Definition. |
| **LI-2** | Identity-bound fields are declared via `$gsm:identityBound: true` vocabulary keywords. The identity-bound set is immutable per Archetype Definition.                                                                                                                                     |

Identity-bound fields per SubjectType (reiterated from lifecycle file):

| SubjectType | Identity-bound fields                 |
| ----------- | ------------------------------------- |
| Structure   | `purpose`                             |
| Mechanism   | `structure`, `function`               |
| Effector    | `mechanism`, `archetype` (data)       |
| Receptor    | `mechanism`, `archetype` (data)       |
| Interaction | `effector`, `receptor`                |
| Interface   | `structure`                           |
| Directive   | `structure`, `qualifier`, `purpose`   |
| Norm        | `structure`, `qualifier`, `predicate` |
| Archetype   | `schema.title` (DM-enforced)          |

---

### 16. Referee/Reference Table

| ID        | Referee     | Reference(s)                                                      |
| --------- | ----------- | ----------------------------------------------------------------- |
| **REF-1** | Mechanism   | Structure                                                         |
| **REF-2** | Effector    | Archetype                                                         |
| **REF-3** | Receptor    | Archetype                                                         |
| **REF-4** | Interaction | Effector, Receptor                                                |
| **REF-5** | Interface   | Structure, Effector, Receptor                                     |
| **REF-6** | Directive   | Structure (definedBy), Archetype (qualifier), Structure (purpose) |
| **REF-7** | Norm        | Structure (definedBy), Archetype (qualifier)                      |
| **REF-8** | Structure   | (none — Not a Referee)                                            |
| **REF-9** | Archetype   | (none — Not a Referee)                                            |

---

### 17. Referee Transition Preconditions

For **every** transition of a Referee, **each** Reference MUST already be in a compatible status. When a Referee incorporates multiple References, the transition is permitted only if ALL satisfy the precondition; if ANY fails, the transition MUST be rejected.

| ID        | Transition               | Reference MUST be in                                                                                             |
| --------- | ------------------------ | ---------------------------------------------------------------------------------------------------------------- |
| **RP-1**  | `[*] → DRAFT`            | `{DRAFT, PROPOSED, APPROVED, ACTIVE}`                                                                            |
| **RP-2**  | `DRAFT → PROPOSED`       | `{PROPOSED, APPROVED, ACTIVE}`                                                                                   |
| **RP-3**  | `PROPOSED → APPROVED`    | `{APPROVED, ACTIVE}`                                                                                             |
| **RP-4**  | `APPROVED → ACTIVE`      | `{ACTIVE}`                                                                                                       |
| **RP-5**  | `ACTIVE → SUSPENDED`     | `{ACTIVE, SUSPENDED, DEPRECATED}`                                                                                |
| **RP-6**  | `SUSPENDED → ACTIVE`     | `{ACTIVE, DEPRECATED}`                                                                                           |
| **RP-7**  | `SUSPENDED → DEPRECATED` | `{ACTIVE, SUSPENDED, DEPRECATED}`                                                                                |
| **RP-8**  | `ACTIVE → DEPRECATED`    | `{ACTIVE, SUSPENDED, DEPRECATED}`                                                                                |
| **RP-9**  | `DEPRECATED → SUSPENDED` | `{ACTIVE, SUSPENDED, DEPRECATED}`                                                                                |
| **RP-10** | `DEPRECATED → RETIRED`   | `{ACTIVE, SUSPENDED, DEPRECATED, RETIRED}`                                                                       |
| **RP-11** | `DRAFT → ABANDONED`      | `{DRAFT, PROPOSED, APPROVED, ACTIVE, DEPRECATED, SUSPENDED, ABANDONED, REJECTED, RETIRED}` (all — always passes) |
| **RP-12** | `PROPOSED → REJECTED`    | `{PROPOSED, APPROVED, ACTIVE, DEPRECATED, SUSPENDED, ABANDONED, REJECTED, RETIRED}` (all except DRAFT)           |

---

### 18. Cascade Rules

#### 18.1 Constitutive Cascades (lifecycle-coupled parts)

| ID       | Rule                                                                                                                                                                                                                                                                                   |
| -------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **CC-1** | `Mechanism.X→Y` cascades to `Effector.X→Y`                                                                                                                                                                                                                                             |
| **CC-2** | `Mechanism.X→Y` cascades to `Receptor.X→Y`                                                                                                                                                                                                                                             |
| **CC-3** | **Scope**: ALL transitions (`X→Y`).                                                                                                                                                                                                                                                    |
| **CC-4** | **Invariant**: Target.status = Source.status (always — ports and Mechanism share status).                                                                                                                                                                                              |
| **CC-5** | **On failure**: Source transition MUST be rejected.                                                                                                                                                                                                                                    |
| **CC-6** | **Rationale**: Effectors and Receptors are constitutive parts of the Mechanism — the Mechanism is semantically incomplete without them (existential dependency, lifecycle coupling). If the Mechanism cannot co-transition its constitutive parts, the Mechanism itself is incoherent. |

#### 18.2 Governing Cascades (governance scope)

| ID       | Rule                                                                                                                                                                                                                                                    |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **GC-1** | `Structure.X→Y` cascades to `Mechanism.X→Y`                                                                                                                                                                                                             |
| **GC-2** | `Structure.X→Y` cascades to `Interface.X→Y`                                                                                                                                                                                                             |
| **GC-3** | `Structure.X→Y` cascades to `Directive.X→Y`                                                                                                                                                                                                             |
| **GC-4** | `Structure.X→Y` cascades to `Norm.X→Y`                                                                                                                                                                                                                  |
| **GC-5** | **Scope**: ALL transitions (`X→Y`).                                                                                                                                                                                                                     |
| **GC-6** | **Invariant**: none (lagging permitted).                                                                                                                                                                                                                |
| **GC-7** | **On failure**: no-op for that target.                                                                                                                                                                                                                  |
| **GC-8** | **Rationale**: the Structure governs these elements via DNA. Non-blocking because governed elements have independent identity and their own referee preconditions (e.g. a Directive's qualifier Archetype is outside the Structure's governance scope). |

#### 18.3 Dependent Cascades (downstream consumers)

| ID       | Rule                                                                                                                                                                                                                                       |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **DC-1** | `Effector.X→Y` cascades to `Interaction.X→Y`                                                                                                                                                                                               |
| **DC-2** | `Effector.X→Y` cascades to `Interface.X→Y`                                                                                                                                                                                                 |
| **DC-3** | `Receptor.X→Y` cascades to `Interaction.X→Y`                                                                                                                                                                                               |
| **DC-4** | `Receptor.X→Y` cascades to `Interface.X→Y`                                                                                                                                                                                                 |
| **DC-5** | **Scope**: `X→Y` ∈ {`ACTIVE→SUSPENDED`, `ACTIVE→DEPRECATED`, `SUSPENDED→DEPRECATED`, `DEPRECATED→SUSPENDED`, `DEPRECATED→RETIRED`, `DRAFT→ABANDONED`, `PROPOSED→REJECTED`}. Progress and restoration transitions do NOT cascade.           |
| **DC-6** | **Invariant**: none (lagging permitted).                                                                                                                                                                                                   |
| **DC-7** | **On failure**: no-op for that target.                                                                                                                                                                                                     |
| **DC-8** | **Rationale**: Interactions and Interfaces depend on Effectors/Receptors. When a port degrades, its dependents should degrade too (integrity). Progress does not cascade because dependents advance through their own governance timeline. |

#### 18.4 Cascade Evaluation Algorithm (per target)

| ID       | Rule                                                           |
| -------- | -------------------------------------------------------------- |
| **CE-1** | Step 1: IF Target.status ≠ `X`: **failure**.                   |
| **CE-2** | Step 2: IF Target's preconditions for `X→Y` fail: **failure**. |
| **CE-3** | Step 3: ELSE: Target transitions from `X` to `Y`.              |

---

### 19. Audit-Log Invariants (AscriptionStatusTransition)

| ID       | Rule                                                                                                          |
| -------- | ------------------------------------------------------------------------------------------------------------- |
| **AL-1** | Transitions are append-only; never modify/delete.                                                             |
| **AL-2** | The first transition represents creation: `preStatus = null`, `postStatus = DRAFT`.                           |
| **AL-3** | Transition timestamps are monotonic: each `datetime` is >= the preceding `datetime`.                          |
| **AL-4** | The current `status` is derived/denormalized from the latest transition's `postStatus`.                       |
| **AL-5** | **Terminality**: once `status` is ABANDONED, REJECTED, or RETIRED, no additional transitions may be appended. |

---

## Summary Statistics

| Source                            | Classes with rules           | Total rules   |
| --------------------------------- | ---------------------------- | ------------- |
| **gsm.puml**                      | 13 classes/enums             | 117 rules     |
| **gsm-ascription-lifecycle.puml** | Lifecycle + Cascades + Audit | 47 rules      |
| **Total**                         | —                            | **164 rules** |

| Category               | Count |
| ---------------------- | ----- |
| Validation             | 63    |
| Assignment             | 15    |
| Consistency            | 4     |
| Relational Consistency | 1     |
| State                  | 33    |
| Usage                  | 14    |
| Lifecycle Transitions  | 12    |
| Referee Preconditions  | 12    |
| Cascade Rules          | 22    |
| Audit-Log Invariants   | 5     |

**Note**: The lifecycle file's second filename is [gsm-ascription-lifecycle.puml](sie/sie-definition-manager/definition/gsm-ascription-lifecycle.puml) (no `-v1` suffix). The file also contains 103 test scenarios (SC-01 through SC-103) which validate the transition/cascade rules but are not rules themselves — they are omitted from this catalog per scope.
