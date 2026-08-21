# SIE Definition Manager deployables

This folder contains ops/runtime assets for the DM service.

- `ops/helm/`: umbrella Helm chart for Kubernetes deployments (dev/preprod/prod depending cluster/context)

## Table of contents

- [Helm](#helm)
- [Observability modes](#observability-modes-observabilitymode)
- [Log sink switch](#log-sink-switch-observabilitylogssink)
- [Dev dependencies for `run-api`](#dev-dependencies-for-run-api)

## Helm

`ops/helm` is an **umbrella chart**: DM's own templates plus vendored
dependency subcharts under `ops/helm/charts/`:

| Subchart                  | Alias                | Provides        |
| ------------------------- | -------------------- | --------------- |
| `sie-definition-database` | `definitiondatabase` | PostgreSQL      |
| `sie-event-bus-kafka`     | `eventbus`           | Kafka (KRaft)   |
| `sie-schema-registry`     | `schemaregistry`     | Schema Registry |

Subchart sources: `sie-definition-database` resolves from its sibling repo
(`file://../../../sie-definition-database/ops/helm`); the event-bus and
schema-registry source repos are retired, so their chart sources are vendored
in-repo under `ops/helm-deps/` and referenced from `Chart.yaml` via `file://`.
`helm dependency update ops/helm` rebuilds `Chart.lock` and `charts/*.tgz`
from these sources.

Environment values (no chart-root `values.yaml` — values live only under
`environments/`):

- `environments/dev/values.yaml`
- `environments/preprod/values.yaml`
- `environments/prod/values.yaml`

Each environment file is self-contained and carries the chart defaults for that
target environment.

Recommended deploy command (the Makefile contract used by CD; runs `deploy-check`
preflight first):

```bash
cd sie/sie-definition-manager && make prod-deploy DEPLOY_ENV=preprod
```

Escape hatch — direct Helm (only when bypassing the Makefile deliberately):

```bash
helm upgrade --install sie-definition-manager \
  sie/sie-definition-manager/ops/helm \
  -n sie --create-namespace \
  -f sie/sie-definition-manager/ops/helm/environments/preprod/values.yaml \
  --set-string secrets.DB_PASSWORD="$DM_DB_PASSWORD"
```

Secrets policy:

- Never commit production secrets in values files.
- Inject secrets at deploy time (`--set-string`) or from a cluster secret
  manager.
- The chart requires `secrets.DB_PASSWORD` explicitly.

Chart validation:

- Lint the chart against the target environment file before deploying:

```bash
helm lint sie/sie-definition-manager/ops/helm \
  -f sie/sie-definition-manager/ops/helm/environments/preprod/values.yaml \
  --set-string secrets.DB_PASSWORD=dummy
```

- `make helm-template-matrix` runs the full render-matrix validation:
  `helm lint` across all three environments plus positive/negative rendering
  of the log-sink switch (see below). CI runs the exact same target on every
  PR (`.github/workflows/helm-template-matrix.yaml`), keeping local and CI
  checks identical.
- `make package-helm` packages the chart into a `.tgz` for distribution.

## Observability modes (`observability.mode`)

Per ADR-001 D-1 (`portfolio/sie-definition/architecture/adr-001-otel-collector-and-conventions.md`
in the Poesis workspace, outside this repository),
the chart exposes a single Helm value — `observability.mode` — that selects how
OpenTelemetry data leaves the pod. The Helm template translates the mode into
SDK env vars (`OTEL_TRACES_EXPORTER`, `OTEL_LOGS_EXPORTER`,
`OTEL_EXPORTER_OTLP_ENDPOINT`); no Java code change is required to switch.

| `observability.mode`           | Exporters | Endpoint                                               | Use when                                                                                                                                                                   |
| ------------------------------ | --------- | ------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `shared-collector` _(default)_ | `otlp`    | `http://sie-otel-collector.sie.svc.cluster.local:4317` | Standard managed deployment with the shared SIE collector in the cluster.                                                                                                  |
| `direct`                       | `otlp`    | `observability.otlp.endpoint` _(required)_             | Customer-owned OTLP backend (on-prem APM, vendor SaaS reachable directly). Helm install **fails fast** if `observability.otlp.endpoint` is unset.                          |
| `stdout`                       | `logging` | _(unset)_                                              | Air-gapped / forensic mode — OTLP JSON is written to the app's own pod stdout; no collector traffic. Inspect with `kubectl logs deployment/sie-definition-manager -n sie`. |

Override at install/upgrade time, e.g.:

```bash
# direct mode to a customer-owned collector
helm upgrade --install sie-definition-manager sie/sie-definition-manager/ops/helm \
  -n sie \
  -f sie/sie-definition-manager/ops/helm/environments/prod/values.yaml \
  --set observability.mode=direct \
  --set observability.otlp.endpoint=https://customer.example.com:4317

# stdout mode (no collector dependency)
helm upgrade --install sie-definition-manager sie/sie-definition-manager/ops/helm \
  -n sie \
  -f sie/sie-definition-manager/ops/helm/environments/dev/values.yaml \
  --set observability.mode=stdout
```

Out of scope (future sprints): multi-exporter fan-out, vendor-specific exporters
(Datadog/Splunk/AppInsights), OTLP `direct`-mode auth headers.

## Log sink switch (`observability.logs.sink`)

Per ADR-003 D-2, `observability.logs.sink` selects where application logs go,
independently of the OTLP routing above:

| `observability.logs.sink` | Log destination | `LOGGING_THRESHOLD_CONSOLE` |
| ------------------------- | --------------- | --------------------------- |
| `otlp`                    | OTLP only       | `OFF`                       |
| `stdout`                  | Console only    | `TRACE`                     |
| `both`                    | OTLP + console  | `TRACE`                     |

Any other value fails the render with an enum-guard error from `_helpers.tpl`.
`make helm-template-matrix` asserts all three cells plus the negative case.

## Dev dependencies for `run-api`

Use `make dev-up` from `sie/sie-definition-manager` to deploy only DM
dependencies and expose them on localhost (as expected by `.env` and `.env.dev`).

What it deploys:

- The umbrella chart (`ops/helm`) into the `sie` namespace with the dev
  values; the vendored subcharts bring up PostgreSQL (`definitiondatabase`),
  Kafka (`eventbus`), and the Schema Registry (`schemaregistry`).
- Port-forwards to localhost: `5432` (PostgreSQL), `9092` (Kafka),
  `8081` (Schema Registry).
- DM itself is run locally via `make run-api` — not in-cluster in the dev flow.

Start dependencies:

```bash
cd sie/sie-definition-manager && make dev-check
cd sie/sie-definition-manager && make dev-up
```

Local DB credentials for dependency bootstrap:

- Put `DB_USER`, `DB_PASSWORD`, and `DEF_DB_ADMIN_PASSWORD` in
  `sie-definition-manager/.env.dev` (copy `.env.dev.template` to get started).
- `make dev-up` injects those values into the Helm install.
- The dev environment overlay enables a local-only post-install/post-upgrade
  Helm hook Job in the DM chart.
- That hook creates or updates the DM database role after the local
  definition-database deployment is ready.
- `make run-api` passes `DB_URL`, `DB_USER`, and `DB_PASSWORD` from the same
  local env file to Spring Boot, so the API connects with those credentials.

Run DM locally:

```bash
cd sie/sie-definition-manager && make run-api
```

Stop dependencies and local port-forwards:

```bash
cd sie/sie-definition-manager && make dev-down
```
