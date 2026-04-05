# Service Package — Reader's Guide

> **Package**: `cloud.poesis.sie.defman.service` > **27 files** (1 interface + 25 `@Service` beans + 1 static utility)
> managing the GSM ascription lifecycle, statement validation, schema
> governance, and cross-entity orchestration for the 8 GSM subject types.

---

## 1. Package topology

The service package is organized in three dependency layers. Every
dependency arrow points **downward**; no lower layer depends on a higher one.

```
Layer 3 — Subject type handlers                8 handler services + subsidiaries
    │  implement SubtypeHandler<T> / delegate to
Layer 2 — Facade & shared ascription services  create template, lifecycle, validation
    │  persist / query via
Layer 1 — Supporting entity services           leaf entity services (no upward deps)
```

`PersistenceExceptionTranslationService` is a static utility class (not a
Spring bean) outside all layers — maps PostgreSQL constraint names to domain
exception types.

### Layer 3: Subject type handler services

The 8 GSM subject type services, each implementing `SubtypeHandler<T>`:

```
archetype/
├── ArchetypeService                              primary handler
├── ArchetypeSchemaAnnotationValidationService     subsidiary
├── ArchetypeSchemaCompositionValidationService    subsidiary
└── ArchetypeSchemaPropertyIndexationService       subsidiary

mechanism/
├── MechanismService                               primary handler
├── MechanismRuleParsingService                    subsidiary (shared)
├── MechanismRuleValidationService                 subsidiary
└── MechanismPortDerivationService                 subsidiary

norm/
├── NormService                                    primary handler
├── NormApplicabilityValidationService             subsidiary
└── NormAssertionValidationService                 subsidiary

structure/
└── StructureService                               primary handler

directive/
└── DirectiveService                               primary handler

effector/
└── EffectorService                                primary handler

receptor/
└── ReceptorService                                primary handler

interaction/
└── InteractionService                             primary handler
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
Receptor, Interaction) have no third-party technology integration and
therefore no subsidiaries — their domain logic fits entirely within the
primary handler service.

### Layer 2: Facade & shared ascription services

`AscriptionService` is the **facade** that owns the 10-step create template,
generic CRUD dispatch, and cross-subtype lookups. It injects all 8 handlers
via `List<SubtypeHandler<?>>` and builds a type-keyed map at startup
(`SmartInitializingSingleton`).

`AscriptionLifecycleOrchestrationService` coordinates lifecycle transitions
(cascades, referee preconditions, activation handoff) across all 8 subtypes,
also via `List<SubtypeHandler<?>>`.

The remaining shared services implement the ascription lifecycle — state
machine transitions, statement validation, annotation enforcement. They
depend downward on Layer 1 entity services and on each other within Layer 2.

```
SubtypeHandler<T>                                  interface for all 8 handler services
│ methods: getSubjectType, getRepository, buildEntity,
│          getIdentityBoundValues, getRefereeReferences,
│          getCascadeTargetRoles, findCascadeTargetsFrom
│ defaults: save, findAllByDefinitionId, afterCreate,
│           onActivation, onDeactivation,
│           validateActivationUniqueness, statementValidationRule

AscriptionService (facade)                         10-step create, CRUD, handler dispatch
│   consumes: DefinitionService (L1)
│             AscriptionStateMachineService (L2)
│             AscriptionStatementValidationService (L2)
│             List<SubtypeHandler<?>> (all 8 handlers)

AscriptionLifecycleOrchestrationService            coordinates lifecycle across all 8
│   consumes: AscriptionStateMachineService (L2)
│             List<SubtypeHandler<?>> (all 8 handlers)

AscriptionStateMachineService                      pure state machine
│   consumes: AscriptionStatusTransitionService (L1)

AscriptionStatementValidationService               JSON Schema + annotation validation
│   consumes: ArchetypeSchemaService (L2)
│             AscriptionArchetypeSchemaAnnotationEnforcementService (L2)

