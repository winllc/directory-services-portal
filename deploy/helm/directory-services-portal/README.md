# Directory Services Portal Helm chart

Installs the Directory Services Portal on Kubernetes or OpenShift from the images published
with each release:

| Workload | Image | |
|---|---|---|
| `<release>-directory-services-portal-api` | `ghcr.io/winllc/directory-services-portal-api` | the Spring Boot API on port 3001, one replica, `Recreate`; never exposed outside the cluster |
| `<release>-directory-services-portal-web` | `ghcr.io/winllc/directory-services-portal-web` | nginx serving the web client on 8080 (and HTTPS on 8443 with `tls.enabled`), proxying `/api` to the API |

The Service `<release>-directory-services-portal` (the web tier) is what users, the Ingress and
the Route reach. The API keeps two volumes: the **license volume** (`/var/lib/ens`: the ENS
license and this installation's id) and the **data volume** (`/data`: forms, definitions,
grants, dashboards, analyses and the audit store). Both are PVCs. There is no database.

Full installation guide: <https://winllc.github.io/ens-apps-documentation/directory-services-portal/install/>.

## Install

The chart is published as an OCI artifact with every release (and is also in the release
download as `directory-services-portal-<version>.tgz`):

```bash
helm install dsp oci://ghcr.io/winllc/charts/directory-services-portal --version 1.2.3 \
  --namespace dsp --create-namespace -f my-values.yaml
helm test dsp -n dsp
```

The chart's version is the release version, and by default it runs the images of that same
version.

Requirements: Kubernetes 1.25+, a default StorageClass (or `persistence.*.storageClass`), and an
LDAPv3 directory the cluster can reach.

## Examples

### Minimal

```yaml
# my-values.yaml
ldap:
  url: ldaps://ldap.corp.example:636
  bindDn: cn=portal,ou=system,dc=corp,dc=example
  bindPassword:
    value: change-me            # stored in the chart's Secret
  baseDn: dc=corp,dc=example
  userSearchBase: ou=people,dc=corp,dc=example
  groupSearchBase: ou=groups,dc=corp,dc=example
admin:
  groups: [cn=directory-admins,ou=groups,dc=corp,dc=example]
```

```bash
helm install dsp oci://ghcr.io/winllc/charts/directory-services-portal --version 1.2.3 -n dsp -f my-values.yaml \
  --set-file license.file=./license.lic        # optional; or upload it on the license screen
kubectl port-forward -n dsp svc/dsp-directory-services-portal 8080:8080   # http://localhost:8080
```

### Existing secrets

```bash
kubectl create secret generic dsp-secrets -n dsp \
  --from-literal=ldap-bind-password='...' --from-literal=splunk-hec-token='...'
kubectl create secret generic dsp-license -n dsp --from-file=license.lic=./license.lic
```

```yaml
ldap:
  bindPassword:
    existingSecret: dsp-secrets
    existingSecretKey: ldap-bind-password
license:
  existingSecret: dsp-license        # key license.lic
audit:
  splunk:
    url: https://splunk.corp.example:8088
    token:
      existingSecret: dsp-secrets
      existingSecretKey: splunk-hec-token
```

LDAPS with a private CA: put a PKCS12 trust store (every CA the API must trust) in a Secret and
point `ldap.truststore` at it:

```bash
keytool -importcert -noprompt -alias corp-ca -file corp-ca.pem -keystore truststore.p12 \
  -storetype PKCS12 -storepass 'store-pass'
kubectl create secret generic dsp-truststore -n dsp --from-file=truststore.p12 \
  --from-literal=truststore-password='store-pass'
```

```yaml
ldap:
  truststore:
    existingSecret: dsp-truststore
    password:
      existingSecret: dsp-truststore
      existingSecretKey: truststore-password
```

### Ingress with TLS

```yaml
ingress:
  enabled: true
  className: nginx
  hosts:
    - host: portal.corp.example
      paths: [{path: /, pathType: Prefix}]
  tls:
    - secretName: portal-tls
      hosts: [portal.corp.example]
```

`server.cookieSecure: auto` (the default) turns on secure session cookies (`ENS_COOKIE_SECURE`) when `ingress.tls`,
a Route or `tls.enabled` is set. Schema imports can be up to 5 MB: raise the controller's body
size limit if it is lower (ingress-nginx: `nginx.ingress.kubernetes.io/proxy-body-size: 5m`).

### OpenShift

```yaml
openshift:
  enabled: true          # the restricted SCC assigns runAsUser, runAsGroup and fsGroup
route:
  enabled: true
  host: portal.apps.ocp.corp.example
  tls:
    termination: edge
```

Both images run under an arbitrary UID in group 0.

### X.509 smart-card sign-in

