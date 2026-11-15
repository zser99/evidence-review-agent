{{- define "ea.name" -}}{{ .Release.Name }}{{- end -}}

{{- define "ea.labels" -}}
app.kubernetes.io/part-of: evidence-agent
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "ea.selector" -}}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/name: {{ .name }}
{{- end -}}

{{- define "ea.image" -}}
{{ .root.Values.image.registry }}/{{ .image }}:{{ .root.Values.image.tag }}
{{- end -}}

{{- define "ea.secretName" -}}
{{- if .Values.secrets.create -}}{{ .Release.Name }}-secrets{{- else -}}{{ required "secrets.existingSecret 이 필요합니다" .Values.secrets.existingSecret }}{{- end -}}
{{- end -}}

{{- define "ea.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}{{ default (printf "%s-backend" .Release.Name) .Values.serviceAccount.name }}{{- else -}}{{ default "default" .Values.serviceAccount.name }}{{- end -}}
{{- end -}}

{{- define "ea.pgHost" -}}
{{- if .Values.postgres.internal.enabled -}}{{ .Release.Name }}-postgres{{- else -}}{{ required "postgres.host(RDS 엔드포인트)가 필요합니다" .Values.postgres.host }}{{- end -}}
{{- end -}}

{{/* 모든 파드에 공통으로 거는 보안 설정: 비루트, 권한 상승 금지, 읽기 전용 루트 파일시스템 */}}
{{- define "ea.podSecurity" -}}
runAsNonRoot: true
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{- define "ea.containerSecurity" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: ["ALL"]
{{- end -}}

{{- define "ea.pullSecrets" -}}
{{- with .Values.image.pullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end -}}