AscriptionArchetypeSchemaAnnotationEnforcementSvc   $gsm:* enforcement on write
│   consumes: AscriptionService (@Lazy, facade)
│             AscriptionStatementProtectionService (L2)

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
AscriptionStatusTransitionService  transition record persistence
```

- **`DefinitionService`** — every GSM subject has a `DefinitionEntity`
  (stable identity). Consumed by `AscriptionService` (L2 facade).
- **`AscriptionStatusTransitionService`** — owns transition record
  persistence. Consumed by `AscriptionStateMachineService` (L2).

---

## 2. Cross-entity dependency edges

Handler services reference other handler services when GSM semantics require
it (e.g. a Directive references a Structure, an Effector references a
Mechanism). These edges reflect the GSM subject type graph:

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

### `@Lazy` annotations (3 total)

| Service                                                 | `@Lazy` parameter   | Reason                                                      |
| ------------------------------------------------------- | ------------------- | ----------------------------------------------------------- |
| `MechanismPortDerivationService`                        | `EffectorService`   | Breaks Mechanism → PortDer → Effector → Mechanism cycle     |
| `MechanismPortDerivationService`                        | `ReceptorService`   | Breaks Mechanism → PortDer → Receptor → Mechanism cycle     |
| `AscriptionArchetypeSchemaAnnotationEnforcementService` | `AscriptionService` | Breaks Facade → StatementVal → AnnotationEnf → Facade cycle |

---

## 3. Service roles

### 3.1 The 8 handler services

Each implements `SubtypeHandler<T>` and provides the subtype-specific
methods: `buildEntity`, `getIdentityBoundValues`, `getRefereeReferences`,
`getCascadeTargetRoles`, `findCascadeTargetsFrom`, plus optional overrides
for lifecycle hooks.

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
| `MechanismPortDerivationService` | `MechanismService`                                                 | Auto-derives Effector/Receptor entities from Starlark rule AST port signatures; resolves data/port archetypes; creates fresh Definitions per port                       |

### 3.4 Norm subsidiary services

| Service                              | Consumed by   | Role                                                                                                               |
| ------------------------------------ | ------------- | ------------------------------------------------------------------------------------------------------------------ |
| `NormApplicabilityValidationService` | `NormService` | Validates CEL applicability expressions: pure conjunctions, single-axis predicates, archetype reference resolution |
| `NormAssertionValidationService`     | `NormService` | Validates CEL assertion expressions: deterministic, boolean-producing, archetype-bound property paths              |

### 3.5 Layer 1 — Supporting entity services

| Service                             | Entity                             | Role                                                                       |
| ----------------------------------- | ---------------------------------- | -------------------------------------------------------------------------- |
| `DefinitionService`                 | `DefinitionEntity`                 | Stable identity. Consumed by `AscriptionService` (facade)                  |
| `AscriptionStatusTransitionService` | `AscriptionStatusTransitionEntity` | Transition record persistence. Consumed by `AscriptionStateMachineService` |

### 3.6 Layer 2 — Facade & shared ascription services

| Service                                                 | Role                                                                                                                                                                                                    |
| ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SubtypeHandler<T>`                                     | Interface contract for all 8 handler services: entity building, identity-bound values, referee references, cascade targets, lifecycle hooks, repository convenience defaults                            |
| `AscriptionService`                                     | Facade: 10-step create template, handler dispatch via `SmartInitializingSingleton`, generic CRUD, cross-subtype lookups, property uniqueness, filter spec building                                      |
| `AscriptionLifecycleOrchestrationService`               | Coordinates lifecycle transitions across all 8 subtypes: referee validation, cascade dispatch, activation handoff, governance convergence                                                               |
| `AscriptionStateMachineService`                         | Pure state machine: transition validation, referee preconditions, cascade applicability rules. No subtype knowledge                                                                                     |
| `AscriptionStatementValidationService`                  | Validates ascription statements against archetype JSON Schemas; delegates annotation enforcement                                                                                                        |
| `AscriptionArchetypeSchemaAnnotationEnforcementService` | Enforces `$gsm:*` vocabulary annotations at statement authoring time: identity-bound immutability, uniqueness constraints, data protection                                                              |
| `AscriptionStatementProtectionService`                  | Applies `$gsm:dataProtection` measures (hash, mask, suppression) at write-time and read-time                                                                                                            |
| `ArchetypeSchemaService`                                | Centralizes schema inspection: `hasAnnotation`, `extractTitleFromRef`, `isAllowedRef`, `isGsmBaseTitle`, and tenant archetype resolution. Read-only `ArchetypeRepository` access (documented exception) |

