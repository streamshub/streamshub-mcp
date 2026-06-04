{{/*
Expand the name of the chart.
*/}}
{{- define "strimzi-mcp.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "strimzi-mcp.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "strimzi-mcp.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "strimzi-mcp.labels" -}}
helm.sh/chart: {{ include "strimzi-mcp.chart" . }}
{{ include "strimzi-mcp.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: streamshub
{{- end }}

{{/*
Selector labels
*/}}
{{- define "strimzi-mcp.selectorLabels" -}}
app.kubernetes.io/name: {{ include "strimzi-mcp.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "strimzi-mcp.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "strimzi-mcp.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Map mcp.log.provider value to the Quarkus config property value
*/}}
{{- define "strimzi-mcp.logProvider" -}}
{{- if eq .Values.mcp.log.provider "loki" }}streamshub-loki
{{- else }}streamshub-kubernetes
{{- end }}
{{- end }}

{{/*
Map mcp.metrics.provider value to the Quarkus config property value
*/}}
{{- define "strimzi-mcp.metricsProvider" -}}
{{- if eq .Values.mcp.metrics.provider "prometheus" }}streamshub-prometheus
{{- else }}streamshub-pod-scraping
{{- end }}
{{- end }}