The API reads the certificate from a header set by a trusted proxy. Two setups are supported;
the license must include `smart-card-sign-in`. In both, the API accepts that header only from
the web pods' addresses (`x509.trustedProxies`) **together with the proxy secret** the web pods
send with every request, and checks the certificate chain itself against the CA in
`x509.trustedCa` (or `tls`'s `ca.pem`), which is required. The chart generates the proxy secret
into the Secret `<release>-directory-services-portal-x509-proxy`, keeps it across upgrades and
mounts it into both pods. Rendering the chart without access to the cluster (`helm template`,
Argo CD) would generate a new one each time: there, create the Secret yourself and set
`x509.proxySecret.existingSecret`:

```bash
kubectl create secret generic dsp-x509-proxy -n dsp --from-literal=proxy-secret="$(openssl rand -hex 32)"
```

**TLS passthrough to the pod (recommended).** The web pod terminates TLS on 8443 with the web
image's mTLS nginx config, asks browsers for a certificate (optional, so password sign-in keeps
working), verifies it against `ca.pem` and forwards it to the API in `X-SSL-Client-Cert`. The
API checks the chain again against the same `ca.pem`.

```bash
# server.pem: certificate (and chain) for the portal's host name; server.key: its key;
# ca.pem: the CA(s) that issue user certificates.
kubectl create secret generic dsp-web-tls -n dsp \
  --from-file=server.pem --from-file=server.key --from-file=ca.pem
```

```yaml
tls:
  enabled: true
  existingSecret: dsp-web-tls
x509:
  enabled: true
  userFilter: (&(objectClass=inetOrgPerson)(userPrincipalName={{upn}}))
# Then one of:
service:                 # a LoadBalancer straight to the pod
  type: LoadBalancer
  port: 80               # redirects to HTTPS
  httpsPort: 443
  externalTrafficPolicy: Local
ingress:                 # ingress-nginx started with --enable-ssl-passthrough
  enabled: true
  className: nginx
  annotations:
    nginx.ingress.kubernetes.io/ssl-passthrough: "true"
  hosts:
    - host: portal.corp.example
      paths: [{path: /, pathType: Prefix}]
route:                   # OpenShift
  enabled: true
  host: portal.apps.ocp.corp.example
  tls:
    termination: passthrough
```

Set `tls.httpsPortSuffix` (for example `":8443"`) when browsers reach HTTPS on a port other
than 443; it is used in the HTTP-to-HTTPS redirect.

**Edge termination at the ingress.** The ingress controller verifies the certificate and
forwards it in its own header (`x509.header`); the web tier passes that header on and removes
any other certificate header, and the API accepts it from the web pods with the proxy secret.
With ingress-nginx:

```bash
kubectl create secret generic dsp-client-ca -n dsp --from-file=ca.crt=user-ca.pem
```

```yaml
x509:
  enabled: true
  header: ssl-client-cert
  trustedCa:                       # the API validates the chain too
    existingSecret: dsp-client-ca
    existingSecretKey: ca.crt
ingress:
  enabled: true
  className: nginx
  annotations:
    nginx.ingress.kubernetes.io/auth-tls-secret: dsp/dsp-client-ca
    nginx.ingress.kubernetes.io/auth-tls-verify-client: "optional"
    nginx.ingress.kubernetes.io/auth-tls-pass-certificate-to-upstream: "true"
  hosts:
    - host: portal.corp.example
      paths: [{path: /, pathType: Prefix}]
  tls:
    - secretName: portal-tls
      hosts: [portal.corp.example]
networkPolicy:                     # only the ingress controller may reach the web tier
  enabled: true
  webIngressFrom:
    - namespaceSelector:
        matchLabels:
          kubernetes.io/metadata.name: ingress-nginx
```

Use `optional`, never `optional_no_ca`, and keep `auth-tls-pass-certificate-to-upstream: "true"`:
then the controller always sets `ssl-client-cert` itself, replacing any copy a client sends. In
this setup anyone who can reach the web pods without going through the ingress controller could
send the header with a user's certificate (which is public), so the chart refuses to render it
unless `networkPolicy.enabled` is `true` and `networkPolicy.webIngressFrom` lists the ingress
controller's pods. If other controls already ensure that only the ingress controller reaches the
web pods (a cluster-wide policy, a service mesh), set `x509.allowUnrestrictedWebIngress: true`
instead. An OpenShift Route cannot forward client certificates: use passthrough there.

### Several API replicas (high availability)

More than one API pod needs the shared PostgreSQL database (13 or later). It holds sessions, the
sign-in lockout, configuration, the audit store, background jobs and cluster locks; the API
creates its tables (Flyway). Upgrade in two steps so the first start can import the data volume:

```yaml
# Step 1: keep one replica and the data volume; add the database.
database:
  url: jdbc:postgresql://postgres.db.svc:5432/dsp
  username: dsp
  password:
    existingSecret: dsp-database      # key: database-password (or set existingSecretKey)
```

The first start copies `portal-config.json`, the audit files (the hash chain carries on from the
same head), the audit API tokens and forwarder cursors and the saved analysis results into the
database; the files are left as they were but are not read again. Check the API log for
`Imported ...` lines, then:

```yaml
# Step 2: drop the data volume and scale out.
api:
  replicaCount: 3
persistence:
  data:
    enabled: false
```

