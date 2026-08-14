{{/*
Return the chart name (overridable).
*/}}
{{- define "hitorro-mesh.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Return the fully-qualified app name (overridable).
*/}}
{{- define "hitorro-mesh.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name (include "hitorro-mesh.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{/*
Common labels applied to every mesh object.
*/}}
{{- define "hitorro-mesh.labels" -}}
app: hitorro-mesh
app.kubernetes.io/name: {{ include "hitorro-mesh.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
