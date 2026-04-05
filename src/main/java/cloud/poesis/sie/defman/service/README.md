# Service Package — Reader's Guide

> **Package**: `cloud.poesis.sie.defman.service` > **27 classes** managing the GSM ascription lifecycle, statement validation,
> schema governance, and cross-entity orchestration for the 8 GSM subject types.

---

## 1. Package topology

The service package is organized in three dependency layers. Every
dependency arrow points **downward**; no lower layer depends on a higher one.

```
Layer 3 — Subject type services        8 GSM subject services + subsidiaries
    │  extend / delegate to
Layer 2 — Shared ascription services    lifecycle, validation, enforcement
    │  persist / query via
Layer 1 — Supporting entity services    leaf entity services (no upward deps)
```

`PersistenceExceptionTranslationService` is a static utility class (not a
Spring bean) outside all layers — maps PostgreSQL constraint names to domain
exception types.

### Layer 3: Subject type services

The 8 GSM subject type services, each extending `AbstractAscriptionService`
(Layer 2):

```
archetype/
├── ArchetypeService                              primary lifecycle
├── ArchetypeSchemaAnnotationValidationService     subsidiary
├── ArchetypeSchemaCompositionValidationService    subsidiary
└── ArchetypeSchemaPropertyIndexationService       subsidiary

mechanism/
├── MechanismService                               primary lifecycle
├── MechanismRuleParsingService                    subsidiary (shared)
├── MechanismRuleValidationService                 subsidiary
└── MechanismPortDerivationService                 subsidiary

norm/
├── NormService                                    primary lifecycle
├── NormApplicabilityValidationService             subsidiary
└── NormAssertionValidationService                 subsidiary

structure/
└── StructureService                               primary lifecycle

directive/
└── DirectiveService                               primary lifecycle

effector/
└── EffectorService                                primary lifecycle

receptor/
└── ReceptorService                                primary lifecycle

interaction/
└── InteractionService                             primary lifecycle
```

**Subsidiary pattern** — every `{Entity}{*}Service` is injected into and
consumed exclusively by its corresponding `{Entity}Service`. No subsidiary
is consumed by a different entity's service.

**Why subsidiaries exist** — the three entity groups that have subsidiaries
are precisely those that integrate third-party technology stacks:

| Entity group  | Technology integration           | Subsidiaries handle                                                                       |
| ------------- | -------------------------------- | ----------------------------------------------------------------------------------------- |
| **Archetype** | JSON Schema                      | Schema composition validation, annotation vocabulary validation, JSONB index provisioning |
| **Norm**      | CEL (Common Expression Language) | Applicability profile validation, assertion profile validation                            |
| **Mechanism** | Starlark                         | Starlark AST parsing (shared), rule structural validation, port auto-derivation from AST  |

The remaining 5 subject type services (Structure, Directive, Effector,
Receptor, Interaction) have no
third-party technology integration and therefore no subsidiaries — their
domain logic fits entirely within the primary lifecycle service.

### Layer 2: Shared ascription services

Shared domain services that implement the ascription lifecycle — governance
grammar enforcement, state machine transitions, statement validation. They
depend downward on Layer 1 entity services and on each other within Layer 2.
No Layer 2 service depends on any Layer 3 subject type service.

```
AbstractAscriptionService                          base class for all 8 subject services
│   consumes: DefinitionService (L1)
│             AscriptionStateMachineService (L2)
│             AscriptionStatementValidationService (L2)
│
AscriptionLifecycleOrchestrationService            coordinates all 8 subtypes
│   consumes: AscriptionStateMachineService (L2)
│             List<AbstractAscriptionService<?>> (L2 abstract type)
│
AscriptionStateMachineService                      pure state machine
│   consumes: AscriptionStatusTransitionService (L1)
│
AscriptionStatementValidationService               JSON Schema + annotation validation
│   consumes: ArchetypeSchemaService (L2)
│             AscriptionArchetypeSchemaAnnotationEnforcementService (L2)
│
AscriptionArchetypeSchemaAnnotationEnforcementService   $gsm:* enforcement on write
│   consumes: AscriptionService (L1)
│             AscriptionStatementProtectionService (L2)
│
AscriptionStatementProtectionService               $gsm:dataProtection (hash/mask/suppress)
ArchetypeSchemaService                             schema inspection + tenant resolution*
```

\* `ArchetypeSchemaService` holds `ArchetypeRepository` directly (documented
exception to the repository–service exclusivity rule) to avoid an upward
dependency on `ArchetypeService` (Layer 3).