The license state (installation id, license, revisions seen) is imported into the database at the
same first start, from the license volume, which must therefore be mounted then (it is, with one
replica). With several replicas a ReadWriteOnce license volume is no longer mounted (the pods get
an emptyDir and a license given with `license.*` reaches them as `ENS_LICENSE`); its PVC carries
`helm.sh/resource-policy: keep`, so Helm never deletes it. A ReadWriteMany license volume stays
mounted. Pods without the license volume get `ENS_LICENSE_EXPECT_INSTALLATION_ID=true`: while
the database has no installation id they refuse to start rather than make one, so going straight
from one replica without the database to several replicas with it (or rebuilding PostgreSQL behind
several replicas) stops with a message instead of breaking the license; start with one replica
(which mounts the volume and imports the id), then scale out.
`ENS_LICENSE_ALLOW_NEW_INSTALLATION_ID=true` (via `api.extraEnv`) accepts a new id only while the
database holds no license state, for an installation that never had a license; remove it after.

With `persistence.license.enabled=false` and the database on, the database is the only copy of
the installation id: a rebuilt database (without its `ens_license_*` tables) gives the
installation a new id, and licenses bound to the old one must be re-issued. Back up the database.

After the move the license volume is a frozen copy of the state at that first start. Scaling back
to one replica mounts it again, but the API reads the database, not the volume; turning the
database off (`database.url` empty) would bring back the volume's old license, so don't, or
upload the current license again if you must.

With more than one replica the chart renders a PodDisruptionBudget (`api.podDisruptionBudget`)
and, with the database and no ReadWriteOnce volume mounted, a `RollingUpdate` strategy
(`maxUnavailable: 0`; without the database it is always `Recreate`, and `RollingUpdate` is
refused), so
upgrades and node drains don't interrupt the portal. It refuses `replicaCount > 1` without
`database.url`, or with a ReadWriteOnce data or audit-file volume. Audit forwarding to Splunk and syslog runs on one elected
replica at a time; retention runs on one replica.

### Single sign-on

```yaml
sso:
  oidc:
    issuerUri: https://login.microsoftonline.com/<tenant-id>/v2.0
    clientId: <application-id>
    clientSecret:
      existingSecret: dsp-oidc          # key: oidc-client-secret
    usernameClaim: preferred_username
    userFilter: (&(objectClass=user)(userPrincipalName={{value}}))
    adminGroups: [<object id of the portal admins group>]
    label: Sign in with Microsoft
server:
  cookieSecure: true
  publicUrl: https://portal.example.com   # redirect URI and SAML entity id; required for SAML
```

Register `https://<portal>/api/auth/sso/callback/oidc` as the redirect URI. `server.publicUrl`
also sets the web tier's `PUBLIC_HOST`, so a client's `Host` header can't change what the API
believes its address is. For SAML set
`sso.saml.metadataUrl` (or `metadataXml`) and register the SP with
`https://<portal>/api/auth/sso/saml/metadata` (entity id and metadata URL) and the assertion
consumer service `https://<portal>/api/auth/sso/saml/acs/saml`. Provider setup for Entra ID, Okta
and ADFS is in the source README, Single sign-on. With `networkPolicy.enabled`, the API gets the
`networkPolicy.egress.sso` rule (443) and, with a database, `networkPolicy.egress.database`
(5432).

### Air-gapped

Mirror the two images (same tags) into your registry, then:

```yaml
global:
  imageRegistry: registry.corp.example:5000   # ghcr.io/winllc/... -> registry.corp.example:5000/winllc/...
imagePullSecrets:
  - name: corp-registry
```

Install from the chart file in the release download (or `helm pull
oci://ghcr.io/winllc/charts/directory-services-portal --version 1.2.3` on a connected host):
`helm install dsp ./directory-services-portal-1.2.3.tgz -n dsp -f my-values.yaml`. The license
init container and the `helm test` pod use the same two images, so nothing else needs mirroring.

### Upgrade

```bash
helm upgrade dsp oci://ghcr.io/winllc/charts/directory-services-portal --version 1.2.4 -n dsp -f my-values.yaml
```

The API is replaced (`Recreate`: the old pod stops before the new one starts, while a
ReadWriteOnce volume is mounted; `RollingUpdate` otherwise, see Several API replicas); the PVCs, and
with them the license, the installation id and the data, are kept. With `x509.enabled`, the first
upgrade to a release with the X.509 proxy secret generates it and restarts both workloads; an
edge-terminated setup (without `tls.enabled`) then also needs `x509.trustedCa`,
`networkPolicy.enabled` and `networkPolicy.webIngressFrom` (or
`x509.allowUnrestrictedWebIngress`), or the upgrade stops with a message saying so. A license given with
`license.*` is copied only into an empty license volume, so a newer license uploaded on the
license screen is never overwritten. Back up the data PVC before upgrading.

