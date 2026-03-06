# SIE Definition Manager deployables

This folder contains deploy/runtime assets for the DM service.

- `deploy/helm/`: Helm chart for Kubernetes deployments (dev/stage/prod depending cluster/context)

## Helm

Chart path:

- `deploy/helm/sie-definition-manager`

Install with defaults:

```bash
helm upgrade --install sie-definition-manager \
  sie/sie-definition-manager/deploy/helm/sie-definition-manager \
  -n sie --create-namespace
```

Profile layering:

- Base defaults: `values.yaml`
- Platform overlays: `values-aks.yaml`, `values-onprem.yaml`
- Environment overlays: `environments/local/values.yaml`,
  `environments/preprod/values.yaml`, `environments/prod/values.yaml`

Recommended merge order (left to right):

```bash
helm upgrade --install sie-definition-manager \
  sie/sie-definition-manager/deploy/helm/sie-definition-manager \
  -n sie --create-namespace \
  -f sie/sie-definition-manager/deploy/helm/sie-definition-manager/values.yaml \
  -f sie/sie-definition-manager/deploy/helm/sie-definition-manager/values-aks.yaml \
  -f sie/sie-definition-manager/deploy/helm/sie-definition-manager/environments/preprod/values.yaml \
  --set-string secrets.DB_PASSWORD="$DM_DB_PASSWORD"
```

Secrets policy:

- Never commit production secrets in values files.
- Inject secrets at deploy time (`--set-string`) or from a cluster secret
  manager.
- The chart requires `secrets.DB_PASSWORD` explicitly.

Schema validation:

- The chart defines `values.schema.json` to validate keys/types in base,
  platform, and environment overlays.
- Validate before deploy:

```bash
helm lint sie/sie-definition-manager/deploy/helm/sie-definition-manager \
  -f sie/sie-definition-manager/deploy/helm/sie-definition-manager/values-aks.yaml \
  -f sie/sie-definition-manager/deploy/helm/sie-definition-manager/environments/preprod/values.yaml \
  --set-string secrets.DB_PASSWORD=dummy
```

## Local dependencies for `run-api`

Use `make local-up` from `sie/sie-definition-manager` to deploy only DM
dependencies and expose them on localhost (as expected by `.env.example`).

What it deploys (using each dependency's own chart):

- Event bus: `sie/sie-event-bus/deploy/helm/sie-event-bus-kafka`
- Definition DB: `sie/sie-definition-database`
- Schema Registry: `sie/sie-schema-registry/deploy/helm/sie-schema-registry`
- DM itself is run locally via `make run-api`

Start dependencies:

```bash
cd sie/sie-definition-manager && make local-check
cd sie/sie-definition-manager && make local-up
```

Run DM locally:

```bash
cd sie/sie-definition-manager && make run-api
```

Stop dependencies and local port-forwards:

```bash
cd sie/sie-definition-manager && make local-down
```
