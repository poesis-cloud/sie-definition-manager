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
