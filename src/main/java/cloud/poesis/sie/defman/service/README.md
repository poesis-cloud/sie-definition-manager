# Service Package — Reader's Guide

> **Package**: `cloud.poesis.sie.defman.service` > **30 files** (1 interface + 26 `@Service` beans + 1 shared `@Component` + 2 static utilities)
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
║  │ ArchetypeService ──→ ArchetypeIdentityValidationService         │    ║
║  │                  ──→ ArchetypeAnnotationValidationService       │    ║
║  │                  ──→ ArchetypeCompositionValidationService      │    ║
║  │                  ──→ ArchetypeSchemaResolverService             │    ║
║  │                  ──→ ArchetypePropertyIndexationService         │    ║
║  │                  ──→ JsonSchemaPositionWalker                   │    ║
║  │                                                                  │    ║
║  │  ArchetypeIdentityValidationService ──→ JsonSchemaPositionWalker│    ║
║  │  ArchetypeAnnotationValidationService ──→ JsonSchemaPositionWalker   ║
║  │                                       ──→ ArchetypeSchemaResolverService  ║
║  │  ArchetypeSchemaResolverService  ──→ ArchetypeParsingService     │    ║
║  │                                  ──→ ArchetypeCompositionValidationService║
║  │  ArchetypePropertyIndexationService ──→ ArchetypeSchemaResolverService    ║
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
║       │──→ ArchetypeParsingService                                      ║
║       └──→ JsonSchemaPositionWalker                                     ║
║                                                                          ║
║  AscriptionIdentityBoundValidationService ──→ ArchetypeSchemaResolverService  ║
║  AscriptionUniquenessValidationService    ──→ ArchetypeSchemaResolverService  ║
║  AscriptionProtectionService              ──→ ArchetypeSchemaResolverService  ║
║  AscriptionParsingService                  (static utilities)           ║
║  ArchetypeParsingService                   (no service deps)            ║
║  DefinitionService                         (no service deps)            ║
║                                                                          ║
╠════════════════════════════════════════════════════════════════════════════╣
║ Shared component and utilities                                           ║
║                                                                          ║
║  JsonSchemaPositionWalker  (shared schema-position traversal component)  ║
║  AscriptionParsingService  (static, not a Spring bean)                   ║
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
| `ArchetypeIdentityValidationService`    | `ArchetypeService` | Validates Archetype URI `$id` grammar, `$id`/`title` coherence, and root-only `$id` placement                                                |
| `ArchetypeAnnotationValidationService`  | `ArchetypeService` | Validates `$gsm:*` annotation vocabulary and `$ref` URI policy on Archetype schemas; collects identity-bound fields                          |
| `ArchetypeCompositionValidationService` | `ArchetypeService` | Validates `$ref` chain convergence to GSM base archetypes, `allOf` facet acyclicity, `$gsm:sealed` enforcement; resolves the composition chain's property set                               |
| `ArchetypeSchemaResolverService`        | `ArchetypeService`, `ArchetypeAnnotationValidationService`, `ArchetypePropertyIndexationService`, `AscriptionIdentityBoundValidationService`, `AscriptionUniquenessValidationService`, `AscriptionProtectionService` | The single `gsmarc://` URI → schema resolver (governed store first, vendored classpath snapshot as fallback for GSM bases), and GSM §11.1 annotation inheritance: resolves an Archetype's property set over its resolved composition chain (own `properties` + `$ref` chain + `allOf` facets); cached per Ascription id |
| `ArchetypePropertyIndexationService`    | `ArchetypeService` | Provisions/deprovisions PostgreSQL JSONB indexes driven by `$gsm:queryable` and `$gsm:unique` annotations resolved over the composition chain; idempotent DDL via `JdbcTemplate` |

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
| `AscriptionProtectionService`              | Applies `$gsm:dataProtection` measures (hash, mask, suppression) at write-time and read-time; fails closed on a declared measure the processor does not implement                |
| `AscriptionParsingService`                 | Static utilities for statement field extraction (UUID parsing, required-field validation)                                                                      |
| `ArchetypeParsingService`                  | Schema inspection utilities: annotation detection, title extraction, `$ref` resolution, base-type checking                                                     |
| `DefinitionService`                        | Stable identity resolution/creation for `DefinitionEntity`                                                                                                     |

### 2.6 Shared component and utilities

`JsonSchemaPositionWalker` is a package-private Spring component shared by
`ArchetypeService`, `ArchetypeIdentityValidationService`,
`ArchetypeAnnotationValidationService`, and
`AscriptionParsingValidationService`. It visits only Draft 2020-12
schema-valued positions and reports each location as a JSON Pointer. It is a
traversal utility rather than a domain service, so its `Walker` name is
intentional.

`AscriptionParsingService` is a static utility class (not a Spring bean) for
typed statement-field extraction and required-field validation.

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
4. **`ArchetypeService`** + its 4 subsidiaries — richest service group;
   demonstrates schema validation pipeline and index provisioning.
5. **`AscriptionStatusTransitionService`** — how transitions are
   coordinated across subtypes (cascades, referee checks, activation).
6. **`AscriptionStateMachineService`** — pure state machine rules, isolated
   from entity knowledge.
7. **`MechanismService`** + subsidiaries — rule validation and port
   derivation illustrate the most complex subsidiary interactions.
8. **`NormService`** + subsidiaries — CEL expression profile validation.
