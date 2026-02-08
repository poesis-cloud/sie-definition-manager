# SIE Features Backlog Coherence Audit

This is a *design-time* coherence check over `backlog-export.json`. It focuses on:
- **Functional grounding** (UseCase coverage and UC mapping completeness)
- **Value** (market value bucket coverage)
- **Feasibility** (complexity + feasibility tier coverage)

## Inventory

- Total items: **101**
- By kind:
  - Capability: **12**
  - EnablerCapability: **9**
  - EnablerEpic: **3**
  - EnablerFeature: **11**
  - Epic: **5**
  - Feature: **49**
  - UseCase: **12**

## Functional

### Capability ↔ UseCase coverage

- All **12** Capabilities have at least one UseCase (by title match).

### UseCase mapping completeness

- All UseCases have `epicId` + `capability` + `feature` mapping fields.

## Value

In the current models, **Market Value buckets (P0–P3) are assigned at Capability level**
via packages in `sie-analytics-market-value-v1.puml`. That’s why Features/Epics/Enablers
do not carry `priorityBucket` fields in the export.

- All Capabilities have a `priorityBucket`.

## Feasibility

In the current models, **Complexity + Feasibility tiers are assigned at Capability level**
via packages/notes in `sie-analytics-feature-evaluation-v1.puml`, and additionally for UseCases
via UC notes in `analytics-usecases/*.puml`.

- All Capabilities have `complexity` and `feasibility`.

### Feature-level evaluation exceptions

Most Features do not carry evaluation fields. The export currently includes eval fields for:
- E-5/systemic-reasoning/emergence-detection (Emergence Detection): complexity=HIGH, feasibility=RESEARCH

## Dependency coherence (`requires`)

- Requires edges exported: **7**
- All `requires` references resolve and point to Enabler Epics (runway).

## Coherence notes / options

- If you want **Feature-level** value/feasibility (instead of Capability-level), the smallest
  coherent change is: keep the Capability buckets as *defaults* and add **explicit overrides**
  only where needed (to avoid exploding the evaluation/mkt-value diagrams).
- Enabler items currently act as *prerequisite runway*; adding evaluation notes for EN-1/EN-2/EN-3
  (complexity/feasibility only) would improve planning without conflating them with market value.
