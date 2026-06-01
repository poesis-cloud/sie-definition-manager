{{- define "sie-definition-manager.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "sie-definition-manager.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- include "sie-definition-manager.name" . -}}
{{- end -}}
{{- end -}}

{{/*
OTel resource attributes string, built from .Values.service +
.Chart.AppVersion. service.version is sourced from .Chart.AppVersion as
the single source of truth (Governor-approved option (a), S-001 cycle 2,
2026-05-25): the chart's appVersion is the canonical app version, and
OTEL_RESOURCE_ATTRIBUTES tracks it automatically on every chart bump.
Whitelists the OTel identity keys (name, namespace) from .Values.service
so that Kubernetes Service spec keys (type, port) in the same map do
NOT leak into OTEL_RESOURCE_ATTRIBUTES. Keys are emitted as OTel resource
semconv names: service.<key>=<value>, joined by commas.

S-001 cycle 2: consumed by templates/deployment.yaml only.
*/}}
{{- define "sie-definition-manager.otelResourceAttributes" -}}
{{- $pairs := list -}}
{{- $allowed := list "name" "namespace" -}}
{{- range $key, $val := .Values.service -}}
{{- if has $key $allowed -}}
{{- $pairs = append $pairs (printf "service.%s=%s" $key $val) -}}
{{- end -}}
{{- end -}}
{{- $pairs = append $pairs (printf "service.version=%s" .Chart.AppVersion) -}}
{{- join "," $pairs -}}
{{- end -}}

{{/*
Validate observability.logs.sink ∈ {otlp, stdout, both}. Fails helm template
rendering with a clear error if violated. The enum is the AC-1 contract for
S-006 (ADR-003 D-2 dual-sink switch). Default `both` matches ADR-003 D-2
default until S-014 (collector-outage handling) lands and flips it to `otlp`.

Invoked from templates/deployment.yaml near the top of the Pod template so
that any bad value short-circuits chart rendering before env wiring runs.
*/}}
{{- define "sie-defman.validateLogsSink" -}}
{{- $sink := .Values.observability.logs.sink | default "both" -}}
{{- if not (has $sink (list "otlp" "stdout" "both")) -}}
{{- fail (printf "observability.logs.sink must be one of: otlp, stdout, both (got %q)" $sink) -}}
{{- end -}}
{{- end -}}
