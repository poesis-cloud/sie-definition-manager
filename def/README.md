# DM Design-Time Artifacts

Index of the design-time models, schemas, and decision records for the
Definition Manager. These artifacts are authoritative: when prose (README,
docs site) and a model here disagree, the model wins.

## Models

| Artifact                                                       | Contents                                                                                                        |
| --------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| [gsm.puml](gsm.puml)                                             | **The GSM specification model** — the 8 primitives, governance grammar (DNA), statement closure, class relations |
| [gsm-ascription-lifecycle.puml](gsm-ascription-lifecycle.puml)   | Ascription lifecycle state machine — statuses, transitions, referee preconditions, cascade rules                  |

## Schemas

| Artifact                            | Contents                                                                                          |
| ------------------------------------ | --------------------------------------------------------------------------------------------------- |
| [statement/](statement/README.md)    | The 8 GSM base Archetype schemas (JSON Schema 2020-12) + `$gsm:*` vocabulary and naming conventions |

## Rule API

| Artifact                                             | Contents                                                          |
| ----------------------------------------------------- | ------------------------------------------------------------------ |
| [sie-rule-api-starlark.py](sie-rule-api-starlark.py)   | Starlark Rule API surface consumed by Mechanism rule validation   |

## Architecture decision records

| ADR                                                                                    | Decision                                                          |
| --------------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| [authorship-not-a-gsm-concern.md](adr/authorship-not-a-gsm-concern.md)                   | Authorship stays out of GSM primitives                            |
| [uniform-polymorphic-ascription-api.md](adr/uniform-polymorphic-ascription-api.md)       | One polymorphic Ascription API across the 8 subject types         |

Product-level ADRs (observability collector topology, log sink switch) live in
the Poesis workspace at `portfolio/sie-definition/architecture/`, outside this
repository.

## Telemetry coverage catalogs

| Artifact                                                     | Contents                                          |
| ------------------------------------------------------------- | --------------------------------------------------- |
| [telemetry-coverage-spans.md](telemetry-coverage-spans.md)     | Per-class span coverage catalog (manual enrichment) |
| [telemetry-coverage-logs.md](telemetry-coverage-logs.md)       | Per-class log coverage catalog (manual enrichment)  |

## Archive

[archive/](archive/) holds superseded content for historical reference only
(the pre-simplification README, earlier GSM model revisions, analysis notes).
**Read-only** — never edit, update, or propagate changes into it.
