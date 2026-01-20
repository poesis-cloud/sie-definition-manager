
# SIE SDM — Systemic Intelligence Engine (System Design Models)

This folder contains **architecture/model documentation**, primarily as **PlantUML class diagrams** under `concept/model/`, plus a small set of **PostgreSQL SQL translation artifacts** under `concept/model/sql/`. It is not an executable application.

## What’s in here

- `concept/model/sie-csdm-v1.puml` — **CSDM** (Canonical Systemic Definition Model)
  - Defines systemic primitives, schemas, qualifiers, and rules (see the extensive notes inside the diagram).
- `concept/model/sie-dslm-v1.puml` — **DSLM** (Domain Specific Language Model)
- `concept/model/sie-smm-v1.puml` — **SMM** (Schema Management Model)
- `concept/model/sql/` — SQL translations meant to be **re-runnable** and aligned with the conceptual model
  - Example: `concept/model/sql/sie-csdm-qualifiers-v1.sql` (PostgreSQL; `jsonb`, `plpgsql`, idempotent DDL)

## PlantUML rendering (VS Code)

Recommended workflow is the PlantUML extension (commonly `jebbs.plantuml`) using **Local render**.

Repo-provided settings:

- `.vscode/settings.json` configures include paths to make `!include` work reliably.
- `tools/plantuml/plantuml.jar` pins a renderer version.

If diagrams fail to render, check:

- `!include` paths in the `.puml` file
- `plantuml.commandArgs` include path in `.vscode/settings.json`

## CLI rendering (optional)

Run from the `sie/sie-sdm/` directory:

```bash
java -jar tools/plantuml/plantuml.jar -tpng concept/model/**/*.puml
```

## Conventions (important when editing)

- **Versioned filenames**: diagrams are named `*-v1.puml`. Prefer creating `-v2` for breaking/semantic changes rather than rewriting `-v1`.
- **Diagram framing**: diagrams use a top-level `frame "… - v1" { ... }` (e.g. `CD - … - v1`).
- **Style preservation**: keep the existing indentation, section separators, and note formatting. Large diagrams (notably `sie-csdm-v1.puml`) are intentionally structured into clearly labeled sections.
- **SQL alignment**: SQL files in `concept/model/sql/` are translations of the conceptual model; when the UML changes, update the corresponding SQL artifact (and keep the script re-runnable).