From the release that checks revocation by default, `x509.revocation.mode` and `.fail` default to
empty instead of `off` and `closed`: with `x509.enabled` and neither set in your values, client
certificates are checked live at sign-in (OCSP, then the CRL), a revoked one is refused, and one
no responder or CRL answers for signs in with a warning in the API log. With
`networkPolicy.enabled`, the API pods get the `x509.revocation.egress` rule for the fetches. Set
`x509.revocation.fail: closed` to refuse those as DISA STIG expects (with `crlDir` or an
`ocspResponderUrl` relay on a closed network), or `mode: "off"` to keep the check off. A mode you
set yourself keeps `closed` as before. `helm upgrade --reuse-values` keeps the old `off`.

From the release with the license state in the database, the license PVC gets
`helm.sh/resource-policy: keep` (`helm uninstall` no longer deletes it), and with `database.url`
the first start imports the installation id from it: upgrade with one replica first. Several
replicas no longer need a ReadWriteMany license volume.

From the release with single sign-on and the shared database, nothing changes until you set
`sso.*` or `database.*`: one replica, `Recreate`, the same volumes. `api.replicaCount` may now be
more than 1 with the conditions above, and `api.updateStrategy` (empty: automatic) and
`api.podDisruptionBudget` are new.

From the release with the integration API, webhooks and entry import and export, nothing needs
changing: `integrations.*` has safe defaults, no API token or webhook exists until an administrator
creates one, and the web tier's upload limit (`MAX_BODY_SIZE`) follows
`integrations.transfer.importMaxBytes` (5 MiB as before). With `database.url` the upgrade adds the
`dsp_webhook_delivery` table (Flyway V3). With `networkPolicy.enabled`, the API pods get the
`networkPolicy.egress.webhooks` rule (443 anywhere by default; narrow it to your receivers).

From the release with the platform baseline, the API's probes moved from `/api/health` to
Spring Boot's liveness and readiness groups on the metrics port (`metrics.enabled`, default on):
a pod is not Ready while the directory is unreachable or the audit log can't be written. API
log lines are JSON (`logFormat: json`) unless you set `logFormat: text`. The idle timeout is
`server.session.idleTimeout` (`30m`); the earlier default was 480 minutes, and a
`server.sessionTtlMinutes` you set yourself still applies.

## License

Without a license only sign-in and the license screen work. Sign in as an administrator: you
land on *Administration > License* (`/admin/license`), which shows the **installation id** (to
bind a license to this installation on the ENS licensing portal) and takes the upload. For a
trial, download an evaluation license from your account on the ENS licensing portal.

