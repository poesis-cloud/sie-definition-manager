# GSM Base Archetype Schemas

JSON Schema (draft 2020-12) definitions for the 8 GSM base Archetypes.

These are the **governance contract** between DM and tenants. Each schema types the `statement` payload of Ascriptions for one GSM class. Tenants extend base schemas via top-level `$ref`; sealed schemas cannot be extended. Rootless archetypes (no `$ref` base) are valid for qualifier, facet, and data archetype roles.

## URI Convention

All base schemas use the URI convention:

`gsmarc://gsm/{title}/v{version}`

`title` is the Archetype's identity (DM-enforced identity-bound). `version` is the Ascription version number. DM assigns `$id` deterministically — clients can construct URIs from known values.

## `$schema` Convention

All Archetype schemas — GSM base and tenant-defined — declare `"$schema": "gsmarc://gsm/Archetype/v1"`. This is the truthful declaration: Archetype schemas are governed by the seed Archetype (the GSM meta-schema), not just standard JSON Schema 2020-12.

**DM validation mechanism**: DM loads `Archetype.json` (the seed Archetype's statement) at startup as a compiled JSON Schema validator. When an Archetype Ascription arrives, DM treats the `statement` (the incoming Archetype schema document) as a JSON instance and validates it against the seed Archetype validator. This validates standard JSON Schema 2020-12 structure (inherited via `allOf` in the meta-schema) AND `$gsm:*` keyword value shapes — in one pass.

**Security invariant**: DM MUST NOT resolve `$schema` URIs from incoming tenant schemas for validation purposes. DM always validates against its own bundled seed Archetype. A tenant declaring `"$schema": "https://evil.com/permissive.json"` does not affect which meta-schema DM uses. The `$schema` field is a declaration, not an enforcement vector.

## Schemas

| Schema             | GSM Class   | Sealed | Tenant-Extensible | Identity-bound fields                 |
| ------------------ | ----------- | ------ | ----------------- | ------------------------------------- |
| `Structure.json`   | Structure   | No     | Yes (`$ref`)      | `purpose`                             |
| `Mechanism.json`   | Mechanism   | No     | Yes (`$ref`)      | `structure`, `function`               |
| `Effector.json`    | Effector    | No     | Yes (`$ref`)      | `mechanism`, `archetype`              |
| `Receptor.json`    | Receptor    | No     | Yes (`$ref`)      | `mechanism`, `archetype`              |
| `Interaction.json` | Interaction | No     | Yes (`$ref`)      | `effector`, `receptor`                |
| `Archetype.json`   | Archetype   | Yes    | No                | `title` (DM-enforced)                 |
| `Directive.json`   | Directive   | No     | Yes (`$ref`)      | `structure`, `qualifier`, `purpose`   |
| `Norm.json`        | Norm        | No     | Yes (`$ref`)      | `structure`, `qualifier`, `assertion` |

**Extensible** schemas use `unevaluatedProperties: false` (allows `allOf` facet additions and `$ref` base extension).
**Sealed** schemas use `additionalProperties: false` and carry `$gsm:sealed: true`.

## Archetype's Triple Relational Role

A single Archetype construct serves three distinct relational roles across the GSM class model, differentiated not by class hierarchy or table structure, but by which FK column references it:

| Role          | FK Column                                    | Where              | Structural base required?                                                                     |
| ------------- | -------------------------------------------- | ------------------ | --------------------------------------------------------------------------------------------- |
| **Typing**    | `archetype_id`                               | Every Ascription   | Yes — `$ref` chain must converge to exactly one GSM base (determines `DefinitionSubjectType`) |
| **Qualifier** | `qualifier_id`                               | Directive, Norm    | No — rootless (facet) archetypes allowed; defines the viability dimension being governed      |
| **Data**      | `output_archetype_id` / `input_archetype_id` | Effector, Receptor | No — rootless archetypes allowed; declares the information type a port emits/consumes         |

**Why one construct and one table suffice**: all three roles share the same identity model (Definition + Ascription lifecycle), the same schema validation surface (JSON Schema + `$gsm:*` vocabulary), and the same governance process (authoring → review → activation). The roles differ only in referential position (which FK column) and in structural-base requirement (typing requires a base; qualifier and data do not). Splitting into separate entities would duplicate the entire Ascription lifecycle, schema validation, and vocabulary infrastructure for no semantic gain.

The `$ref` chain presence/absence IS the discriminant: DM enforces structural-base convergence at two points — Archetype authoring (`$ref` chain validation) and Ascription creation (`archetype_id` must resolve to a based archetype). Rootless archetypes pass authoring validation and are usable in qualifier/data positions, but DM rejects them as `archetype_id`.

## `$gsm:*` Schema Vocabulary

Archetype schemas carry `$gsm:*` vocabulary keywords — schema-level declarations that instruct DM _how_ to govern Ascription data beyond structural typing. They form the **governance contract** between Archetypes and DM.

DM introspects vocabulary keywords at **Archetype authoring time** (validates well-formedness, provisions infrastructure) and enforces them at **Ascription authoring time** (validates data). No runtime behavior — pure definition-plane governance signals.

Vocabulary keyword schemas are defined as `$defs` within the **GSM meta-schema** ([`Archetype.json`](Archetype.json) — the seed Archetype's own statement). DM validates every incoming Archetype schema against this meta-schema at authoring time — this validates standard JSON Schema 2020-12 structure AND `$gsm:*` keyword value shapes in one pass. Archetype schemas do NOT reference this file via `allOf` or `$ref` for vocabulary — vocabulary keywords are schema-level (like `type`, `required`), not data-level.

### Sealed vocabulary keywords (GSM-defined, DM-implemented)

| Keyword               | Scope                             | DM Behavior                                                                             | PostgreSQL Mechanism            |
| --------------------- | --------------------------------- | --------------------------------------------------------------------------------------- | ------------------------------- |
| `$gsm:sealed`         | Archetype (top-level)             | Rejects tenant `$ref` extension                                                         | Authoring-time validation       |
| `$gsm:identityBound`  | Cross-version (same Definition)   | Rejects Ascription if value differs from first                                          | Authoring-time validation       |
| `$gsm:queryable`      | Query optimization                | Auto-provisions JSONB path index                                                        | Expression index or GIN         |
| `$gsm:unique`         | Cross-Definition (same Archetype) | Enforces uniqueness among in-effect Ascriptions                                         | Partial unique expression index |
| `$gsm:dataProtection` | Data protection                   | Phase-first protection model (atRest / inTransit × encryption, hash, mask, suppression) | Authoring + read/write-time     |

### `$gsm:sealed: true`

Top-level schema keyword. Marks a schema as non-extensible. Tenant `$ref` pointing to a sealed schema is rejected.

### `$gsm:identityBound: true`

Property-level. Value MUST NOT change across Ascriptions of the same Definition. The identity-bound set is itself immutable per Archetype Definition — changing it requires a new Definition.

### `$gsm:queryable: true`

Property-level. DM provisions a PostgreSQL expression index on the JSONB path (B-tree for scalars, GIN for arrays/objects). Enables efficient `GET /ascriptions?archetype=X&statement.prop=value` queries.

Constraints:

- Property type MUST be indexable: string, number, integer, boolean, or array of scalars.
- Max queryable properties per Archetype: DM-configurable cap (default: 8).
- Index auto-provisioned when Archetype transitions to ACTIVE; dropped when no in-effect Ascription references it.

### `$gsm:unique: true`

Property-level. Unique among in-effect (ACTIVE/DEPRECATED) Ascriptions of the same Archetype. Different from `$gsm:identityBound` (same Definition) — `$gsm:unique` constrains across _all_ Definitions of the same Archetype.

DM provisions: `CREATE UNIQUE INDEX ON ascription ((statement->>'prop')) WHERE archetype_id = X AND status IN ('ACTIVE','DEPRECATED')`.

### `$gsm:dataProtection: { ... }`

Property-level. Phase-first structured protection keyword with two
lifecycle phases — `atRest`, `inTransit` — each allowing at most one
of four uniform measures: `encryption`, `hash`, `mask`, `suppression`.
No auto-cascade between phases; each phase must be explicitly declared.

DM validates the keyword value against the `DataProtection` `$defs`
in the GSM meta-schema at Archetype authoring time (see [Data Protection](#data-protection)
section for the full schema, phase semantics, and cross-phase rules).

All fields annotated with `$gsm:dataProtection` are subject to implicit
access audit — DM logs every read as a governance event.

Constraint: `$gsm:queryable` + `atRest.encryption` on the same property
is rejected (ciphertext is not indexable). `$gsm:queryable` +
`atRest.hash` is allowed (DM hashes query inputs to match stored
hashes).

### Vocabulary keyword immutability

Vocabulary keywords on a property can be added/changed across Ascription versions — **except** `$gsm:identityBound`, whose set is immutable per Archetype Definition (see GSM §5).

## Schema Composition Terminology

Archetype schemas use three orthogonal JSON Schema keywords, each with a distinct semantic role:

| Keyword   | Semantic role                                                                                                    | Example                                                                     |
| --------- | ---------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| `$schema` | **Who validates me** — declares the meta-schema (always the GSM seed Archetype)                                  | `"$schema": "gsmarc://gsm/Archetype/v1"`                                    |
| `$ref`    | **What I am / extend** — base extension; determines the GSM subject type via the `$ref` chain                    | `"$ref": "gsmarc://gsm/Structure/v1"`                                       |
| `allOf`   | **What facets I include** — applicable property sets; additional cross-cutting concerns composed into the schema | `"allOf": [{"$ref": "gsmarc://itip/frameworks/.../SecurityProperties/v1"}]` |

### Definitions

**Based archetype** — An Archetype whose schema declares a top-level `$ref` pointing to a GSM base archetype (or to an intermediate tenant archetype whose `$ref` chain eventually reaches a GSM base). The `$ref` chain determines the `DefinitionSubjectType`. Based archetypes are required for the **typing** role (`archetype_id` on Ascriptions). Formerly called "structural archetype."

**Rootless archetype** — An Archetype whose schema does NOT declare a top-level `$ref` to any GSM base. It has no structural base and cannot be used as `archetype_id` (typing role). Valid for **qualifier** (`qualifier_id` on Directive/Norm), **data** (`output_archetype_id`/`input_archetype_id` on Effector/Receptor), and **facet** (included via `allOf`) roles. Examples: quality dimensions (SecurityProperties, PerformanceProperties), domain-scoped classifications, cross-structural patterns.

**GSM base archetype** — One of the 8 JSON Schema files shipped with DM, corresponding to the 8 GSM classes: Structure, Mechanism, Effector, Receptor, Interaction, Directive, Norm, and the Archetype meta-schema. 7 of 8 are extensible (via `$ref`); the Archetype meta-schema is sealed.

**GSM subtype** (informal) — A based archetype that extends a GSM base. For example, a `ServiceProperties` archetype with `"$ref": "gsmarc://gsm/Structure/v1"` is informally a "GSM subtype" of Structure. The formal term is "based archetype with base = Structure."

**Extension** — The act of creating a tenant-defined archetype that references a GSM base (or intermediate) via top-level `$ref`. The extending schema inherits the base's properties (via JSON Schema 2020-12 `$ref` resolution) and adds its own domain-specific properties.

**Applicable property set** (facet) — A schema (typically rootless) included in another archetype's `allOf` array. It contributes additional properties as a cross-cutting concern without affecting the archetype's structural base. Multiple facets can be composed into a single archetype. Example: a based `ServiceProperties` archetype may include `SecurityProperties` and `PerformanceProperties` facets via `allOf`.

**`$ref` chain** — The linear chain of top-level `$ref` references from a tenant archetype through zero or more intermediate tenant archetypes to a GSM base. DM walks this chain to determine the `DefinitionSubjectType`. The chain must be acyclic and converge to exactly one GSM base for based archetypes.

**Sealed** — A schema annotated with `$gsm:sealed: true`. Tenant archetypes MUST NOT declare a `$ref` pointing to a sealed schema. Currently only the Archetype meta-schema is sealed; all other GSM bases are extensible.

## Extension Pattern

Tenant-defined Archetypes extend a base schema via top-level `$ref`:

```json
{
  "$schema": "gsmarc://gsm/Archetype/v1",
  "$ref": "gsmarc://gsm/Structure/v1",
  "title": "CostCenterProperties",
  "properties": {
    "costCenter": {
      "type": "string",
      "$gsm:identityBound": true,
      "$gsm:queryable": true,
      "$gsm:unique": true
    },
    "budgetOwner": {
      "type": "string",
      "format": "uuid"
    },
    "apiToken": {
      "type": "string",
      "$gsm:dataProtection": {
        "atRest": { "encryption": {} },
        "inTransit": { "suppression": true }
      }
    }
  }
}
```

The `$ref` chain MUST converge to exactly one GSM base schema when the Archetype is used as a **typing archetype** (`archetype_id`). Depth-N chains through intermediate tenant archetypes are permitted.

**Rootless archetypes** (facet / qualifier / data archetypes) omit the top-level `$ref` (no base extension). They MAY declare `allOf` entries as applicable property sets (facets) but these do NOT establish a structural base. They are valid first-class Archetypes usable as `qualifier_id` (Directive/Norm facet) or `output_archetype_id` / `input_archetype_id` (Effector/Receptor data type), but MUST NOT be used as `archetype_id`.

A based Archetype schema MAY also declare `allOf` entries as applicable property sets (e.g., extending `Structure` via `$ref` AND including a rootless `SecurityProperties` facet in `allOf`). The base is determined exclusively by the `$ref` chain — `allOf` entries are facets and never determine the structural base.

Example rootless facet archetype:

```json
{
  "$schema": "gsmarc://gsm/Archetype/v1",
  "title": "SecurityProperties",
  "type": "object",
  "properties": {
    "encryptionLevel": { "type": "string" },
    "tlsEnabled": { "type": "boolean" },
    "authMethod": { "type": "string" }
  }
}
```

---

## Data Protection

### Design principle: domain-agnostic treatment primitives

Data sensitivity **classification** (PII, PCI, PHI, etc.) is domain-specific vocabulary. Different industries, jurisdictions, and organizations define what counts as "sensitive" and at what level. SIE MUST NOT embed domain-specific classification taxonomies into its governance primitives.

Instead, GSM provides a single **structured protection keyword** — `$gsm:dataProtection` — that declares specific, mechanical treatments DM applies to the annotated property's data. Domains compose these treatments based on their own classification logic.

The mapping from domain classification to GSM treatments is a **domain governance decision** expressed outside GSM (e.g., in ITIP's domain model or in domain-specific Directives). GSM's job is to execute the declared treatments faithfully.

### Architecture: phase-first protection model

`$gsm:dataProtection` is organized by **lifecycle phase** — when protection applies — not by measure type. Two phases cover the complete data lifecycle:

| Phase       | Scope                    | Description                                                                                       |
| ----------- | ------------------------ | ------------------------------------------------------------------------------------------------- |
| `atRest`    | Persisted JSONB          | How data is stored in the `statement` column                                                      |
| `inTransit` | All non-persisted output | How data appears in API responses, domain events, streams, logs, traces, metrics, and diagnostics |

Each phase independently declares at most **one** of four uniform measures:

| Measure       | Semantics                                                                      |
| ------------- | ------------------------------------------------------------------------------ |
| `encryption`  | Reversible AES-256-GCM envelope encryption. DM encrypts/decrypts transparently |
| `hash`        | Irreversible one-way salted hash. Original value permanently destroyed         |
| `mask`        | Partial masking — visible from LEFT/RIGHT, replacement character × occurrence  |
| `suppression` | Complete omission — field absent from output                                   |

**No auto-cascade**: declaring `atRest` does NOT imply any `inTransit` treatment. Each phase must be explicitly declared. This is deliberate — the author decides exactly which treatment applies at each lifecycle point.

**Implicit access audit**: every field annotated with `$gsm:dataProtection` (regardless of which phases/measures are declared) is subject to field-level access auditing. DM logs every read of an Ascription that touches a protected field as a governance event:

```json
{
  "caller": "user@example.com",
  "timestamp": "2025-01-15T10:30:00Z",
  "ascriptionId": "...",
  "definitionId": "...",
  "fieldName": "ssn",
  "representation": "CLEARTEXT | ENCRYPTED | HASHED | MASKED | SUPPRESSED"
}
```

The audit event is a Description-plane artifact (evidence/telemetry) fed into the governance loop — it does not block the API response.

### Schema validation invariant (hard rule)

**Treated property values MUST NOT be incompatible with their Archetype schema constraints.** DM rejects keyword declarations at Archetype authoring time if the declared treatment would produce schema-invalid values.

Encryption is **transparent** — DM encrypts at write time and decrypts at read time. Consumers (API, logs, indexes) never see ciphertext. Therefore encryption has **no schema restrictions**.

For non-transparent measures (hash, mask, suppression), schema compatibility rules apply uniformly across all phases:

| Measure       | Property type restriction | Schema constraint restrictions                                  |
| ------------- | ------------------------- | --------------------------------------------------------------- |
| `encryption`  | None                      | None (transparent — DM encrypts/decrypts internally)            |
| `hash`        | `type` MUST be `"string"` | MUST NOT have `enum`, `const`, `pattern`, `format`, `maxLength` |
| `mask`        | `type` MUST be `"string"` | MUST NOT have `enum`, `const`, `pattern`, `format`              |
| `suppression` | None                      | Property MUST NOT be in parent's `required` array               |

**Rationale**: hashes are hex digests, masked values contain replacement characters — both violate constraints designed for cleartext. Suppression omits the field entirely — required fields cannot be omitted. Encryption is transparent: DM validates cleartext before encrypting and returns cleartext after decrypting, so the property's full schema always applies.

### Cross-phase mutual exclusion rules

`atRest` determines what data is available to transit phases. Not all measure combinations are semantically valid:

| `atRest` measure | Allowed `inTransit`           | Reason                                                                                                |
| ---------------- | ----------------------------- | ----------------------------------------------------------------------------------------------------- |
| `encryption`     | Any measure or absent         | DM decrypts → full cleartext available for any transit treatment                                      |
| `hash`           | `suppression` only, or absent | Stored value is a hash digest — encrypting, masking, or re-hashing a hash is semantically meaningless |
| `mask`           | `suppression` only, or absent | Stored value is already degraded — further transformation is meaningless                              |
| `suppression`    | MUST be absent                | No data exists — nothing to process                                                                   |
| Absent           | Any measure or absent         | Cleartext stored — all transit measures available                                                     |

DM enforces these rules at Archetype authoring time. Violations are rejected.

### `$gsm:queryable` interaction (atRest only)

PostgreSQL indexes operate on persisted JSONB values. Only `atRest` affects queryability:

| `atRest` measure | `$gsm:queryable` | Reason                                                          |
| ---------------- | ---------------- | --------------------------------------------------------------- |
| `encryption`     | **Forbidden**    | Ciphertext is non-deterministic (GCM nonce) — cannot be indexed |
| `hash`           | Allowed          | DM hashes query inputs to match stored hashes                   |
| `mask`           | **Forbidden**    | Masked value cannot match query inputs                          |
| `suppression`    | **Forbidden**    | No data to index                                                |
| Absent           | Allowed          | Cleartext stored — index works normally                         |

### GSM meta-schema (`$gsm:dataProtection` detail)

`$gsm:dataProtection` is a structured object. DM validates keyword values against the `DataProtection` and `DataProtectionMeasures` `$defs` in [`Archetype.json`](Archetype.json) at Archetype authoring time.

All `$gsm:*` vocabulary keyword schemas are defined in [`Archetype.json`](Archetype.json) as `$defs` entries. DM validates keyword values against these definitions at Archetype authoring time. The `DataProtection` and `DataProtectionMeasures` `$defs` define the full protection structure shown below.

```json
{
  "type": "object",
  "description": "Phase-first protection model.",
  "properties": {
    "atRest": { "$ref": "#/$defs/DataProtectionMeasures" },
    "inTransit": { "$ref": "#/$defs/DataProtectionMeasures" }
  },
  "minProperties": 1,
  "additionalProperties": false,
  "$defs": {
    "DataProtectionMeasures": {
      "type": "object",
      "description": "Exactly one protection measure per lifecycle phase.",
      "properties": {
        "encryption": {
          "type": "object",
          "description": "Reversible AES-256-GCM envelope encryption.",
          "properties": {
            "algorithm": {
              "type": "string",
              "enum": ["AES-256-GCM"],
              "default": "AES-256-GCM",
              "description": "Authenticated encryption algorithm."
            },
            "keyRetention": {
              "type": "integer",
              "minimum": 1,
              "description": "Days before automatic key destruction (crypto-shredding). Omit for on-demand only."
            }
          },
          "additionalProperties": false
        },
        "hash": {
          "type": "object",
          "description": "One-way salted hash. DM-managed salt. Irreversible.",
          "properties": {
            "algorithm": {
              "type": "string",
              "enum": ["SHA-256", "SHA-512", "SHA3-256"],
              "default": "SHA-256",
              "description": "Hash algorithm (java.security.MessageDigest)."
            }
          },
          "additionalProperties": false
        },
        "mask": {
          "type": "object",
          "description": "Partial masking — visible from LEFT/RIGHT, replacement character × occurrence.",
          "properties": {
            "from": {
              "type": "string",
              "enum": ["LEFT", "RIGHT"],
              "description": "Which end of the value to keep visible."
            },
            "with": {
              "type": "object",
              "description": "Replacement specification.",
              "properties": {
                "character": {
                  "type": "string",
                  "minLength": 1,
                  "maxLength": 1,
                  "default": "*",
                  "description": "Replacement character."
                },
                "occurrence": {
                  "type": "integer",
                  "minimum": 1,
                  "maximum": 16,
                  "description": "Number of characters to keep visible."
                }
              },
              "required": ["occurrence"],
              "additionalProperties": false
            }
          },
          "required": ["from", "with"],
          "additionalProperties": false
        },
        "suppression": {
          "type": "boolean",
          "const": true,
          "description": "Complete omission — field absent from output."
        }
      },
      "minProperties": 1,
      "maxProperties": 1,
      "additionalProperties": false
    }
  }
}
```

**Note**: cross-phase mutual exclusion rules (see above) are enforced by DM at authoring time — they are application-level validation beyond what the structural meta-schema expresses.

### Protection measures

The four measures are uniform across all phases. Phase determines _when_ the measure applies; the measure determines _what transformation_ is performed.

#### `encryption` — Envelope encryption

Replaces the property value with **ciphertext** (reversible by DM). DM encrypts at write time and decrypts at read time — encryption is **transparent** to consumers.

**Fields**:

| Field          | Type    | Required | Default         | Description                                              |
| -------------- | ------- | -------- | --------------- | -------------------------------------------------------- |
| `algorithm`    | enum    | No       | `"AES-256-GCM"` | Authenticated encryption algorithm                       |
| `keyRetention` | integer | No       | —               | Days before automatic key destruction (crypto-shredding) |

**Algorithm**: `AES-256-GCM` (sealed enum). Authenticated encryption — confidentiality + integrity in one operation. Implemented via `javax.crypto.Cipher` with `"AES/GCM/NoPadding"` — zero external dependencies.

**Key management** (envelope encryption pattern):

1. DM generates a random **DEK** (Data Encryption Key) per tenant (or per tenant + field scope for granular shredding).
2. Data is encrypted with the DEK (AES-256-GCM, unique 96-bit nonce per operation).
3. The DEK is encrypted (**wrapped**) with a KMS master key (Azure Key Vault / AWS KMS / GCP KMS).
4. The wrapped DEK is stored alongside the ciphertext in a key-reference table.
5. On read: DM calls KMS to unwrap the DEK, then decrypts the field value.

**Benefits**: key rotation only re-wraps DEKs (no data re-encryption); KMS operations are minimal (one unwrap per read); multi-cloud portable.

**`keyRetention`** — enables **crypto-shredding**: destroying the DEK renders all values encrypted with that key irrecoverable. Ascription rows are never mutated — the encrypted blob remains, but without the key it is cryptographically indistinguishable from random data.

- `keyRetention` present: DM automatically destroys the key after the specified number of days.
- `keyRetention` absent: crypto-shredding on-demand only (e.g., GDPR Art. 17 erasure request).
- Every key revocation event is recorded in the governance audit trail.

**Phase-specific semantics**:

| Phase       | Behavior                                                                                                                                                                          |
| ----------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `atRest`    | Statement value stored as ciphertext in JSONB. DM decrypts transparently at read time — consumers always see cleartext. `$gsm:queryable` is forbidden (ciphertext not indexable). |
| `inTransit` | Output contains field-level encrypted value. Consumer requires a shared key to decrypt.                                                                                           |

**Minimal declaration** (all defaults):

```json
"encryption": {}
```

Means: AES-256-GCM, no automatic key revocation.

#### `hash` — One-way hash

Replaces the property value with a **salted hash** (irreversible). The original cleartext is permanently destroyed.

**Fields**:

| Field       | Type | Required | Default     | Description    |
| ----------- | ---- | -------- | ----------- | -------------- |
| `algorithm` | enum | No       | `"SHA-256"` | Hash algorithm |

**Supported algorithms** (sealed enum, all `java.security.MessageDigest`, zero external deps):

| Algorithm  | Output size | Use case                                       |
| ---------- | ----------- | ---------------------------------------------- |
| `SHA-256`  | 256 bits    | Standard — fast, universally supported         |
| `SHA-512`  | 512 bits    | Higher collision resistance for large datasets |
| `SHA3-256` | 256 bits    | NIST post-quantum hedge, Java 9+ native        |

**Salting**: DM always applies a per-tenant salt (generated and stored by DM, never exposed to tenants). Transparent prevention of rainbow-table attacks.

**Output format**: `"sha256:a1b2c3d4..."` — algorithm prefix + lowercase hex digest.

**Phase-specific semantics**:

| Phase       | Behavior                                                                                                                                                          |
| ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `atRest`    | Statement value replaced with salted hash in JSONB. Cleartext permanently destroyed. `$gsm:queryable` is allowed (DM hashes query inputs to match stored hashes). |
| `inTransit` | Output contains hash of the (potentially decrypted) value. Enables correlation without exposing cleartext.                                                        |

**Use cases by phase**:

- `atRest`: data minimization, identity matching, deduplication detection. Suited for fields where the original value is not needed after initial processing (e.g., SSN stored as hash for matching).
- `inTransit`: cross-output correlation without exposing originals (replaces former `displayHash` concept).

#### `mask` — Partial masking

Shows a portion of the value (visible from LEFT or RIGHT) and replaces the rest with a replacement character.

**Fields**:

| Field             | Type    | Required | Default | Description                                                    |
| ----------------- | ------- | -------- | ------- | -------------------------------------------------------------- |
| `from`            | enum    | Yes      | —       | `"LEFT"` or `"RIGHT"` — which end of the value to keep visible |
| `with.character`  | string  | No       | `"*"`   | Single replacement character                                   |
| `with.occurrence` | integer | Yes      | —       | Number of characters to keep visible (1–16)                    |

**Implementation**: deterministic character substitution — no regex.

| Input                 | from    | with                  | Output                                                                 |
| --------------------- | ------- | --------------------- | ---------------------------------------------------------------------- |
| `"4111111111111111"`  | `RIGHT` | `{ "occurrence": 4 }` | `"************1111"`                                                   |
| `"john.doe@acme.com"` | `LEFT`  | `{ "occurrence": 4 }` | `"john**************"`                                                 |
| `"abc"`               | `RIGHT` | `{ "occurrence": 4 }` | `"***"` (value shorter than `with.occurrence` — DM masks entire value) |

**Type restriction**: `mask` only works on strings. DM rejects `mask` at authoring time if property `type` is not `"string"`. Credit card numbers, phone numbers, etc. MUST be typed as `"string"` if they need masking.

**Edge case**: if the value length is ≤ `with.occurrence`, DM masks the entire value — all characters are replaced with the mask character and no original characters are visible.

**Phase-specific semantics**:

| Phase       | Behavior                                                                                                                                    |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `atRest`    | Masked value stored in JSONB. Original cleartext permanently replaced. `$gsm:queryable` forbidden (masked value cannot match query inputs). |
| `inTransit` | Output contains masked value.                                                                                                               |

#### `suppression` — Field omission

```json
"suppression": true
```

DM **silently omits** the field from output. The field does not exist in the output — no `null` value, no placeholder, no metadata.

**Why silent omission**: eliminates information leakage — consumers/observers cannot determine the field exists.

**Schema compatibility**: DM rejects `suppression` at authoring time if the property is in the parent object's `required` array (omitting a required field violates the schema). This rule applies uniformly across all phases.

**Phase-specific semantics**:

| Phase       | Behavior                                                                                                          |
| ----------- | ----------------------------------------------------------------------------------------------------------------- |
| `atRest`    | Field not stored in JSONB. Data never enters the database.                                                        |
| `inTransit` | Field omitted from all non-persisted output (API responses, events, streams, logs, traces, metrics, diagnostics). |

### Domain composition example

Domains compose `$gsm:dataProtection` phases based on their own classification logic. SIE does not dictate the mapping — it executes what is declared.

Example: an ITIP domain classifying "PII", "Credential", and non-sensitive data:

```json
{
  "title": "CustomerProperties",
  "$ref": "gsmarc://gsm/Structure/v1",
  "properties": {
    "fullName": {
      "type": "string",
      "$gsm:dataProtection": {
        "atRest": {
          "encryption": {
            "keyRetention": 1095
          }
        },
        "inTransit": {
          "mask": {
            "from": "LEFT",
            "with": { "character": "*", "occurrence": 3 }
          }
        }
      }
    },
    "email": {
      "type": "string",
      "$gsm:dataProtection": {
        "atRest": { "hash": { "algorithm": "SHA-256" } }
      },
      "$gsm:queryable": true
    },
    "apiToken": {
      "type": "string",
      "$gsm:dataProtection": {
        "atRest": { "encryption": {} },
        "inTransit": { "suppression": true }
      }
    },
    "accountStatus": {
      "type": "string",
      "$gsm:queryable": true
    }
  }
}
```

In this example the domain decided:

- `fullName` is "PII": encrypt at rest (ciphertext in JSONB, auto-shred after 3 years), mask in transit (keep first 3 characters visible from LEFT). Not queryable (ciphertext can't be indexed).
- `email` is "PII": hash at rest (salted hash replaces cleartext — irreversible). `$gsm:queryable` works because DM hashes query inputs. No transit phase declared — delivered and observed as stored hash.
- `apiToken` is a "Credential": encrypt at rest (no shredding — tokens are rotated), suppress in transit.
- `accountStatus` is non-sensitive: queryable, no protection.

SIE doesn't know what "PII" or "Credential" means. It sees one structured vocabulary keyword per property and executes each phase's declared measure faithfully.
