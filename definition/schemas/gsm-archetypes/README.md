# GSM Base Archetype Schemas

JSON Schema (draft 2020-12) definitions for the 9 GSM base Archetypes.

These are the **governance contract** between DM and tenants. Each schema types the `statement` payload of Ascriptions for one GSM class. Tenants extend structural schemas via `allOf`; sealed schemas cannot be extended.

## URI Convention

All base schemas use the URI convention:

```
gsm://archetypes/{schema.title}/v{version}
```

`schema.title` is the Archetype's identity (DM-enforced identity-bound). `version` is the Ascription version number. DM assigns `$id` deterministically — clients can construct URIs from known values.

## Schemas

| Schema | GSM Class | Sealed | Tenant-Extensible | Identity-bound fields |
|--------|-----------|--------|-------------------|----------------------|
| `Structure.schema.json` | Structure | No | Yes (`allOf`) | `purpose` |
| `Mechanism.schema.json` | Mechanism | No | Yes (`allOf`) | `structure`, `function` |
| `Effector.schema.json` | Effector | No | Yes (`allOf`) | `mechanism`, `archetype` |
| `Receptor.schema.json` | Receptor | No | Yes (`allOf`) | `mechanism`, `archetype` |
| `Interaction.schema.json` | Interaction | No | Yes (`allOf`) | `effector`, `receptor` |
| `Interface.schema.json` | Interface | No | Yes (`allOf`) | `structure` |
| `Archetype.schema.json` | Archetype | Yes | No | `schema.title` (DM-enforced) |
| `Directive.schema.json` | Directive | Yes | No | `structure`, `qualifier`, `purpose` |
| `Norm.schema.json` | Norm | Yes | No | `structure`, `qualifier`, `predicate` |

**Extensible** schemas use `unevaluatedProperties: false` (allows `allOf` additions).
**Sealed** schemas use `additionalProperties: false` and carry `$gsm:sealed: true`.

## `$gsm:*` Schema Annotations

Archetype schemas carry `$gsm:*` annotations — metadata that instructs DM *how* to manage Ascription data governed by the Archetype.

DM introspects annotations at **Archetype authoring time** (validates well-formedness, provisions infrastructure) and enforces them at **Ascription authoring time** (validates data). No runtime behavior — pure definition-plane governance signals.

### Sealed annotations (GSM-defined, DM-implemented)

| Annotation | Scope | DM Behavior | PostgreSQL Mechanism |
|------------|-------|-------------|---------------------|
| `$gsm:sealed` | Archetype (top-level) | Rejects tenant `allOf` extension | Authoring-time validation |
| `$gsm:identityBound` | Cross-version (same Definition) | Rejects Ascription if value differs from first | Authoring-time validation |
| `$gsm:queryable` | Query optimization | Auto-provisions JSONB path index | Expression index or GIN |
| `$gsm:referential` | Cross-Definition integrity | Validates referenced Definition exists | Authoring-time validation (no DB FK on JSONB) |
| `$gsm:unique` | Cross-Definition (same Archetype) | Enforces uniqueness among in-effect Ascriptions | Partial unique expression index |
| `$gsm:sensitive` | Data protection | Masks, encrypts, redacts | Column-level encryption + app redaction |
| `$gsm:validationCEL` | Intra-Ascription | Evaluates CEL CHECK constraints at authoring | Authoring-time validation |
| `$gsm:deprecated` | Schema evolution | Emits warning on new Ascription use | Authoring-time warning |

### `$gsm:sealed: true`

Top-level schema keyword. Marks a schema as non-extensible. Tenant `allOf` referencing a sealed schema is rejected.

### `$gsm:identityBound: true`

Property-level. Value MUST NOT change across Ascriptions of the same Definition. The identity-bound set is itself immutable per Archetype Definition — changing it requires a new Definition.

### `$gsm:queryable: true`

Property-level. DM provisions a PostgreSQL expression index on the JSONB path (B-tree for scalars, GIN for arrays/objects). Enables efficient `GET /ascriptions?archetype=X&statement.prop=value` queries.

Constraints:
- Property type MUST be indexable: string, number, integer, boolean, or array of scalars.
- Max queryable properties per Archetype: DM-configurable cap (default: 8).
- Index auto-provisioned when Archetype transitions to ACTIVE; dropped when no in-effect Ascription references it.

### `$gsm:referential: { "subjectType": "<DefinitionSubjectType>" }`

Property-level. Marks a property as a foreign reference to a Definition `id`. DM validates at authoring time:
- Referenced Definition exists.
- If `subjectType` specified, it matches.
- Optionally, referenced Definition has an in-effect Ascription.

PostgreSQL FK constraints cannot target JSONB values; DM enforces at application level (richer than DB FK: checks subjectType, status, governance scope). Also enables reverse-reference queries.

### `$gsm:unique: true`

Property-level. Unique among in-effect (ACTIVE/DEPRECATED) Ascriptions of the same Archetype. Different from `$gsm:identityBound` (same Definition) — `$gsm:unique` constrains across *all* Definitions of the same Archetype.

DM provisions: `CREATE UNIQUE INDEX ON ascription ((statement->>'prop')) WHERE archetype_id = X AND status IN ('ACTIVE','DEPRECATED')`.

### `$gsm:sensitive: true`

Property-level. DM behavior:
- Masks value in audit logs and `statusTransitions` context.
- Encrypts at rest (envelope encryption for JSONB path or column-level encryption).
- Redacts in API responses unless caller has explicit scope/role.
- Excludes from full-text search and `$gsm:queryable` indexing (`sensitive + queryable` is rejected).

### `$gsm:validationCEL: ["<expr>", ...]`

Top-level schema keyword. CEL expressions evaluated as CHECK constraints at Ascription authoring time. Receives statement payload as `this`, MUST evaluate to `bool`. Covers cross-property invariants JSON Schema can't express:

```json
{
  "$gsm:validationCEL": [
    "this.minReplicas <= this.maxReplicas",
    "this.budget > 0.0 || this.budgetExempt == true"
  ]
}
```

Expressions MUST be deterministic and side-effect-free.

### `$gsm:deprecated: true`

Property-level. DM emits a warning (not error) when new Ascriptions populate the property. Advisory — deprecated properties are still schema-validated.

### Annotation immutability

Annotations on a property can be added/changed across Ascription versions — **except** `$gsm:identityBound`, whose set is immutable per Archetype Definition (see GSM §5).

## Extension Pattern

Tenant-defined Archetypes extending a base schema:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "CostCenterProperties",
  "allOf": [
    { "$ref": "gsm://archetypes/Structure/v1" }
  ],
  "properties": {
    "costCenter": {
      "type": "string",
      "$gsm:identityBound": true,
      "$gsm:queryable": true,
      "$gsm:unique": true
    },
    "budgetOwner": {
      "type": "string",
      "$gsm:referential": { "subjectType": "STRUCTURE" }
    },
    "apiToken": {
      "type": "string",
      "$gsm:sensitive": true
    }
  },
  "$gsm:validationCEL": [
    "this.costCenter.matches('^[A-Z]{2,4}-[0-9]{4}$')"
  ]
}
```

The `allOf` chain MUST converge to exactly one GSM base schema. Depth-N chains through intermediate tenant archetypes are permitted.