---

## 4. Dependency graph

Complete service-to-service dependency graph (service-internal only;
repositories, `EntityManager`, `JdbcTemplate`, `CelCompiler`, `ObjectMapper`
omitted for clarity).

```
LEGEND:  ──→ injection   ···→ list injection   @L = @Lazy

╔══════════════════════════════════════════════════════════════════════════════╗
║ LAYER 3 — Subject Type Handlers (implement SubtypeHandler<T>)              ║
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
╚═══════════════╤════════════════════════════════════════════════════════════╝
                │ all 8 implement SubtypeHandler<T>
╔═══════════════╧════════════════════════════════════════════════════════════╗
║ LAYER 2 — Facade & Shared Ascription Services                             ║
║                                                                            ║
║  AscriptionService (facade, SmartInitializingSingleton)                     ║
║       │──→ DefinitionService (L1)                                          ║
║       │──→ AscriptionStateMachineService                                   ║
║       │──→ AscriptionStatementValidationService                            ║
║       └···→ List<SubtypeHandler<?>>  (all 8 handlers)                      ║
║                                                                            ║
║  AscriptionLifecycleOrchestrationService (SmartInitializingSingleton)       ║
║       │──→ AscriptionStateMachineService                                   ║
║       └···→ List<SubtypeHandler<?>>  (all 8 handlers)                      ║
║                                                                            ║
║  AscriptionStatementValidationService                                      ║
║       │──→ ArchetypeSchemaService                                          ║
║       └──→ AscriptionArchetypeSchemaAnnotationEnforcementSvc               ║
║                   │──→ AscriptionService  @L (facade)                      ║
║                   └──→ AscriptionStatementProtectionService                ║
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
║  AscriptionStatusTransitionService  transition record persistence          ║
╚════════════════════════════════════════════════════════════════════════════╝

PersistenceExceptionTranslationService  (static utility, not a Spring bean)

* ArchetypeSchemaService holds ArchetypeRepository directly — documented
  exception to the repository–service exclusivity rule (avoids upward
  dependency on ArchetypeService in Layer 3).
```

---

## 5. Reading order for newcomers

1. **`SubtypeHandler<T>`** — understand the interface contract (required
   methods + default hooks) that all 8 handler services implement.
2. **`StructureService`** — simplest handler (no subsidiaries, no
   cross-entity references). Good baseline for the pattern.
3. **`AscriptionService`** — the facade: 10-step create template, handler
   dispatch via `SmartInitializingSingleton`, generic CRUD, static utilities.
4. **`ArchetypeService`** + its 3 subsidiaries — richest handler group;
   demonstrates schema validation pipeline and index provisioning.
5. **`AscriptionLifecycleOrchestrationService`** — how transitions are
   coordinated across subtypes (cascades, referee checks, activation).
6. **`AscriptionStateMachineService`** — pure state machine rules, isolated
   from entity knowledge.
7. **`MechanismService`** + subsidiaries — rule validation and port
   auto-derivation illustrate the most complex subsidiary interactions.
8. **`NormService`** + subsidiaries — CEL expression profile validation.
9. **Cross-entity statement validation chain** — follow from
   `AscriptionStatementValidationService` → `ArchetypeSchemaService` →
   `AscriptionArchetypeSchemaAnnotationEnforcementService` to understand
   how statements are validated and annotations enforced at authoring time.
