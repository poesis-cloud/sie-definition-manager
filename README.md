# SIE Definition Manager

[![CI](https://github.com/poesis-cloud/sie-definition-manager/actions/workflows/ci.yaml/badge.svg)](https://github.com/poesis-cloud/sie-definition-manager/actions/workflows/ci.yaml)
[![Helm template matrix](https://github.com/poesis-cloud/sie-definition-manager/actions/workflows/helm-template-matrix.yaml/badge.svg)](https://github.com/poesis-cloud/sie-definition-manager/actions/workflows/helm-template-matrix.yaml)
[![Release](https://img.shields.io/github/v/release/poesis-cloud/sie-definition-manager)](https://github.com/poesis-cloud/sie-definition-manager/releases/latest)
[![Coverage gate](https://img.shields.io/badge/JaCoCo-%E2%89%A595%25%20instruction%20coverage%20enforced-brightgreen)](pom.xml)
[![Java](https://img.shields.io/badge/Java-21-orange)](pom.xml)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)](pom.xml)
[![License: BUSL-1.1](https://img.shields.io/badge/license-BUSL--1.1-blue)](LICENSE)

SIE (Systemic Intelligence Engine) is an **AI Context Platform**: it turns "context" into a governed, typed, provenance-backed asset (observations + definitions + evaluations) that can be assembled for AI reasoning, deterministic checks, and remediation decisions.

The **Definition Manager (DM)** is SIE's core service. It hosts the **GSM (Generative System Model)** — SIE's generative/definitional system model — manages the Ascription lifecycle across the 8 GSM subject types, and enforces the governance grammar (Directives, Norms, Ascriptions).

## Table of contents

- [Features](#features)
- [Repository map](#repository-map)
- [Quickstart](#quickstart)
  - [Build and test](#build-and-test)
  - [Local development](#local-development)
- [Configuration](#configuration)
- [API semantics](#api-semantics)
- [Deployment and observability](#deployment-and-observability)
- [Documentation](#documentation)
- [License](#license)

## Features

- REST API over the 8 GSM subject types (Structure, Mechanism, Effector, Receptor, Interaction, Archetype, Directive, Norm) with `application/hal+json` and `application/json`
- Full **Ascription lifecycle**: state machine, referee preconditions, governance-convergence cascades
- **Archetype governance**: JSON Schema (2020-12) validation against the GSM meta-schema, `$ref`-chain convergence, `$gsm:*` vocabulary enforcement (`sealed`, `identityBound`, `queryable`, `unique`, `dataProtection`)
- **Data protection**: phase-first at-rest / in-transit treatments (encryption, hash, mask, suppression) with JSONB index auto-provisioning
- Starlark **Mechanism rule** validation and port derivation; CEL **Norm** applicability/assertion profile validation
- Dynamic OpenAPI endpoint (`/api/v1/openapi`) and RFC 7807 error model (`application/problem+json`)
- PostgreSQL + Flyway; Kafka + Schema Registry integration; OAuth2/OIDC resource server baseline
- OpenTelemetry-native observability: AOP instrumentation floor + manual enrichment, OTLP export ([span](def/telemetry-coverage-spans.md) and [log](def/telemetry-coverage-logs.md) coverage catalogs)
- Stack: `Java 21` + `Spring Boot 3.5` + `Maven`; Helm chart for AKS and on-prem profiles; GitHub Actions CI/CD

## Repository map

| Path                                                                                  | Contents                                                                                                                                                                                      |
| ------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `gsm-specifications` (sibling repo)                                                   | **The normative GSM artifacts**: `model/gsm.puml`, `model/gsm-ascription-lifecycle.puml`, `model/rule-api-starlark.py`, `schemas/` (8 base Archetype schemas + `$gsm:*` vocabulary reference) |
| [src/main/resources/gsm/schemas/](src/main/resources/gsm/README.md)                   | Pinned snapshot of the base schemas consumed by the build (`make sync-gsm-schemas`)                                                                                                           |
| [def/](def/README.md)                                                                 | DM design-time artifacts (ADRs, telemetry catalogs)                                                                                                                                           |
| [def/adr/](def/README.md#architecture-decision-records)                               | Local architecture decision records                                                                                                                                                           |
| [src/main/java/.../service/](src/main/java/cloud/poesis/sie/defman/service/README.md) | Service layer reader's guide (dependency graph, subsidiary pattern, reading order)                                                                                                            |
| [ops/](ops/README.md)                                                                 | Helm chart, deployment flows, observability modes                                                                                                                                             |
| [.github/workflows/](.github/workflows)                                               | CI (`ci.yaml`), CD (`cd.yaml`), Helm sink-switch matrix (`helm-template-matrix.yaml`)                                                                                                         |

## Quickstart

### Build and test

```bash
make test      # unit tests (mvn test)
make verify    # full build gate (mvn verify)
```

`mvn verify` enforces the **JaCoCo ≥ 95% instruction coverage** gate (`jacoco-check` in [pom.xml](pom.xml)) — the build fails below the threshold. The same gate runs in CI on every push and pull request.

### Local development

The dev flow runs DM on the host against dependencies deployed in a local Kubernetes cluster:

```bash
make dev-check   # preflight: tooling, cluster, chart, dev values
make dev-up      # deploy dependencies + port-forwards
make run-api     # run DM locally (Spring Boot) against them
make dev-down    # stop the API, port-forwards, uninstall, drop dev PVCs
```

`make dev-up` installs the umbrella Helm chart (`ops/helm`) into the `sie`
namespace. The chart's **vendored subcharts** (see `ops/helm/charts/`) provide
the dependencies, which are then port-forwarded to localhost:

| Dependency      | Subchart alias       | Local port |
| --------------- | -------------------- | ---------- |
| PostgreSQL      | `definitiondatabase` | `5432`     |
| Kafka event bus | `eventbus`           | `9092`     |
| Schema Registry | `schemaregistry`     | `8081`     |

`make run-api` sources `.env` / `.env.dev` and starts Spring Boot against the
port-forwarded endpoints. See [ops/README.md](ops/README.md) for the full
dependency bootstrap details.

## Configuration

Environment files:

- `.env` — checked-in non-secret defaults.
- `.env.dev` — machine-local dev secrets and overrides (never committed).

Required in `.env.dev` for the dev flow: `DB_USER`, `DB_PASSWORD`,
`DEF_DB_ADMIN_PASSWORD`.

To enable social OAuth providers, set `DM_OAUTH2_LOGIN_ENABLED=true` in `.env.dev`
and supply the registration env vars (Spring Boot relaxed binding):

```env
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID=...
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_SECRET=...
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID=...
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET=...
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_MICROSOFT_CLIENT_ID=...
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_MICROSOFT_CLIENT_SECRET=...
```

## API semantics

### Schema registration policy

DM is configured for an explicit **registration pipeline** (no auto-registration at runtime):

- `DM_SCHEMA_AUTO_REGISTER=false` (default)

Schemas should be registered by CI/CD or dedicated governance pipelines before DM runtime operations rely on them.

### Reliability profile

Default profile is:

- `DM_RELIABILITY_MODE=alo-idempotent`

This keeps v1 cost-effective while preserving a path to stricter producer transaction settings when needed.

### POST endpoint idempotency

Neither POST endpoint is idempotent — by design:

- **`POST /ascriptions`**: not idempotent. Submitting the same payload twice
  creates two distinct Ascription rows (each with its own UUIDv7 `id`). This
  is intentional: GSM's governance convergence pattern handles duplicates —
  approving one Ascription auto-terminates siblings (DRAFT → ABANDONED,
  PROPOSED → REJECTED), so duplicate drafts are harmless and self-cleaning.
- **`POST /ascriptions/{id}/transitions`**: not idempotent, but naturally
  guarded by the state machine. The first call succeeds; a duplicate call with
  the same `postStatus` fails because the Ascription's `status` has already
  advanced past the `preStatus` — the transition path is no longer valid
  (409 Conflict).

Clients should treat both endpoints as non-idempotent and avoid blind retries.
If at-least-once delivery introduces duplicate drafts, governance convergence
resolves them without manual intervention.

## Deployment and observability

Deployment (Helm chart, environments, secrets policy) and observability
(`observability.mode` OTLP routing, `observability.logs.sink` switch) are
documented in [ops/README.md](ops/README.md).

Environment values live under `ops/helm/environments/{dev,preprod,prod}/values.yaml`;
each file is self-contained for its target environment. Secrets are never stored
in values files — inject `secrets.DB_PASSWORD` at deploy time via the CI/CD
secret store.

Telemetry coverage catalogs (per-class span/log inventories):
[def/telemetry-coverage-spans.md](def/telemetry-coverage-spans.md),
[def/telemetry-coverage-logs.md](def/telemetry-coverage-logs.md).

## Documentation

Conceptual and user-facing documentation lives on the docs site:

- **SIE Definition** — <https://docs.poesis.cloud/sie-definition/>
- **GSM, the open standard** — <https://docs.poesis.cloud/gsm/>

Design-time models in this repo are indexed in [def/README.md](def/README.md).
The SIE umbrella overview lives at `../README.md`.

> The full pre-simplification README (GSM theory, the DM-as-compiler concept
> layers, the explicit-fetch design notes, the Directive → Norm governance
> examples, and ADR-001) is preserved at `def/archive/README.md`.

## License

[Business Source License 1.1](LICENSE) — see [LICENSE](LICENSE) for the change
date and change license terms.
