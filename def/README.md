# DM Design-Time Artifacts

Index of the Definition Manager's own design-time artifacts (decision records
and telemetry catalogs).

> **The normative GSM artifacts moved to the `gsm-specifications` sibling
> repo**: the model (`model/gsm.puml`, `model/gsm-ascription-lifecycle.puml`),
> the Starlark Rule API (`model/rule-api-starlark.py`), and the 8 base
> Archetype schemas (`schemas/`). DM consumes a pinned snapshot of the schemas
> at [../src/main/resources/gsm/schemas/](../src/main/resources/gsm/README.md) — refresh with
> `make sync-gsm-schemas`; drift is build-gated by `GsmSchemaVendorSyncTest`.

## Architecture decision records

| ADR                                                                                | Decision                                                  |
| ---------------------------------------------------------------------------------- | --------------------------------------------------------- |
| [authorship-not-a-gsm-concern.md](adr/authorship-not-a-gsm-concern.md)             | Authorship stays out of GSM primitives                    |
| [uniform-polymorphic-ascription-api.md](adr/uniform-polymorphic-ascription-api.md) | One polymorphic Ascription API across the 8 subject types |

Product-level ADRs (observability collector topology, log sink switch) live in
the Poesis workspace at `portfolio/sie-definition/architecture/`, outside this
repository.

## Telemetry coverage catalogs

| Artifact                                                   | Contents                                            |
| ---------------------------------------------------------- | --------------------------------------------------- |
| [telemetry-coverage-spans.md](telemetry-coverage-spans.md) | Per-class span coverage catalog (manual enrichment) |
| [telemetry-coverage-logs.md](telemetry-coverage-logs.md)   | Per-class log coverage catalog (manual enrichment)  |

## Archive

[archive/](archive/) holds superseded content for historical reference only
(the pre-simplification README, earlier GSM model revisions, analysis notes).
**Read-only** — never edit, update, or propagate changes into it.