To hand the chart a license instead, use `license.existingSecret` (key `license.lic`) or
`--set-file license.file=./license.lic`. The `license-init` init container copies it into the
license volume **only when no license is installed there yet** (the same rule as the release
bundle's `install.sh`). Replace a license on the license screen.

The installation id is also in the API's startup log:
`kubectl logs -n dsp deploy/dsp-directory-services-portal-api -c api | grep License:`.

**Keep the license PVC** (`<release>-directory-services-portal-license`). It carries
`helm.sh/resource-policy: keep`, so neither `helm upgrade` nor `helm uninstall` deletes it (delete
it yourself with `kubectl delete pvc` when you really mean to). If it is lost, the installation gets
a new id and licenses bound to the old one must be re-bound. For installations you may
reinstall, create the PVCs yourself and set `persistence.license.existingClaim` (and
`persistence.data.existingClaim`).

## Uninstall

```bash
helm uninstall dsp -n dsp      # deletes the data and audit-file PVCs the chart created; the license PVC is kept
```

Existing claims (`existingClaim`) are never touched.

## Values

### Images and pods

| Value | Default | |
|---|---|---|
| `global.imageRegistry` | `""` | replaces the registry part of every image (air-gapped mirrors) |
| `imagePullSecrets` | `[]` | pull secrets for both workloads |
| `nameOverride`, `fullnameOverride` | `""` | resource names |
| `clusterDomain` | `cluster.local` | for the API's fully qualified name (nginx does not use DNS search domains) |
| `revisionHistoryLimit` | `5` | ReplicaSets kept per Deployment |
| `serviceAccount.create` / `.name` / `.annotations` | `true` / `""` / `{}` | service account (shared by both workloads) |
| `serviceAccount.automountServiceAccountToken` | `false` | the portal never calls the Kubernetes API |
| `openshift.enabled` | `false` | drop runAsUser, runAsGroup and fsGroup so the SCC assigns them |
| `api.image.repository` | `ghcr.io/winllc/directory-services-portal-api` | |
| `api.image.tag` | `""` | empty: the chart's appVersion |
| `api.image.pullPolicy` | `IfNotPresent` | |
| `api.replicaCount` | `1` | more than 1 needs `database.url` and no ReadWriteOnce data volume (see Several API replicas) |
| `api.updateStrategy` | `""` | `""`: `RollingUpdate` with `database.url` and no ReadWriteOnce volume, else `Recreate`; `RollingUpdate` needs `database.url` |
| `api.podDisruptionBudget.minAvailable` | `1` | rendered while `replicaCount > 1` |
| `api.javaOpts` | `-XX:MaxRAMPercentage=75.0` | `JAVA_OPTS` |
| `api.javaToolOptions` | `""` | `JAVA_TOOL_OPTIONS` (printed at startup: no passwords) |
| `api.resources` | requests 250m / 768Mi, limit 1536Mi | |
| `api.podSecurityContext` | non-root 185/185, fsGroup 185, RuntimeDefault seccomp | |
| `api.securityContext` | no privilege escalation, drop ALL, read-only root filesystem | `/tmp` is an emptyDir |
| `api.startupProbe` | liveness, 10s + 36 × 5s | generous for the first start |
| `api.livenessProbe`, `api.readinessProbe` | `/actuator/health/liveness` / `/readiness` on the metrics port (`/livez` / `/readyz` on 3001 without `metrics.enabled`) | `enabled`, `periodSeconds`, `timeoutSeconds`, `failureThreshold`. Readiness needs the directory, a writable audit log and room on the data disk |
| `api.service.port` / `.annotations` | `3001` / `{}` | the API's ClusterIP Service |
| `web.image.repository` | `ghcr.io/winllc/directory-services-portal-web` | |
| `web.image.tag` / `.pullPolicy` | `""` / `IfNotPresent` | |
| `web.replicaCount` | `1` | stateless; may be raised |
| `web.resources` | requests 50m / 64Mi, limit 256Mi | |
| `web.podSecurityContext` | non-root 1001/1001, fsGroup 1001, RuntimeDefault seccomp | |
| `web.securityContext` | no privilege escalation, drop ALL, read-only root filesystem | rendered config, `/tmp` and `/var/log/nginx` are emptyDirs |
| `web.livenessProbe`, `web.readinessProbe` | `GET /` on 8080 (HTTPS 8443 with `tls.enabled`) | |
| `api.*` / `web.*`: `podAnnotations`, `podLabels`, `nodeSelector`, `tolerations`, `affinity`, `topologySpreadConstraints`, `priorityClassName` | empty | scheduling and metadata |
| `api.*` / `web.*`: `extraEnv`, `extraEnvFrom`, `extraVolumes`, `extraVolumeMounts` | `[]` | anything else |

### Persistence and license

| Value | Default | |
|---|---|---|
| `persistence.license.enabled` | `true` | PVC at `/var/lib/ens` (`ENS_LICENSE_STATE_DIR=/var/lib/ens/directory-portal`, `ENS_LICENSE_FILE=/var/lib/ens/directory-portal/license.lic`) |
| `persistence.license.size` | `100Mi` | |
| `persistence.license.storageClass` | `""` | `""` default class, `"-"` none |
| `persistence.license.accessModes` | `[ReadWriteOnce]` | |
| `persistence.license.existingClaim` | `""` | use this PVC |
| `persistence.license.annotations` | `{}` | |
| `persistence.data.*` | enabled, `2Gi`, same knobs | `/data` (`DATA_DIR`): configuration, audit store, analysis results (with `database.url`: only imported on the first start) |
| `database.url` | `""` | `jdbc:postgresql://host:5432/db` (`DSP_DATABASE_URL`); empty: no shared database |
| `database.username` | `""` | |
| `database.password.value` / `.existingSecret` / `.existingSecretKey` | `""` / `""` / `database-password` | mounted as `DSP_DATABASE_PASSWORD_FILE` |
| `database.poolSize` / `.configRefresh` | `10` / `2s` | connections per pod; how soon other replicas see a configuration change |
| `license.existingSecret` | `""` | Secret with key `license.lic` |
| `license.file` | `""` | `--set-file license.file=./license.lic` |

### Single sign-on

| Value | Default | |
|---|---|---|
| `server.publicUrl` | `""` | `PORTAL_PUBLIC_URL` and the web tier's `PUBLIC_HOST`; required for SAML unless `sso.saml.entityId` is set |
| `sso.idpAdminSessionLifetime` | `1h` | sessions whose admin rights come only from provider groups end after this |
| `sso.oidc.issuerUri` / `.clientId` | `""` | both set: OpenID Connect on (`SSO_OIDC_*`) |
| `sso.oidc.clientSecret.value` / `.existingSecret` / `.existingSecretKey` | `""` / `""` / `oidc-client-secret` | mounted as a file; empty: public client (PKCE) |
| `sso.oidc.scopes` / `.usernameClaim` / `.groupsClaim` | `openid profile email` / `preferred_username` / `groups` | |
| `sso.oidc.userFilter` | `(&(objectClass=inetOrgPerson)(uid={{value}}))` | maps the claim to one directory entry |
| `sso.oidc.adminGroups` / `.label` | `[]` / `""` | provider groups that grant administrator; button text |
| `sso.saml.metadataUrl` / `.metadataXml` | `""` | the IdP's metadata (one of them turns SAML on) |
| `sso.saml.entityId` | `""` | empty: `https://<portal>/api/auth/sso/saml/metadata` |
| `sso.saml.usernameAttribute` / `.groupsAttribute` | `""` | empty username attribute: the NameID |
| `sso.saml.userFilter` / `.adminGroups` / `.label` / `.clockSkew` | as OIDC / `2m` | |
| `sso.saml.signing.existingSecret` / `.certificateKey` / `.privateKeyKey` | `""` / `tls.crt` / `tls.key` | for IdPs that require signed AuthnRequests |

### Directory

| Value | Env | Default |
|---|---|---|
| `directory.mode` | `DIRECTORY_MODE` | `ldap` (`memory`: built-in demo directory, evaluation only) |
| `ldap.url` | `LDAP_URL` | `ldaps://ldap.example.com:636` |
| `ldap.startTls` | `LDAP_STARTTLS` | `false` |
| `ldap.tlsRejectUnauthorized` | `LDAP_TLS_REJECT_UNAUTHORIZED` | `true` |
| `ldap.timeoutMs` | `LDAP_TIMEOUT_MS` | `10000` |
| `ldap.poolSize` | `LDAP_POOL_SIZE` | `10` |
| `ldap.bindDn` | `LDAP_BIND_DN` | `cn=portal,ou=system,dc=example,dc=com` |
| `ldap.bindPassword.value` / `.existingSecret` / `.existingSecretKey` | `LDAP_BIND_PASSWORD_FILE` (the Secret mounted at `/etc/dsp/ldap/bind-password`) | `""` / `""` / `ldap-bind-password` |
| `ldap.baseDn` | `LDAP_BASE_DN` | `dc=example,dc=com` |
| `ldap.userSearchBase` | `LDAP_USER_SEARCH_BASE` | `ou=people,dc=example,dc=com` |
| `ldap.userFilter` | `LDAP_USER_FILTER` | `(&(objectClass=inetOrgPerson)(uid={{username}}))` |
| `ldap.usernameAttribute` | `LDAP_USERNAME_ATTRIBUTE` | `uid` |
| `ldap.groupSearchBase` | `LDAP_GROUP_SEARCH_BASE` | `ou=groups,dc=example,dc=com` |
| `ldap.groupFilter` | `LDAP_GROUP_FILTER` | `(\|(member={{dn}})(uniqueMember={{dn}}))` |
| `ldap.truststore.existingSecret` / `.key` / `.type` | JVM trust store | `""` / `truststore.p12` / `PKCS12` |
| `ldap.truststore.password.value` / `.existingSecret` / `.existingSecretKey` | trust store password | `""` / `""` / `truststore-password` |
| `admin.users` | `ADMIN_USERS` | `[]` (list, or a `;`-separated string) |
| `admin.groups` | `ADMIN_GROUPS` | `[cn=directory-admins,ou=groups,dc=example,dc=com]` |

### Server, metrics and background tasks

| Value | Env | Default |
|---|---|---|
| `server.session.idleTimeout` | `ENS_SESSION_IDLE_TIMEOUT` | `30m` (was 480 minutes; the deprecated `server.sessionTtlMinutes`, when set, still wins) |
| `server.session.adminIdleTimeout` | `ENS_SESSION_ADMIN_IDLE_TIMEOUT` | `""` (the idle timeout; STIG suggests `15m`) |
| `server.session.absoluteTimeout` | `ENS_SESSION_ABSOLUTE_TIMEOUT` | `12h` |
| `server.session.maxPerUser` | `ENS_SESSION_MAX_PER_USER` | `0` (no limit; STIG suggests `3`) |
| `server.groupRefreshInterval` | `GROUP_REFRESH_INTERVAL` | `5m` |
| `server.login.maxFailures` / `.lockoutWindow` | `LOGIN_MAX_FAILURES` / `LOGIN_LOCKOUT_WINDOW` | `8` / `15m` |
| `server.cookieSecure` | `ENS_COOKIE_SECURE` | `auto` (true with ingress TLS, a Route or `tls.enabled`) |
| `server.cspReportOnly` | `ENS_CSP_REPORT_ONLY` (API and web tier) | `false` |
| `server.seedDemoConfig` | `SEED_DEMO_CONFIG` | `false` |
| `server.loginHint` | `LOGIN_HINT` | `""` |
| `server.consentBanner.enabled` / `.title` / `.text` | `ENS_CONSENT_BANNER_ENABLED` / `_TITLE` / `_TEXT` | `false` / `""` / `""` (the standard DoD notice and consent) |
| `server.banner.text` / `.foreground` / `.background` | `BANNER_TEXT` / `BANNER_FOREGROUND` / `BANNER_BACKGROUND` | `""` / `#ffffff` / `#007a33` |
| `server.maxHttpRequestHeaderSize` | `MAX_HTTP_REQUEST_HEADER_SIZE` | `32KB` |
| `metrics.enabled` / `.port` | `MANAGEMENT_SERVER_PORT` | `true` / `9090`: Prometheus (`/actuator/prometheus`) and the probes on a port of their own, the `metrics` port of the API's Service (never the ingress or route) |
| `metrics.serviceMonitor.enabled` / `.interval` / `.scrapeTimeout` / `.labels` | | `false` / `30s` / `10s` / `{}`: a Prometheus Operator `ServiceMonitor` |
| `metrics.allowFrom` | | `[]`: NetworkPolicy peers admitted to the metrics port (with `networkPolicy.enabled`) |
| `logFormat` | `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` with `json` | `json` (ECS JSON log lines) or `text` |
| `metrics.maxEntries` / `.cacheSeconds` / `.waitMillis` | `METRICS_MAX_ENTRIES` / `METRICS_CACHE_SECONDS` / `METRICS_WAIT_MILLIS` | `100000` / `60` / `2000` |
| `analysis.maxIssues` | `ANALYSIS_MAX_ISSUES` | `50000` |
| `jobs.metricsThreads` / `.analysisThreads` | `JOBS_METRICS_THREADS` / `JOBS_ANALYSIS_THREADS` | `4` / `2` |
| `jobs.maxActivePerUser` / `.retainMinutes` | `JOBS_MAX_ACTIVE_PER_USER` / `JOBS_RETAIN_MINUTES` | `5` / `1440` |

Fixed by the chart: `DATA_DIR=/data`, `PORT=3001`, `SERVER_FORWARD_HEADERS_STRATEGY=framework`, the
`ENS_LICENSE_*` paths, and the web tier's `API_URL`.

### Integrations: API tokens, webhooks, import and export

| Value | Env | Default |
| --- | --- | --- |
| `integrations.api.rateLimitPerMinute` | `API_RATE_LIMIT_PER_MINUTE` | `120` (per token, per replica) |
| `integrations.api.maxTokenDays` | `API_TOKEN_MAX_DAYS` | `365` |
| `integrations.webhooks.enabled` | `WEBHOOKS_ENABLED` | `true` |
| `integrations.webhooks.allowedHosts` | `WEBHOOK_ALLOWED_HOSTS` | `[]` (public addresses only; metadata addresses never) |
| `integrations.webhooks.maxAttempts` | `WEBHOOK_MAX_ATTEMPTS` | `8` |
| `integrations.transfer.exportMaxEntries` | `EXPORT_MAX_ENTRIES` | `10000` |
| `integrations.transfer.importMaxBytes` | `IMPORT_MAX_BYTES` (and the web tier's `MAX_BODY_SIZE`) | `5242880` |
| `integrations.transfer.importMaxRows` / `.bulkMaxMembers` | `IMPORT_MAX_ROWS` / `BULK_MAX_MEMBERS` | `5000` / `5000` |

### Audit logging (ens-audit/1)

| Value | Env | Default |
|---|---|---|
| `audit.dir` | `AUDIT_DIR` | `""` (`/data/audit`) |
| `audit.memoryEvents` | `AUDIT_MEMORY_EVENTS` | `10000` |
| `audit.stdout` | `ENS_AUDIT_STDOUT` | `true` |
| `audit.file.enabled` / `.path` | `ENS_AUDIT_FILE` | `false` / `/var/log/ens/directory-portal/audit.jsonl` |
| `audit.file.keep` | `ENS_AUDIT_FILE_KEEP` | `30` |
| `audit.file.persistence.*` | PVC at `/var/log/ens` (else an emptyDir) | disabled, `1Gi` |
| `audit.retentionDays` | `ENS_AUDIT_RETENTION_DAYS` | `0` (keep everything) |
| `audit.splunk.url` / `.index` / `.sourcetype` / `.insecure` | `ENS_AUDIT_SPLUNK_URL` / `_INDEX` / `_SOURCETYPE` / `_INSECURE` | `""` / `""` / `ens:audit` / `false` |
| `audit.splunk.token.value` / `.existingSecret` / `.existingSecretKey` | `ENS_AUDIT_SPLUNK_TOKEN` | `""` / `""` / `splunk-hec-token` |
| `audit.syslog.host` / `.port` / `.protocol` / `.facility` | `ENS_AUDIT_SYSLOG_HOST` / `_PORT` / `_PROTOCOL` / `_FACILITY` | `""` / `""` / `tcp` / `local0` |

### X.509 and HTTPS in the pod

| Value | Env | Default |
|---|---|---|
| `x509.enabled` | `X509_ENABLED` | `false` |
| `x509.passwordLoginEnabled` | `PASSWORD_LOGIN_ENABLED` | `true` |
| `x509.header` | `X509_HEADER` | `""` (`X-SSL-Client-Cert`; edge termination: the controller's header) |
| `x509.trustedProxies` | `X509_TRUSTED_PROXIES` | `10.0.0.0/8;172.16.0.0/12;192.168.0.0/16` (the pod network) |
| `x509.proxySecret.existingSecret` / `.existingSecretKey` | `X509_PROXY_SECRET_FILE` (API and web) | `""` (generated, kept across upgrades) / `proxy-secret` |
| `x509.trustedCa.value` / `.existingSecret` / `.existingSecretKey` | `X509_TRUSTED_CA_FILE` | empty: `tls`'s `ca.pem` when `tls.enabled`; required with `x509.enabled` |
| `x509.revocation.mode` | `ENS_X509_REVOCATION` | `""`: `ocsp-then-crl`, soft-fail (or `off`, `ocsp`, `crl`, `ocsp-then-crl`) |
| `x509.revocation.ocspResponderUrl` / `.crlDir` | `ENS_X509_OCSP_RESPONDER_URL` / `ENS_X509_CRL_DIR` | `""` / `""` (mount CRL files with `api.extraVolumes`) |
| `x509.revocation.fail` / `.cache` | `ENS_X509_REVOCATION_FAIL` / `ENS_X509_REVOCATION_CACHE` | `""` (`open` while `mode` is empty, `closed` once it is set) / `10m` |
| `x509.revocation.egress.ports` / `.cidrs` | | `[80, 443]` / `[0.0.0.0/0]`: NetworkPolicy egress for OCSP and CRL fetches (with a mode other than `off`, the empty default included) |
| `x509.allowedIssuers` | `ENS_X509_ALLOWED_ISSUERS` | `[]` (list, or DNs separated by `;`) |
| `x509.checkRevocation` | `X509_CHECK_REVOCATION` | `false` (deprecated: `true` means `ocsp-then-crl`, failing closed, while the mode is `off`) |
| `x509.mapping` | `X509_MAPPING` | `filter` |
| `x509.userFilter` | `X509_USER_FILTER` | `(&(objectClass=inetOrgPerson)(uid={{cn}}))` |
| `x509.requireCertificateMatch` | `X509_REQUIRE_CERTIFICATE_MATCH` | `false` |
| `x509.autoLogin` | `X509_AUTO_LOGIN` | `false` |
| `x509.allowUnrestrictedWebIngress` | | `false`: edge termination needs `networkPolicy.enabled` and `networkPolicy.webIngressFrom` |
| `tls.enabled` | web: HTTPS on 8443 (`templates-mtls`) | `false` |
| `tls.existingSecret` | Secret with `server.pem`, `server.key`, `ca.pem` | `""` |
| `tls.certificate` / `.key` / `.clientCa` | PEM values instead (`--set-file`) | `""` |
| `tls.httpsPortSuffix` | `HTTPS_PORT_SUFFIX` | `""` |

`X509_SOURCE` is always `header`: browsers reach the API only through the web tier. The web pods
also get `X509_HEADER`, `X509_EDGE_TERMINATION` (`true` with `x509.enabled` and not
`tls.enabled`: the ingress controller's header is passed on, other copies are removed) and the
proxy secret.

### Networking

| Value | Default | |
|---|---|---|
| `service.type` | `ClusterIP` | or `NodePort` / `LoadBalancer` |
| `service.port` / `service.httpsPort` | `8080` / `8443` | `httpsPort` only with `tls.enabled` |
| `service.annotations`, `.loadBalancerIP`, `.loadBalancerSourceRanges`, `.externalTrafficPolicy` | empty | LoadBalancer options; `Local` keeps client addresses |
| `ingress.enabled` / `.className` / `.annotations` | `false` / `""` / `{}` | backend port `http`, or `https` with `tls.enabled` |
| `ingress.hosts` | `portal.example.com`, `/` | `host`, `paths[].path`, `paths[].pathType` |
| `ingress.tls` | `[]` | `secretName`, `hosts` |
| `route.enabled` / `.host` / `.annotations` | `false` / `""` / `{}` | OpenShift Route |
| `route.tls.termination` | `edge` | `passthrough` / `reencrypt` need `tls.enabled` |
| `route.tls.insecureEdgeTerminationPolicy` | `Redirect` | |
| `route.tls.certificate` / `.key` / `.caCertificate` / `.destinationCACertificate` | `""` | |
| `networkPolicy.enabled` | `false` | web: in on 8080/8443, out to DNS and the API; API: in from the web pods only |
| `networkPolicy.webIngressFrom` | `[]` | peers allowed to reach the web tier (empty: any); required for edge-terminated X.509 sign-in |
| `networkPolicy.egress.allowAll` | `false` | true: the API may connect anywhere |
| `networkPolicy.egress.ldap.ports` / `.cidrs` | `[389, 636]` / `[0.0.0.0/0]` | |
| `networkPolicy.egress.audit.ports` / `.cidrs` | `[443, 514, 6514, 8088]` / `[0.0.0.0/0]` | Splunk, syslog (UDP added for `protocol: udp`), OCSP/CRL |
| `networkPolicy.egress.database.ports` / `.cidrs` | `[5432]` / `[0.0.0.0/0]` | with `database.url` |
| `networkPolicy.egress.sso.ports` / `.cidrs` | `[443]` / `[0.0.0.0/0]` | with single sign-on |
| `networkPolicy.egress.webhooks.ports` / `.cidrs` | `[443]` / `[0.0.0.0/0]` | with `integrations.webhooks.enabled` |
| `networkPolicy.egress.extra` | `[]` | more egress rules |
| `tests.enabled` | `true` | the `helm test` pod (web image, curls `/api/health` through the web tier) |

## Notes

- The web tier forwards `X-Forwarded-For` as the address it sees (the ingress controller's);
  login throttling and the audit log record that address.
- Example values files for each setup are in `ci/` in the source repository; CI lints and
  validates the chart with each of them.
