# Service Package — Reader's Guide

> **Package**: `cloud.poesis.sie.defman.service` > **28 files** (1 interface + 26 `@Service` beans + 1 static utility)
> managing the GSM ascription lifecycle, statement validation, schema
> governance, and cross-entity orchestration for the 8 GSM subject types.

---

## 1. Dependency graph

Complete service-to-service dependency graph (service-internal only;
repositories, `EntityManager`, `JdbcTemplate`, `CelCompiler`, `ObjectMapper`
omitted for clarity).

```
LEGEND:  ──→ injection   ···→ list injection   @L = @Lazy

╔════════════════════════════════════════════════════════════════════════════╗
║ 8 Subject Type Services (implement AscriptionSubtypeService<T>)          ║
║                                                                          ║
║  ┌─ Archetype ──────────────────────────────────────────────────────┐    ║
║  │ ArchetypeService ──→ ArchetypeAnnotationValidationService       │    ║
║  │                  ──→ ArchetypeCompositionValidationService      │    ║
║  │                  ──→ ArchetypePropertyIndexationService         │    ║
║  └──────────────────────────────────────────────────────────────────┘    ║
║                                                                          ║
║  ┌─ Mechanism ──────────────────────────────────────────────────────┐    ║
║  │ MechanismService ──→ StructureService                           │    ║
║  │                  ──→ MechanismRuleValidationService              │    ║
║  │                  ──→ MechanismPortDerivationService              │    ║
║  │                  ──→ AscriptionService  @L                      │    ║
║  │                                                                  │    ║
║  │  MechanismRuleValidationService ──→ ArchetypeService            │    ║
║  │                                 ──→ MechanismRuleParsingService │    ║
║  │  MechanismPortDerivationService ──→ ArchetypeService            │    ║
║  │                                 ──→ MechanismRuleParsingService │    ║
║  │  MechanismRuleParsingService    (no service deps)               │    ║
║  └──────────────────────────────────────────────────────────────────┘    ║
║                                                                          ║
║  ┌─ Norm ───────────────────────────────────────────────────────────┐    ║
║  │ NormService ──→ StructureService                                │    ║
║  │             ──→ ArchetypeService                                │    ║
║  │             ──→ NormApplicabilityValidationService              │    ║
║  │             ──→ NormAssertionValidationService                  │    ║
║  │                                                                  │    ║
║  │  NormApplicabilityValidationService ──→ ArchetypeService        │    ║
║  │  NormAssertionValidationService     (no service deps)           │    ║
║  └──────────────────────────────────────────────────────────────────┘    ║
║                                                                          ║
║  StructureService         (no cross-entity deps, no subsidiaries)       ║
║  DirectiveService    ──→ StructureService, ArchetypeService             ║
║  EffectorService     ──→ MechanismService, ArchetypeService             ║
║  ReceptorService     ──→ MechanismService, ArchetypeService             ║
║  InteractionService  ──→ EffectorService, ReceptorService               ║
║                                                                          ║
╠════════════════════════════════════════════════════════════════════════════╣
║ Shared Ascription Services                                               ║
║                                                                          ║
║  AscriptionService (facade, SmartInitializingSingleton)                  ║
║       │──→ ArchetypeService                                             ║
║       │──→ DefinitionService                                            ║
║       │──→ AscriptionStateMachineService                                ║
║       │──→ AscriptionParsingValidationService                           ║
║       │──→ AscriptionIdentityBoundValidationService                     ║
║       │──→ AscriptionUniquenessValidationService                        ║
║       │──→ AscriptionProtectionService                                  ║
║       └···→ List<AscriptionSubtypeService<?>>  (all 8)                  ║
║                                                                          ║
║  AscriptionStatusTransitionService (SmartInitializingSingleton)          ║
║       │──→ AscriptionStateMachineService                                ║
║       └···→ List<AscriptionSubtypeService<?>>  (all 8)                  ║
║                                                                          ║
║  AscriptionStateMachineService     (zero-dependency pure validation)    ║
║                                                                          ║
║  AscriptionParsingValidationService                                     ║
║       └──→ ArchetypeParsingService                                      ║
║                                                                          ║
║  AscriptionIdentityBoundValidationService  (no service deps)            ║
║  AscriptionUniquenessValidationService     (no service deps)            ║
║  AscriptionProtectionService               (no service deps)            ║
║  AscriptionParsingService                  (static utilities)           ║
║  ArchetypeParsingService                   (no service deps)            ║
║  DefinitionService                         (no service deps)            ║
║                                                                          ║
╠════════════════════════════════════════════════════════════════════════════╣
║ Utility                                                                  ║
║                                                                          ║
║  PersistenceExceptionTranslationService  (static, not a Spring bean)    ║
╚════════════════════════════════════════════════════════════════════════════╝
```