### Layer 1: Supporting entity services

Leaf entity services with **no upward dependencies** — they do not consume
any Layer 2 or Layer 3 service.

```
DefinitionService                  stable identity (DefinitionEntity)
AscriptionService                  union cross-subtype lookups (AscriptionEntity)
AscriptionStatusTransitionService  transition record persistence
```

- **`DefinitionService`** — every GSM subject has a `DefinitionEntity`
  (stable identity). Consumed by `AbstractAscriptionService` (L2).
- **`AscriptionService`** — owns the union ascription table for cross-subtype
  lookups. Consumed by `AscriptionArchetypeSchemaAnnotationEnforcementService` (L2).
- **`AscriptionStatusTransitionService`** — owns transition record
  persistence. Consumed by `AscriptionStateMachineService` (L2).

---

## 2. Cross-entity dependency edges

Entity services reference other entity services when GSM semantics require it
(e.g. a Directive references a Structure, an Effector references a Mechanism).
These edges reflect the GSM subject type graph:

```
DirectiveService ──→ StructureService, ArchetypeService
NormService ──→ StructureService, ArchetypeService
MechanismService ──→ StructureService
EffectorService ──→ MechanismService, ArchetypeService
ReceptorService ──→ MechanismService, ArchetypeService
InteractionService ──→ EffectorService, ReceptorService
```

Subsidiary-level cross-entity edges:

```
MechanismPortDerivationService ──→ ArchetypeService,
                                   EffectorService (@Lazy),
                                   ReceptorService (@Lazy),
                                   DefinitionService
MechanismRuleValidationService ──→ ArchetypeService
NormApplicabilityValidationService ──→ ArchetypeService
```

`@Lazy` annotations break circular injection chains where a Mechanism
subsidiary must write-back Effector/Receptor ports that themselves reference
MechanismService.

---

## 3. Service roles

### 3.1 The 8 entity lifecycle services

Each extends `AbstractAscriptionService<T>` and implements the template
methods for its GSM subject type: `buildEntity`, `getIdentityBoundValues`,
`getRefereeReferences`, `getCascadeTargetRoles`, `findCascadeTargetsFrom`.

| Service              | Subject type | Key responsibility                                       |
| -------------------- | ------------ | -------------------------------------------------------- |
| `StructureService`   | STRUCTURE    | Purpose uniqueness at activation                         |
| `MechanismService`   | MECHANISM    | Starlark rule validation, auto-port derivation           |
| `EffectorService`    | EFFECTOR     | Constitutive cascade from Mechanism                      |
| `ReceptorService`    | RECEPTOR     | Constitutive cascade from Mechanism                      |
| `InteractionService` | INTERACTION  | Effector/Receptor archetype compatibility                |
| `ArchetypeService`   | ARCHETYPE    | Schema validation, annotation checks, index provisioning |
| `DirectiveService`   | DIRECTIVE    | Governing cascade from Structure                         |
| `NormService`        | NORM         | CEL applicability/assertion profile validation           |

### 3.2 Archetype subsidiary services

| Service                                       | Consumed by        | Role                                                                                                                                         |
| --------------------------------------------- | ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------- |
| `ArchetypeSchemaAnnotationValidationService`  | `ArchetypeService` | Validates `$gsm:*` annotation vocabulary and `$ref` URI policy on Archetype schemas; collects identity-bound fields                          |
| `ArchetypeSchemaCompositionValidationService` | `ArchetypeService` | Validates `$ref` chain convergence to GSM base archetypes, `allOf` facet acyclicity, `$gsm:sealed` enforcement                               |
| `ArchetypeSchemaPropertyIndexationService`    | `ArchetypeService` | Provisions/deprovisions PostgreSQL JSONB indexes driven by `$gsm:queryable` and `$gsm:unique` annotations; idempotent DDL via `JdbcTemplate` |

### 3.3 Mechanism subsidiary services

| Service                          | Consumed by                                                        | Role                                                                                                                                                                    |
| -------------------------------- | ------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `MechanismRuleParsingService`    | `MechanismRuleValidationService`, `MechanismPortDerivationService` | Shared Starlark AST parsing and chain-walking utilities: `parseStarlark`, `ChainLink`, `isSysEffectChain`/`isSysReceiveChain`, `unwrapEffectChain`/`unwrapReceiveChain` |
| `MechanismRuleValidationService` | `MechanismService`                                                 | Structural validation of Starlark rule code: syntax, execution budget, trigger uniqueness, `sys.*` API conformance, dict-literal conformance                            |
| `MechanismPortDerivationService` | `MechanismService`                                                 | Auto-derives Effector/Receptor entities from Starlark rule AST port signatures; resolves data/port archetypes; creates/reuses Definitions                               |

