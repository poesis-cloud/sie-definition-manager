# SIE Definition Manager deployables

This folder contains ops/runtime assets for the DM service.

- `ops/helm/`: Helm chart for Kubernetes deployments (dev/stage/prod depending cluster/context)

## Helm

Chart path:

- `ops/helm`

Install with defaults:

```bash
helm upgrade --install sie-definition-manager \
  sie/sie-definition-manager/ops/helm \
  -n sie --create-namespace
```

Environment values:

- `environments/dev/values.yaml`
- `environments/preprod/values.yaml`
- `environments/prod/values.yaml`

Each environment file is self-contained and carries the chart defaults for that
target environment.

Recommended deploy command:

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

Schema validation:

- The chart defines `values.schema.json`; validate the target environment file

  directly with `helm lint`.

- Validate before deploy:

```bash
helm lint sie/sie-definition-manager/ops/helm \
  -f sie/sie-definition-manager/ops/helm/environments/preprod/values.yaml \
  --set-string secrets.DB_PASSWORD=dummy
```

## Observability modes (`observability.mode`)

Per [ADR-001 D-1](../../../products/sie-definition/architecture/adr-001-observability-shared-collector.md),
the chart exposes a single Helm value — `observability.mode` — that selects how
OpenTelemetry data leaves the pod. The Helm template translates the mode into
SDK env vars (`OTEL_TRACES_EXPORTER`, `OTEL_LOGS_EXPORTER`,
`OTEL_EXPORTER_OTLP_ENDPOINT`); no Java code change is required to switch.

| `observability.mode` | Exporters | Endpoint | Use when |
|---|---|---|---|
| `shared-collector` *(default)* | `otlp` | `http://sie-otel-collector.sie.svc.cluster.local:4317` | Standard managed deployment with the shared SIE collector in the cluster. |
| `direct` | `otlp` | `observability.otlp.endpoint` *(required)* | Customer-owned OTLP backend (on-prem APM, vendor SaaS reachable directly). Helm install **fails fast** if `observability.otlp.endpoint` is unset. |
| `stdout` | `logging` | *(unset)* | Air-gapped / forensic mode — OTLP JSON is written to the app's own pod stdout; no collector traffic. Inspect with `kubectl logs deployment/sie-definition-manager -n sie`. |

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

## Dev dependencies for `run-api`

Use `make dev-up` from `sie/sie-definition-manager` to deploy only DM
dependencies and expose them on localhost (as expected by `.env` and `.env.dev`).

What it deploys (using each dependency's own chart):

- Event bus: `sie/sie-event-bus/ops/helm`
- Definition DB: `sie/sie-definition-database`
- Schema Registry: `sie/sie-schema-registry/ops/helm`
- DM itself is run locally via `make run-api`

Start dependencies:

```bash
cd sie/sie-definition-manager && make dev-check
cd sie/sie-definition-manager && make dev-up
```

Local DB credentials for dependency bootstrap:

- Put `DB_USER`, `DB_PASSWORD`, and `DEF_DB_ADMIN_PASSWORD` in

  `sie-definition-manager/.env.dev`.

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