### `@Lazy` annotations (1 total)

| Service          | `@Lazy` parameter | Reason                                                                               |
| ---------------- | ----------------- | ------------------------------------------------------------------------------------ |
| MechanismService | AscriptionService | Breaks AscriptionService → handler list → MechanismService → AscriptionService cycle |

---

## 2. Service roles

### 2.1 The 8 subject type services

Each implements `AscriptionSubtypeService<T>` and provides subtype-specific
methods: `create`, `getIdentityBoundValues`, `getRefereeReferences`,
`getCascadeTargetRoles`, `findCascadeTargetsFrom`, plus optional overrides
for lifecycle hooks.

| Service              | Subject type | Key responsibility                                                   |
| -------------------- | ------------ | -------------------------------------------------------------------- |
| `StructureService`   | STRUCTURE    | Purpose uniqueness at activation                                     |
| `MechanismService`   | MECHANISM    | Starlark rule validation, port derivation → AscriptionService.create |
| `EffectorService`    | EFFECTOR     | Constitutive cascade from Mechanism                                  |
| `ReceptorService`    | RECEPTOR     | Constitutive cascade from Mechanism                                  |
| `InteractionService` | INTERACTION  | Effector/Receptor archetype compatibility                            |
| `ArchetypeService`   | ARCHETYPE    | Schema validation, annotation checks, index provisioning             |
| `DirectiveService`   | DIRECTIVE    | Governing cascade from Structure                                     |
| `NormService`        | NORM         | CEL applicability/assertion profile validation                       |

### 2.2 Archetype subsidiary services

| Service                                 | Consumed by        | Role                                                                                                                                         |
| --------------------------------------- | ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------- |
| `ArchetypeAnnotationValidationService`  | `ArchetypeService` | Validates `$gsm:*` annotation vocabulary and `$ref` URI policy on Archetype schemas; collects identity-bound fields                          |
| `ArchetypeCompositionValidationService` | `ArchetypeService` | Validates `$ref` chain convergence to GSM base archetypes, `allOf` facet acyclicity, `$gsm:sealed` enforcement                               |
| `ArchetypePropertyIndexationService`    | `ArchetypeService` | Provisions/deprovisions PostgreSQL JSONB indexes driven by `$gsm:queryable` and `$gsm:unique` annotations; idempotent DDL via `JdbcTemplate` |

### 2.3 Mechanism subsidiary services

| Service                          | Consumed by                                                        | Role                                                                                                                                                                              |
| -------------------------------- | ------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `MechanismRuleParsingService`    | `MechanismRuleValidationService`, `MechanismPortDerivationService` | Shared Starlark AST parsing and chain-walking utilities: `parseStarlark`, `ChainLink`, `isSysEffectChain`/`isSysReceiveChain`, `unwrapEffectChain`/`unwrapReceiveChain`           |
| `MechanismRuleValidationService` | `MechanismService`                                                 | Structural validation of Starlark rule code: syntax, execution budget, trigger uniqueness, `sys.*` API conformance, dict-literal conformance                                      |
| `MechanismPortDerivationService` | `MechanismService`                                                 | Derives port specifications (archetypeId + statement) from Starlark rule AST; returns `List<PortDerivation>` for `MechanismService.afterCreate` to create via `AscriptionService` |

### 2.4 Norm subsidiary services