### 3.4 Norm subsidiary services

| Service                              | Consumed by   | Role                                                                                                               |
| ------------------------------------ | ------------- | ------------------------------------------------------------------------------------------------------------------ |
| `NormApplicabilityValidationService` | `NormService` | Validates CEL applicability expressions: pure conjunctions, single-axis predicates, archetype reference resolution |
| `NormAssertionValidationService`     | `NormService` | Validates CEL assertion expressions: deterministic, boolean-producing, archetype-bound property paths              |

### 3.5 Layer 1 — Supporting entity services

Three leaf entity services (no upward dependencies) that follow the
`{Entity}Service` pattern for their own entity type
(see [Layer 1](#layer-1-supporting-entity-services)):

| Service                             | Entity                             | Role                                                                                                                                                                   |
| ----------------------------------- | ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `DefinitionService`                 | `DefinitionEntity`                 | Stable identity. Every GSM subject has a Definition; this service manages creation and resolution. Consumed by all 8 subject services via `AbstractAscriptionService`  |
| `AscriptionService`                 | `AscriptionEntity` (union)         | Cross-subtype lookups when the caller doesn't know the concrete entity type. Consumed by `AscriptionArchetypeSchemaAnnotationEnforcementService` for uniqueness checks |
| `AscriptionStatusTransitionService` | `AscriptionStatusTransitionEntity` | Transition record persistence. Consumed by `AscriptionStateMachineService`                                                                                             |

### 3.6 Layer 2 — Shared ascription services

| Service                                                 | Role                                                                                                                                                                                                             |
| ------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `AbstractAscriptionService`                             | Base class for all 8 subject type services. Provides CRUD, create template method, lifecycle hooks (`onActivation`/`onDeactivation`), statement extraction, filtered queries, and property uniqueness validation |
| `AscriptionLifecycleOrchestrationService`               | Coordinates lifecycle transitions across all 8 subtypes: referee validation, cascade dispatch, activation handoff, governance convergence                                                                        |
| `AscriptionStateMachineService`                         | Pure state machine: transition validation, referee preconditions, cascade applicability rules. No subtype knowledge                                                                                              |
| `AscriptionStatementValidationService`                  | Validates ascription statements against archetype JSON Schemas; delegates annotation enforcement                                                                                                                 |
| `AscriptionArchetypeSchemaAnnotationEnforcementService` | Enforces `$gsm:*` vocabulary annotations at statement authoring time: identity-bound immutability, uniqueness constraints, data protection                                                                       |
| `AscriptionStatementProtectionService`                  | Applies `$gsm:dataProtection` measures (hash, mask, suppression) at write-time and read-time                                                                                                                     |
| `ArchetypeSchemaService`                                | Centralizes schema inspection: `hasAnnotation`, `extractTitleFromRef`, `isAllowedRef`, `isGsmBaseTitle`, and tenant archetype resolution. Read-only access to `ArchetypeRepository` (documented exception)       |

---

## 4. Dependency graph

Complete service-to-service dependency graph (service-internal only;
repositories, `EntityManager`, `JdbcTemplate`, `CelCompiler`, `ObjectMapper`
omitted for clarity).

```
LEGEND:  ──▷ extends   ──→ injection   ···→ abstract/list   @L = @Lazy

╔══════════════════════════════════════════════════════════════════════════════╗
║ LAYER 3 — Subject Type Services (extend AbstractAscriptionService)         ║
║                                                                            ║
║  ┌─ Archetype ──────────────────────────┐                                  ║
║  │ ArchetypeService ──→ ArchetypeSchemaAnnotationValidationService         ║
║  │       │           ──→ ArchetypeSchemaCompositionValidationService       ║
║  │       │           ──→ ArchetypeSchemaPropertyIndexationService          ║
║  └───────┼──────────────────────────────┘                                  ║
║          │                                                                 ║
║  ┌─ Mechanism ──────────────────────────┐                                  ║
║  │ MechanismService ──→ StructureService                                   ║
║  │       │           ──→ MechanismRuleValidationService ──→ ArchetypeSvc   ║
║  │       │           ──→ MechanismPortDerivationService                    ║
║  │       │                    │──→ ArchetypeService                        ║
║  │       │                    │──→ EffectorService  @L                     ║
║  │       │                    │──→ ReceptorService  @L                     ║
║  │       │                    └──→ DefinitionService (L1)                  ║
║  │       │                                                                 ║
║  │  MechanismRuleParsingService  (← RuleVal, PortDer; no cross-entity)     ║
║  └───────┼──────────────────────────────┘                                  ║
║          │                                                                 ║
║  ┌─ Norm ───────────────────────────────┐                                  ║
║  │ NormService ──→ StructureService                                        ║
║  │       │     ──→ ArchetypeService                                        ║
║  │       │     ──→ NormApplicabilityValidationService ──→ ArchetypeSvc     ║
║  │       │     ──→ NormAssertionValidationService                          ║
║  └───────┼──────────────────────────────┘                                  ║
║          │                                                                 ║
║  StructureService         (no cross-entity deps, no subsidiaries)          ║
║  DirectiveService    ──→ StructureService, ArchetypeService                ║
║  EffectorService     ──→ MechanismService, ArchetypeService                ║
║  ReceptorService     ──→ MechanismService, ArchetypeService                ║
║  InteractionService  ──→ EffectorService, ReceptorService                  ║
║                                                                            ║
║  all 8 ──▷ AbstractAscriptionService                                       ║
╚═══════════════╤════════════════════════════════════════════════════════════╝
                │
╔═══════════════╧════════════════════════════════════════════════════════════╗
║ LAYER 2 — Shared Ascription Services                                      ║
║                                                                            ║
║  AbstractAscriptionService (abstract base for all 8)                       ║
║       │──→ DefinitionService (L1)                                          ║
║       │──→ AscriptionStateMachineService                                   ║
║       └──→ AscriptionStatementValidationService                            ║
║                   │──→ ArchetypeSchemaService                              ║
║                   └──→ AscriptionArchetypeSchemaAnnotationEnforcementSvc   ║
║                              │──→ AscriptionService (L1)                   ║
║                              └──→ AscriptionStatementProtectionService     ║
║                                                                            ║
║  AscriptionLifecycleOrchestrationService                                   ║
║       │──→ AscriptionStateMachineService                                   ║
║       └···→ List<AbstractAscriptionService<?>>  (all 8)                    ║
║                                                                            ║
║  AscriptionStateMachineService                                             ║
║       └──→ AscriptionStatusTransitionService (L1)                          ║
║                                                                            ║
║  ArchetypeSchemaService  (holds ArchetypeRepository directly*)             ║
║  AscriptionStatementProtectionService  (no service deps)                   ║
╚═══════════════╤════════════════════════════════════════════════════════════╝
                │
╔═══════════════╧════════════════════════════════════════════════════════════╗
║ LAYER 1 — Supporting Entity Services (leaf, no upward deps)               ║
║                                                                            ║
║  DefinitionService                  stable identity                        ║
║  AscriptionService                  cross-subtype union lookups            ║
║  AscriptionStatusTransitionService  transition record persistence          ║
╚════════════════════════════════════════════════════════════════════════════╝

* ArchetypeSchemaService holds ArchetypeRepository directly — documented
  exception to the repository–service exclusivity rule (avoids upward
  dependency on ArchetypeService in Layer 3).
```

---

## 5. Reading order for newcomers

1. **`AbstractAscriptionService`** — understand the template method pattern
   and lifecycle hooks that all 8 entity services share.
2. **`StructureService`** — simplest entity service (no subsidiaries, no
   cross-entity references). Good baseline for the pattern.
3. **`ArchetypeService`** + its 3 subsidiaries — richest entity group;
   demonstrates schema validation pipeline and index provisioning.
4. **`AscriptionLifecycleOrchestrationService`** — how transitions are
   coordinated across subtypes (cascades, referee checks, activation).
5. **`AscriptionStateMachineService`** — pure state machine rules, isolated
   from entity knowledge.
6. **`MechanismService`** + subsidiaries — rule validation and port
   auto-derivation illustrate the most complex subsidiary interactions.
7. **`NormService`** + subsidiaries — CEL expression profile validation.
8. **Cross-entity statement validation chain** — follow from
   `AscriptionStatementValidationService` → `ArchetypeSchemaService` →
   `AscriptionArchetypeSchemaAnnotationEnforcementService` to understand
   how statements are validated and annotations enforced at authoring time.
