# SIE SDM — Systemic Intelligence Engine (Systemic Data/Definition Models)

This folder contains **architecture/model documentation**, primarily as **PlantUML class diagrams** plus a small amount of **PostgreSQL SQL** that translates parts of the conceptual model.

There is **no runnable application** here (no build/test/debug workflow). The primary developer workflow is: edit diagrams/SQL → render diagrams for review.

## Where to start

- **CSDM (Canonical Systemic Definition Model)**: [concept/model/sie-csdm-v1.puml](concept/model/sie-csdm-v1.puml)
  - Core systemic primitives (System, Interface, Mechanism, Control, etc.)
  - Meta-model constructs (archetypes, schemas, qualifiers)

- **CSDSL (Canonical Systemic Domain Specific Language)**: [concept/model/sie-csdsl-v1.puml](concept/model/sie-csdsl-v1.puml)
  - AST model for logical/comparison expressions used by control conditions

- **SQL translation artifacts**: [concept/model/sql/](concept/model/sql/)
  - Example: qualifiers + schema registry + enforcement trigger in
    [concept/model/sql/sie-csdm-qualifiers-v1.sql](concept/model/sql/sie-csdm-qualifiers-v1.sql)

## Rendering diagrams (PlantUML)

### VS Code (recommended)

- Install a PlantUML extension (commonly `jebbs.plantuml`).
- Use **Local** render.
- A pinned PlantUML jar is available at [tools/plantuml/plantuml.jar](tools/plantuml/plantuml.jar).
- VS Code settings are in [.vscode/settings.json](.vscode/settings.json).

### CLI

Run from this folder (`sie/sie-sdm/`):

```bash
java -jar tools/plantuml/plantuml.jar -tpng concept/**/*.puml
```

If you prefer SVG output:

```bash
java -jar tools/plantuml/plantuml.jar -tsvg concept/**/*.puml
```

Notes:

- The current diagrams are effectively standalone (no `!include`). If you introduce `!include`, make sure include paths resolve both in VS Code and CLI.

## Conventions (important when editing)

- **Versioned files**: diagrams are named `*-v1.puml`. For breaking/semantic changes, prefer creating a `-v2` file instead of rewriting `-v1`.
- **Style preservation**: keep existing `VISUAL THEME` blocks, `frame "… - v1" { ... }` titles, section separators, and note formatting.
- **SQL (PostgreSQL)**:
  - Prefer re-runnable DDL (`create table if not exists`).
  - Commonly wrap changes in `begin; ... commit;`.
  - Expect “conceptual-to-SQL translation” patterns like anchor/supertype tables + triggers for invariants.
