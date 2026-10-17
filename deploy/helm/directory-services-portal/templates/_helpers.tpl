{{/* Names */}}
{{- define "dsp.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "dsp.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "dsp.apiName" -}}{{ include "dsp.fullname" . | trunc 59 | trimSuffix "-" }}-api{{- end -}}
{{- define "dsp.webName" -}}{{ include "dsp.fullname" . | trunc 59 | trimSuffix "-" }}-web{{- end -}}

{{- define "dsp.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "dsp.labels" -}}
helm.sh/chart: {{ include "dsp.chart" . }}
app.kubernetes.io/name: {{ include "dsp.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: directory-services-portal
{{- end -}}

{{- define "dsp.selectorLabels" -}}
app.kubernetes.io/name: {{ include "dsp.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "dsp.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "dsp.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/*
Image reference. global.imageRegistry replaces the registry part of the repository (the first
path element when it looks like a host: contains "." or ":", or is "localhost").
Call with (dict "image" .Values.api.image "root" $).
*/}}
{{- define "dsp.image" -}}
{{- $repo := .image.repository -}}
{{- $registry := "" -}}
{{- with .root.Values.global -}}{{- $registry = .imageRegistry | default "" -}}{{- end -}}
{{- if $registry -}}
{{- $parts := splitList "/" $repo -}}
{{- $first := first $parts -}}
{{- if and (gt (len $parts) 1) (or (contains "." $first) (contains ":" $first) (eq $first "localhost")) -}}
{{- $repo = join "/" (rest $parts) -}}
{{- end -}}
{{- $repo = printf "%s/%s" (trimSuffix "/" $registry) $repo -}}
{{- end -}}
{{- printf "%s:%s" $repo (toString (.image.tag | default .root.Chart.AppVersion)) -}}
{{- end -}}

{{/* Pod security context; OpenShift lets the SCC assign the IDs. */}}
{{- define "dsp.podSecurityContext" -}}
{{- $ctx := deepCopy .ctx -}}
{{- if .root.Values.openshift.enabled -}}
{{- $ctx = omit $ctx "runAsUser" "runAsGroup" "fsGroup" -}}
{{- end -}}
{{- toYaml $ctx -}}
{{- end -}}

{{- define "dsp.containerSecurityContext" -}}
{{- $ctx := deepCopy .ctx -}}
{{- if .root.Values.openshift.enabled -}}
{{- $ctx = omit $ctx "runAsUser" "runAsGroup" -}}
{{- end -}}
{{- toYaml $ctx -}}
{{- end -}}

{{/*
The API's health probe target for a group (liveness or readiness): the management port with
metrics.enabled, else the main port's /livez and /readyz. Call with (dict "group" "readiness" "root" $).
*/}}
{{- define "dsp.apiProbe" -}}
{{- if .root.Values.metrics.enabled -}}
path: /actuator/health/{{ .group }}
port: metrics
{{- else -}}
path: {{ ternary "/livez" "/readyz" (eq .group "liveness") }}
port: http
{{- end -}}
{{- end -}}

{{/* "a;b" from a list or a string. */}}
{{- define "dsp.joinSemi" -}}
{{- if kindIs "slice" . -}}{{- join ";" . -}}{{- else -}}{{- . | default "" -}}{{- end -}}
{{- end -}}

{{/* ENS_SESSION_IDLE_TIMEOUT: the deprecated server.sessionTtlMinutes (minutes) when set, else server.session.idleTimeout. */}}
{{- define "dsp.idleTimeout" -}}
{{- if .Values.server.sessionTtlMinutes -}}
{{- printf "%sm" (include "dsp.str" .Values.server.sessionTtlMinutes) -}}
{{- else -}}
{{- include "dsp.str" .Values.server.session.idleTimeout -}}
{{- end -}}
{{- end -}}

{{/* Whether browsers reach the portal over HTTPS (ENS_COOKIE_SECURE=auto). */}}
{{- define "dsp.cookieSecure" -}}
{{- $v := .Values.server.cookieSecure -}}
{{- if or (eq (toString $v) "auto") (eq (toString $v) "") -}}
{{- if or .Values.tls.enabled (and .Values.ingress.enabled .Values.ingress.tls) .Values.route.enabled -}}true{{- else -}}false{{- end -}}
{{- else -}}
{{- toString $v -}}
{{- end -}}
{{- end -}}

{{/*
A secret value: {value, existingSecret, existingSecretKey}. "dsp.secretSet" says whether one is
configured; "dsp.secretRef" renders the secretKeyRef (the chart's Secret under chartKey for an
inline value).
Call with (dict "v" .Values.ldap.bindPassword "chartKey" "ldap-bind-password" "root" $).
*/}}
{{- define "dsp.secretSet" -}}
{{- if or .v.value .v.existingSecret -}}true{{- end -}}
{{- end -}}

{{- define "dsp.secretRef" -}}
secretKeyRef:
{{- if .v.existingSecret }}
  name: {{ .v.existingSecret }}
  key: {{ required (printf "existingSecretKey is required with existingSecret %s" .v.existingSecret) .v.existingSecretKey }}
{{- else }}
  name: {{ include "dsp.fullname" .root }}
  key: {{ .chartKey }}
{{- end }}
{{- end -}}

{{/* Whether the chart creates its own Secret. */}}
{{- define "dsp.hasChartSecret" -}}
{{- $v := .Values -}}
{{- if or (and $v.ldap.bindPassword.value (not $v.ldap.bindPassword.existingSecret)) (and $v.audit.splunk.token.value (not $v.audit.splunk.token.existingSecret)) (and $v.ldap.truststore.password.value (not $v.ldap.truststore.password.existingSecret)) -}}true{{- end -}}
{{- end -}}

{{- define "dsp.licenseSecretName" -}}
{{- if .Values.license.existingSecret -}}{{ .Values.license.existingSecret }}{{- else -}}{{ include "dsp.fullname" . }}-license{{- end -}}
{{- end -}}

{{- define "dsp.tlsSecretName" -}}
{{- if .Values.tls.existingSecret -}}{{ .Values.tls.existingSecret }}{{- else -}}{{ include "dsp.fullname" . }}-tls{{- end -}}
{{- end -}}

{{/* The CA file the API checks client certificates against: "" when none. */}}
{{- define "dsp.x509CaConfigured" -}}
{{- if or .Values.x509.trustedCa.value .Values.x509.trustedCa.existingSecret .Values.tls.enabled -}}true{{- end -}}
{{- end -}}

{{- define "dsp.x509Header" -}}
{{- .Values.x509.header | default "X-SSL-Client-Cert" -}}
{{- end -}}

{{/* Edge termination: the ingress controller, not the web pod, verifies client certificates. */}}
{{- define "dsp.x509Edge" -}}
{{- if and .Values.x509.enabled (not .Values.tls.enabled) -}}true{{- end -}}
{{- end -}}

{{/*
The X.509 proxy secret, which the web pods send to the API with every request: the Secret and
key holding it. Mounted into both pods at /etc/dsp/x509-proxy/proxy-secret.
*/}}
{{- define "dsp.x509ProxySecretName" -}}
{{- .Values.x509.proxySecret.existingSecret | default (printf "%s-x509-proxy" (include "dsp.fullname" .)) -}}
{{- end -}}

{{- define "dsp.x509ProxySecretKey" -}}
{{- if .Values.x509.proxySecret.existingSecret -}}
{{- required "x509.proxySecret.existingSecretKey is required with x509.proxySecret.existingSecret" .Values.x509.proxySecret.existingSecretKey -}}
{{- else -}}proxy-secret{{- end -}}
{{- end -}}

{{/*
The generated proxy secret (base64): the value already in the cluster, so that upgrades keep it,
else a new random one. Without access to the cluster (helm template) it is new on every render.
*/}}
{{- define "dsp.x509ProxySecretValue" -}}
{{- $existing := lookup "v1" "Secret" .Release.Namespace (printf "%s-x509-proxy" (include "dsp.fullname" .)) -}}
{{- $data := dict -}}
{{- if $existing -}}{{- $data = $existing.data | default dict -}}{{- end -}}
{{- if index $data "proxy-secret" -}}
{{- index $data "proxy-secret" -}}
{{- else -}}
{{- randAlphaNum 64 | b64enc -}}
{{- end -}}
{{- end -}}

{{/* Validation of value combinations that would otherwise fail at runtime. */}}
{{- define "dsp.validate" -}}
{{- if gt (int .Values.api.replicaCount) 1 -}}
{{- fail "api.replicaCount must be 0 or 1: the API is a single writer (audit store, configuration, license state) and keeps sessions in memory" -}}
{{- end -}}
{{- if and .Values.x509.enabled (not .Values.tls.enabled) (eq (lower (include "dsp.x509Header" .)) "x-ssl-client-cert") -}}
{{- fail "x509.enabled without tls.enabled (edge termination) needs x509.header set to the header your ingress controller forwards the certificate in (ingress-nginx: ssl-client-cert); the web tier blanks X-SSL-Client-Cert on plain HTTP" -}}
{{- end -}}
{{- if and .Values.x509.enabled (not (include "dsp.x509CaConfigured" .)) -}}
{{- fail "x509.enabled needs x509.trustedCa (the CA certificates that issue user certificates; with tls.enabled, tls's ca.pem is used): the API validates every forwarded certificate itself and refuses to start without them" -}}
{{- end -}}
{{- if and (include "dsp.x509Edge" .) (not .Values.x509.allowUnrestrictedWebIngress) (not (and .Values.networkPolicy.enabled .Values.networkPolicy.webIngressFrom)) -}}
{{- fail "x509.enabled without tls.enabled (edge termination) trusts the certificate header the ingress controller sets, so only the ingress controller may reach the web pods: set networkPolicy.enabled=true and networkPolicy.webIngressFrom to the ingress controller's pods (for example its namespace). If other controls already ensure this, set x509.allowUnrestrictedWebIngress=true" -}}
{{- end -}}
{{- if and .Values.tls.enabled (not .Values.tls.existingSecret) (not (and .Values.tls.certificate .Values.tls.key .Values.tls.clientCa)) -}}
{{- fail "tls.enabled needs tls.existingSecret (keys server.pem, server.key, ca.pem) or tls.certificate, tls.key and tls.clientCa" -}}
{{- end -}}
{{- if and .Values.route.enabled (has .Values.route.tls.termination (list "passthrough" "reencrypt")) (not .Values.tls.enabled) -}}
{{- fail "route.tls.termination passthrough/reencrypt needs tls.enabled (the web pod serves HTTPS on 8443)" -}}
{{- end -}}
{{- if and .Values.route.enabled (eq .Values.route.tls.termination "edge") .Values.tls.enabled -}}
{{- fail "route.tls.termination edge with tls.enabled would loop (the web pod redirects HTTP to HTTPS): use passthrough or reencrypt" -}}
{{- end -}}
{{- if not (has .Values.directory.mode (list "ldap" "memory")) -}}
{{- fail "directory.mode must be ldap or memory" -}}
{{- end -}}
{{- end -}}

{{/* A value as an env string: whole numbers without exponent notation (values parse as float64). */}}
{{- define "dsp.str" -}}
{{- if kindIs "float64" . -}}
{{- if eq (float64 (int64 .)) . -}}{{- int64 . -}}{{- else -}}{{- . -}}{{- end -}}
{{- else if kindIs "invalid" . -}}
{{- else -}}
{{- toString . -}}
{{- end -}}
{{- end -}}

{{/* JAVA_OPTS: api.javaOpts plus the LDAP trust store (its password through $(DSP_TRUSTSTORE_PASSWORD)). */}}
{{- define "dsp.javaOpts" -}}
{{- $opts := list -}}
{{- with .Values.api.javaOpts -}}{{- $opts = append $opts . -}}{{- end -}}
{{- $ts := .Values.ldap.truststore -}}
{{- if $ts.existingSecret -}}
{{- $opts = append $opts (printf "-Djavax.net.ssl.trustStore=/etc/dsp/truststore/%s -Djavax.net.ssl.trustStoreType=%s" $ts.key $ts.type) -}}
{{- if include "dsp.secretSet" (dict "v" $ts.password) -}}
{{- $opts = append $opts "-Djavax.net.ssl.trustStorePassword=$(DSP_TRUSTSTORE_PASSWORD)" -}}
{{- end -}}
{{- end -}}
{{- join " " $opts -}}
{{- end -}}