| Service                              | Consumed by   | Role                                                                                                               |
| ------------------------------------ | ------------- | ------------------------------------------------------------------------------------------------------------------ |
| `NormApplicabilityValidationService` | `NormService` | Validates CEL applicability expressions: pure conjunctions, single-axis predicates, archetype reference resolution |
| `NormAssertionValidationService`     | `NormService` | Validates CEL assertion expressions: deterministic, boolean-producing, archetype-bound property paths              |

### 2.5 Shared ascription services

| Service                                    | Role                                                                                                                                                           |
| ------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `AscriptionSubtypeService<T>`              | Interface for all 8 subject type services: entity creation, identity-bound values, referee references, cascade targets, lifecycle hooks, repository defaults   |
| `AscriptionService`                        | Facade: 10-step create template, handler dispatch via `SmartInitializingSingleton`, generic CRUD, cross-subtype lookups, filter spec building                  |
| `AscriptionStatusTransitionService`        | Transition persistence, lifecycle orchestration (cascades, referee preconditions, activation handoff, governance convergence) via `SmartInitializingSingleton` |
| `AscriptionStateMachineService`            | Zero-dependency pure state machine: transition validation, referee preconditions, cascade applicability rules                                                  |
| `AscriptionParsingValidationService`       | Validates ascription statements against archetype JSON Schemas                                                                                                 |
| `AscriptionIdentityBoundValidationService` | Validates identity-bound field immutability across ascriptions of the same definition                                                                          |
| `AscriptionUniquenessValidationService`    | Validates handler-defined activation uniqueness constraints                                                                                                    |
| `AscriptionProtectionService`              | Applies `$gsm:dataProtection` measures (hash, mask, suppression) at write-time                                                                                 |
| `AscriptionParsingService`                 | Static utilities for statement field extraction (UUID parsing, required-field validation)                                                                      |
| `ArchetypeParsingService`                  | Schema inspection utilities: annotation detection, title extraction, `$ref` resolution, base-type checking                                                     |
| `DefinitionService`                        | Stable identity resolution/creation for `DefinitionEntity`                                                                                                     |

### 2.6 Utility

`PersistenceExceptionTranslationService` is a static utility class (not a
Spring bean) — maps PostgreSQL constraint names to domain exception types.

---

## 3. Subsidiary pattern

Every `{Entity}{Concern}Service` is injected into and consumed exclusively
by its corresponding `{Entity}Service`. No subsidiary is consumed by a
different entity's service.

The three entity groups that have subsidiaries are precisely those that
integrate third-party technology stacks:

| Entity group  | Technology integration           | Subsidiaries handle                                                                       |
| ------------- | -------------------------------- | ----------------------------------------------------------------------------------------- |
| **Archetype** | JSON Schema                      | Schema composition validation, annotation vocabulary validation, JSONB index provisioning |
| **Norm**      | CEL (Common Expression Language) | Applicability profile validation, assertion profile validation                            |
| **Mechanism** | Starlark                         | Starlark AST parsing (shared), rule structural validation, port derivation from AST       |

The remaining 5 subject type services (Structure, Directive, Effector,
Receptor, Interaction) have no third-party technology integration and
therefore no subsidiaries.

---

## 4. Reading order for newcomers

1. **`AscriptionSubtypeService<T>`** — understand the interface contract
   (required methods + default hooks) that all 8 services implement.
2. **`StructureService`** — simplest service (no subsidiaries, no
   cross-entity references). Good baseline for the pattern.
3. **`AscriptionService`** — the facade: 10-step create template, handler
   dispatch via `SmartInitializingSingleton`, generic CRUD, static utilities.
4. **`ArchetypeService`** + its 3 subsidiaries — richest service group;
   demonstrates schema validation pipeline and index provisioning.
5. **`AscriptionStatusTransitionService`** — how transitions are
   coordinated across subtypes (cascades, referee checks, activation).
6. **`AscriptionStateMachineService`** — pure state machine rules, isolated
   from entity knowledge.
7. **`MechanismService`** + subsidiaries — rule validation and port
   derivation illustrate the most complex subsidiary interactions.
8. **`NormService`** + subsidiaries — CEL expression profile validation.
