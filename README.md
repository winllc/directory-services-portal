# Directory Services Portal

A web frontend for LDAP directories with custom (non-standard) schemas. Administrators set up
**forms** for any object class, publish them as **directory definitions** scoped to a namespace
(read/write or read-only "white pages"), and grant **read/write permissions** to specific users
and groups. Every user gets a **My profile** page that shows where they appear across all
directories, and lets them edit fields marked self-service. **Metrics** turn any directory into
statistics and charts, and **data analysis** compares directories with each other and checks
them for correct and unique values.

```
┌──────── web (React + Vite) ─────────┐        ┌────── backend (Spring Boot 4, Java 21) ──────┐        ┌──────────┐
│ directories · entry CRUD · profile  │  /api  │ Spring Security sessions · LDAP-bind login   │  LDAP  │  LDAP    │
│ admin: schema · forms · definitions │ ─────▶ │ permissions · namespace scoping · validation │ ─────▶ │  server  │
│        · permissions                │        │ UnboundID LDAP SDK · JSON config store       │        │          │
└─────────────────────────────────────┘        └──────────────────────────────────────────────┘        └──────────┘
```

Browsers can't speak LDAP, so a Spring Boot API sits in between. It talks to the directory with a
pooled service-account connection ([UnboundID LDAP SDK](https://github.com/pingidentity/ldapsdk))
and enforces the portal's own permissions.

The **API** (`backend/`) and the **web client** (`web/`) are built and deployed separately:
- The API is a Spring Boot jar (or the `backend/Dockerfile` image) that serves only `/api`.
- The web client is a static React build. The `web/Dockerfile` image serves it with nginx and
  forwards `/api` to the API. The browser therefore sees a single origin, so the session cookie
  (`SameSite=Strict`) and the CSRF header work without any CORS configuration.

If you host the client elsewhere, route `/api` on the same origin to the API in the same way.

## Install

Customers install the portal in one of two supported ways, both from a release (see
[Releases](#releases)):

- **The release package**: the signed download for a host with Docker
  (`directory-services-portal-<version>.tar.gz`: images, compose files, `env.template`,
  `install.sh`, `upgrade.sh`, `portal.sh`), or for a Linux host with systemd and no containers
  (`directory-services-portal-<version>-native.tar.gz`: the API jar, the web client, a systemd
  unit, an nginx site and `install.sh`).
- **The Helm chart**: `oci://ghcr.io/winllc/charts/directory-services-portal` for Kubernetes and
  OpenShift (also in the download as `directory-services-portal-<version>.tgz`). See
  [`deploy/helm/directory-services-portal/README.md`](deploy/helm/directory-services-portal/README.md).

The installation guide covers both: <https://winllc.github.io/ens-apps-documentation/directory-services-portal/install/>.

```bash
# Docker host: verify the download (see Releases), then
tar xzf directory-services-portal-<version>.tar.gz && cd directory-services-portal-<version>
./install.sh --http 8080         # or --https 443 (X.509 sign-in), or nothing published
vi .env                          # LDAP connection and administrators
./portal.sh up                   # also: status, logs, backup, restore, license, down

# Linux host without containers (Java 21+, systemd, optionally nginx)
tar xzf directory-services-portal-<version>-native.tar.gz && cd directory-services-portal-<version>-native
sudo ./install.sh && sudo vi /etc/directory-services-portal/portal.env && sudo systemctl restart directory-services-portal

# Kubernetes / OpenShift
helm install dsp oci://ghcr.io/winllc/charts/directory-services-portal --version <version> \
  -n dsp --create-namespace -f my-values.yaml
```

Each package has its own README (`deploy/package/docker/README.md`,
`deploy/package/native/README.md` in this repository), a `VERSION` and `SHA256SUMS`, which the
installers verify. Building from source, the demo compose stack in this repository and
`docker build` are for development only (see [Development](#development)).

### Upgrade notes: single sign-on and the shared database

Upgrading an existing installation needs no change: without `SSO_*` and `DSP_DATABASE_*` the
portal signs in, stores its data and runs as one instance exactly as before (the Helm chart keeps
one replica and the `Recreate` strategy). What is new and optional:

- [Single sign-on](#single-sign-on-openid-connect-saml-20) (OIDC, SAML 2.0), off until configured.
  The login page gets a button per provider; `/api/auth/sso/**` is new (the web tier already
  forwards `/api/`).
- [The shared database](#high-availability-several-api-instances) (`DSP_DATABASE_*`, PostgreSQL)
  for several API instances. Its first start imports `DATA_DIR`; keep the data volume mounted for
  it, and don't return to running without the database afterwards.
- Helm: `api.replicaCount` may exceed 1 (with the database and a ReadWriteMany license volume),
  `api.updateStrategy`, `api.podDisruptionBudget`, `database.*`, `sso.*`,
  `networkPolicy.egress.database` and `.sso`.
- The release bundle's `env.template` lists the new variables; `upgrade.sh` keeps your `.env`, so
  add them from the template only if you use them. The native package reads the same variables
  from `portal.env`.

## Releases

Cut a release by pushing a tag: `git tag v1.2.3 && git push origin v1.2.3`. The CI `image` job
then tests and publishes the images (`ghcr.io/winllc/directory-services-portal-api|web:1.2.3`)
packages the Helm chart (`deploy/helm/directory-services-portal`, version and appVersion set to the
release) and pushes it to `oci://ghcr.io/winllc/charts`, and builds the customer bundle from
exactly those images with
`scripts/release-bundle.sh --chart <chart.tgz> <version> <api-image> <web-image>` (runnable
locally too):

| File | |
|---|---|
| `directory-services-portal-<version>.tar.gz` | the docker deployment package: images, compose files, `env.template`, `install.sh`, `upgrade.sh`, `portal.sh`, `license/` (a drop-in for the customer's license; never contains one) |
| `directory-services-portal-<version>-native.tar.gz` | the native (systemd) package, with the API jar and web client taken from the same images |
| `directory-services-portal-<version>.tgz` | the Helm chart (also pushed to `oci://ghcr.io/winllc/charts/directory-services-portal`) |
| `sbom-directory-services-portal-api.spdx.json`, `sbom-...-web.spdx.json` | SPDX SBOMs of the images (syft) |
| `SHA256SUMS` | checksums of the files above |
| `SHA256SUMS.asc` | GPG detached signature of `SHA256SUMS` (when a signing key is configured) |

The files are uploaded to `s3://<bucket>/directory-services-portal/<version>/`, and for versions
without a `-suffix` `directory-services-portal/latest.txt` is set to the version. They are also
kept as the workflow artifact `release-directory-services-portal-<version>` (30 days). Every
pull request does a dry run (version `0.0.0-pr<number>`, artifact kept 3 days, never
uploaded; the chart is packaged but not pushed). The `helm` job lints the chart and validates
its output with kubeconform on every push, for each values file in its `ci/` directory. Signing and upload are skipped, with a note in the job summary, until configured
(repository Settings > Secrets and variables > Actions):

| | Name | |
|---|---|---|
| secret | `RELEASE_GPG_PRIVATE_KEY` | ASCII-armored private signing key |
| secret | `RELEASE_GPG_PASSPHRASE` | its passphrase (if any) |
| secret | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | credentials that may write to the bucket |
| variable | `RELEASE_S3_BUCKET` | bucket name |
| variable | `RELEASE_S3_ENDPOINT` | S3 endpoint URL for Cloudflare R2 / MinIO (empty for AWS) |
| variable | `RELEASE_S3_REGION` | region (default `us-east-1`; `auto` for R2) |

**Supply chain** (`.github/`): Dependabot proposes weekly updates (Maven, npm, the Dockerfiles'
base images, GitHub Actions; minor and patch versions grouped). Actions are pinned to commit SHAs
and base images to digests. Every push and pull request runs CodeQL (Java and TypeScript, also
weekly), fails on `npm audit --omit=dev --audit-level=high`, and scans both images with Trivy
(`--severity HIGH,CRITICAL --ignore-unfixed`; accepted risks, with their reason and a review date,
go in `.trivyignore`). On a release tag, both images (by digest) and the chart are signed with
cosign (keyless, GitHub OIDC), and each image gets its SPDX SBOM as an attestation. Verify an image
with:

```bash
cosign verify ghcr.io/winllc/directory-services-portal-api@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/directory-services-portal/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
cosign verify-attestation --type spdxjson ghcr.io/winllc/directory-services-portal-api@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/directory-services-portal/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

(and the same for `-web` and `ghcr.io/winllc/charts/directory-services-portal@<digest>`).

A customer verifies a download (with the ENS release signing key imported) before unpacking:

```bash
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS          # add --ignore-missing if only the tarball was downloaded
tar xzf directory-services-portal-<version>.tar.gz && cd directory-services-portal-<version>
./install.sh --http 8080         # first install; upgrades: ./upgrade.sh ../directory-services-portal-<old>
```

## Licensing

The portal is proprietary to ENS Solutions (`LicenseRef-ENS-Commercial`, see [LICENSE](LICENSE));
using it requires a license.

The portal runs under a license file issued by the **ENS licensing portal** (format
`ens-license/1`, product id `directory-portal`) and checked offline against the ENS public key
built into the API (`backend/src/main/java/com/winllc/dsp/license/EnsKeys.java`; never
configurable). The verifier is the ENS library in `com.enssolutions.license`, copied unchanged
from the products-page repository (`licensing/java`). For a trial or the demo, download an
evaluation license from your account on the ENS portal.

**Modes** (from the license's state):

| Mode | When | What works |
|---|---|---|
| unlicensed | no license, or one that is invalid, for another product, bound to another installation, superseded by a newer revision or not yet valid | sign-in/out, `/api/info`, `/api/health`, the license endpoints and the [audit log](#audit-logging) (`/api/admin/audit/**`, `/admin/audit`, `/api/audit/events`). Every other API call answers `403 {"error": "<why>", "code": "license_required", "state": "MISSING"}`; the web client takes administrators to the license screen and shows everyone else a "not licensed, ask your administrator" page |
| read-only | expired past its grace period, or a perpetual license whose maintenance ended before this release was built | `GET`/`HEAD`/`OPTIONS` (browsing, search, metrics, exports); anything else answers `403` with `"code": "license_read_only"`, except sign-in/out, the license upload and the audit log (verify, export, API tokens). A banner says so |
| full | valid, or expired within its grace period (administrators get a renewal reminder) | the features the license lists |

Evaluation licenses show a banner on every page. **Memory/demo mode** (`DIRECTORY_MODE=memory`,
`SEED_DEMO_CONFIG`) needs a license too: an unlicensed demo shows only the license screen.

**Features** (catalog ids; a license lists them as `directory-portal.<id>`; each is checked in
the API with `@RequiresFeature` or in code, and the web client hides what isn't enabled):

| Feature | What it gates |
|---|---|
| `directory-editor` | browsing, searching and editing entries: `/api/definitions/**`, `/api/directories/**`, the `/d/*` pages |
| `form-builder` | forms and directory definitions: `/api/admin/forms/**`, `/api/admin/definitions/**`, and the definition editor's DN browser and search preview (`/api/admin/browse`, `/api/admin/preview`); their admin pages |
| `self-service-profile` | *My profile*: `/api/me/**`, `/me` (and drop-down options for it) |
| `white-pages` | read access for users who are not administrators: "everyone can read" directories, read grants and read-only (white pages) directories. Without it such users keep only directories they hold a write grant on; administrators keep everything |
| `smart-card-sign-in` | X.509 client-certificate sign-in (`/api/auth/x509`, certificate auto-login). When off, the login page doesn't offer it and the API refuses it with a message; password sign-in stays. With no license in effect at all it stays available, so an administrator can still reach the license screen |
| `metrics` | metrics, explorer and dashboards: `/api/metrics/**`, `/api/admin/dashboards/**`, `/metrics/*` |
| `data-analysis` | comparisons and quality checks, findings and CSV export: `/api/admin/analysis/**` |
| `schema-transfer` | schema import and export: `/api/admin/schema/import`, `/api/admin/schema/export` |

A feature the license leaves out answers `403` with `"code": "feature_not_licensed"` and
`"feature": "<id>"`. Core administration (schema, permissions, audit log, banners) needs only a
license in effect. The size (`limit`, in directory users) is shown on the license screen but not
counted or enforced: counting directory users would mean scanning the whole directory.

**License screen** (*Administration > License*, `/admin/license`; `GET /api/license` and
`POST /api/admin/license`): state and message, licensee, edition, dates (expiry, grace,
maintenance), every feature ticked or not, the size, this **installation id** (copy it into the
ENS portal to bind a license to this installation) and an upload. The upload accepts the file as
the request body (`text/plain`) or as the multipart field `file`, and says why a file is refused
(not signed by ENS, another product, bound to another installation, an older revision).

**Files**: the license and the installation's state are environment settings, not Spring
properties:

| Variable | Default | Container image |
|---|---|---|
| `ENS_LICENSE_FILE` | `/etc/ens/directory-portal.lic` | `/var/lib/ens/directory-portal/license.lic` |
| `ENS_LICENSE_STATE_DIR` (installation id, highest revisions seen) | `/var/lib/ens/directory-portal` | `/var/lib/ens/directory-portal` |

The state directory, and the license file's directory, must be writable by the API (the upload
writes the file) and persistent: losing it gives the installation a new id, and bound licenses
must then be re-bound. `npm run dev` / `npm start` use `backend/data/ens-license`. The image
declares `VOLUME /var/lib/ens` (owned by the runtime user, group 0 writable for OpenShift); the
compose files mount the named volume `ens-license` at `/var/lib/ens/directory-portal`. The API
logs `License: ...` with the state and installation id at startup.

In the release package the license lives in the `ens-license` volume (`./portal.sh license
<file>` or the license screen); with the Helm chart it is on the license PVC
(`persistence.license`, optionally seeded from `license.existingSecret` / `license.file`).

The perpetual-maintenance check uses the build time from Spring Boot's build-info
(`spring-boot-maven-plugin` `build-info` goal) as the release date. Tests trust the published ENS
TEST key only, through their own `LicenseManager` bean (`TestLicensing`, `TestLicenses` signs
licenses with it); the library's own tests run against the products-page fixtures in
`backend/src/test/resources/ens-license-fixtures`.

## Connecting to a real directory

Set these in `.env` (release package), `portal.env` (native package) or the chart's values (Helm; see
its README), and set `DIRECTORY_MODE=ldap`. They map onto `backend/src/main/resources/application.yml`:

| variable | meaning |
|---|---|
| `LDAP_URL` | `ldap://` or `ldaps://` URL; `LDAP_STARTTLS=true` upgrades plain connections. Certificates are verified against the JVM trust store unless `LDAP_TLS_REJECT_UNAUTHORIZED=false` |
| `LDAP_BIND_DN` / `LDAP_BIND_PASSWORD` | service account used for every read and write. `LDAP_BIND_PASSWORD_FILE` names a file holding the password instead (it wins when set and not empty; the Helm chart mounts it from a Secret, the native package uses `/etc/directory-services-portal/ldap-bind-password`) |
| `LDAP_BASE_DN` | default suffix (used by the DN browser) |
| `LDAP_USER_SEARCH_BASE`, `LDAP_USER_FILTER` | how to find a user at login (`{{username}}` is escaped) |
| `LDAP_USERNAME_ATTRIBUTE` | login name attribute (`uid`, `sAMAccountName`, …) |
| `LDAP_GROUP_SEARCH_BASE`, `LDAP_GROUP_FILTER` | group lookup (`{{dn}}`, `{{username}}`); `memberOf` is also used |
| `ADMIN_USERS`, `ADMIN_GROUPS` | portal administrators, separated by `;` |
| `DATA_DIR` | where `portal-config.json` (forms, definitions, grants, custom schema) and the audit log are stored |
| `AUDIT_DIR` | where the audit store goes (default `$DATA_DIR/audit`). `AUDIT_MEMORY_EVENTS` is still accepted but no longer used. Outputs and retention: `ENS_AUDIT_*`, see [Audit logging](#audit-logging) |
| `PORT`, `ENS_COOKIE_SECURE`, `SERVER_FORWARD_HEADERS_STRATEGY` | HTTP settings (see [Security headers and cookies](#security-headers-and-cookies)); session timeouts and sign-in lockout are under [Sessions](#sessions) |

Users sign in with their own directory password (the portal verifies it with an LDAP bind).
The service account needs read access to the schema and to the namespaces you expose, and write
access wherever portal users should be able to edit.

## Features

### Schema: pull, import, customize
*Admin → Schema*
- **Pull from directory** reads `subschemaSubentry` (RFC 4512) from the root DSE, including any
  non-standard object classes and attribute types.
- **Import** takes OpenLDAP `.schema` files, `cn=config` LDIF (`olcAttributeTypes`/`olcObjectClasses`)
  or raw schema descriptions.
- **Customize** any element, or create new ones. Custom definitions are stored by the portal and
  override server elements with the same OID or name. For example, you can add display names or
  describe a schema the server doesn't publish. **Export custom** gives you a `.schema` file to
  load into the server.
- The browser shows inheritance, MUST/MAY attributes, and single-/multi-value, and has a
  one-click **Create form** for any class.

### Forms (custom CRUD for any object type)
*Admin → Forms*
- Choose object classes (structural + auxiliary). The editor suggests the classes' MUST/MAY
  attributes and warns about schema problems, such as a multi-valued field on a `SINGLE-VALUE`
  attribute, a required attribute that's missing, or an attribute not allowed by the classes.
- Each field is **free text**, **text area** or **drop down**, and **single** or **multi-valued**.
- Drop-down options come from a fixed list or a **directory search** (e.g. pick a manager by
  name while storing their DN). You can optionally allow values outside the list.
- Directory-search options can be tuned:
  - **Load all options** (small sets) or **Search as you type** (people, devices, and
    other large sets). In search mode the form searches the directory after a set number of
    characters.
  - **Match typed text against** several attributes (e.g. `cn`, `uid`, `mail`). Each typed
    word must match one of them. Matching can be *contains*, *starts with* or *exact*.
  - **Most options shown**, **Sort by**, a **second line** under each option (e.g. `title`
    or `mail`), and a **label template** such as `{cn} ({uid})`.
  - How long loaded lists are reused, in seconds.
  - **Test search** tries the settings while you edit. It shows the matches, the timing, and
    the exact LDAP filter.
  - Submitted values are always checked against the directory, including values beyond the
    display limit.
- Each field can also be required, read-only or self-service. You can add a format
  (email/phone/url/number/DN), a regex pattern, a max length, a placeholder, help text and a
  section. A live preview shows the result.

### Directory definitions (namespaces)
*Admin → Directory definitions*
- Each definition ties a form to a **base DN**, **scope** and optional **filter**. The server
  only reads or writes entries inside that namespace that match the filter. Every DN is
  checked with proper RFC 4514 parsing.
- **Read / write** or **read-only white pages**. A read-only definition can never be written
  through, not even by administrators.
- You can set list columns, quick-search attributes, the title attribute and the containers
  new entries go into. Use **Test namespace** to preview which entries match.
- **Find the signed-in user** (for My profile) can match on the entry DN, or on an attribute
  such as `member = user DN`, `acmeAssignedTo = user DN` or `uid = uid`.

### Permissions
*Admin → Permissions*
- Grant **view** or **view and edit** on a definition to users or directory groups. Subjects are
  found by searching the directory. An overview matrix shows all grants.
- A definition can be readable by every signed-in user. Administrators have full access to
  read/write definitions.

### End users
- Directory list with search, sorting and paging. Entries can be viewed, created, edited
  (only changed attributes are sent, and changing the naming attribute renames the entry) and
  deleted.
- **My profile** shows every definition that can locate the user, and lets them edit their own
  self-service fields.

### Metrics
*Metrics* (every signed-in user)

Statistics and charts computed live from the directories. Every chart reads one directory
definition, so it only ever covers entries the viewer is allowed to read.

- **Directory overviews** (out of the box): a ready-made dashboard for each directory, generated
  from its form: entry count, new entries per month (`createTimestamp`), a breakdown for every
  drop-down field and e-mail domain, containers (sub-tree directories), values per entry for
  multi-valued links (e.g. group members) and how completely each field is filled in.
- **Dashboards**: administrators combine charts from any directories into dashboards, and share
  them with all signed-in users or keep them for administrators. A viewer sees charts from
  directories they can't read as locked.
- **Explorer**: anyone can build a chart from a directory they can read and watch it update as
  they configure it. The settings live in the URL, so a chart can be bookmarked or sent to a
  colleague; administrators can add it to a dashboard. Every overview and dashboard chart has a
  button that opens it in the explorer to customize.

| Measure | Shows | Charts |
|---|---|---|
| Count | number of entries | number |
| Breakdown | entries per value of an attribute, top N with the rest as "Other", entries without a value as "(none)"; optionally split by a second attribute | bars, columns, donut, stacked bars, table |
| Completeness | share of entries with a value, per attribute | bars, table |
| Over time | entries per day, week, month, quarter or year of a date attribute (GeneralizedTime or ISO dates), optionally as a running total | columns, line, area, table |
| Numbers | count, average, median, lowest and highest, plus a histogram, of numeric values or of how many values each entry has | columns, number, table |

Values can be grouped before counting: lower/upper case, e-mail domain, the name in a DN (e.g.
a manager's `uid`) or its parent container, the first N characters, a regular expression's first
group, or a date's year, quarter, month, week or day. Drop-down values are shown with their
option labels (so `manager` DNs show as people's names). An extra LDAP filter narrows any chart.

Every chart has a table view and a CSV download. Results are cached for
`METRICS_CACHE_SECONDS` (**Refresh** recomputes them); charts that read the same directory share
one paged scan of it. Charts that take longer than `METRICS_WAIT_MILLIS` to compute continue in
the background (see [Background tasks](#background-tasks)).

- Administrators may chart any attribute of a directory's object classes. Everyone else is
  limited to the attributes the directory shows them (form fields, list columns, title) and the
  entry timestamps. Extra filters follow the same rule, so a filter can't be used to probe
  values the user couldn't otherwise see. Password attributes can never be charted or filtered
  on.
- User-supplied regular expressions run with a time budget, so a pathological pattern fails
  that chart instead of tying up the server.
- At most `METRICS_MAX_ENTRIES` entries are read per directory; larger results are marked as
  partial.

### Data analysis
*Admin → Data analysis*

Saved analyses that run on demand against live data, always as [background tasks](#background-tasks):
**Run** returns at once, the page shows progress (and can be left), and the latest result of each
saved analysis is kept on disk (`<DATA_DIR>/analysis-results/`) to reopen later. It is marked as
out of date when the analysis settings change afterwards. Running with unsaved changes keeps the
saved result and shows the draft's result separately. A run's counts cover every entry read;
the list of individual findings is capped at `ANALYSIS_MAX_ISSUES` (default 50,000). The
server pages, filters and searches the findings, so large results stay fast in the browser. The
CSV download includes every finding that matches the current filters, not just the page shown.
Each finding links to the entry.

- **Comparison** of two to six directories (or filtered parts of directories). Entries are
  matched by a key attribute per source (e.g. `uid`, `mail` or `employeeNumber`), and the run
  reports keys missing from some sources, keys used by more than one entry in a source, and the
  compared attributes whose values differ. Attributes can be named differently per source.
  Comparisons can ignore case, extra spaces and the order of multiple values; DNs always compare
  in normalized form.
- **Quality check** of one to six directories, with any of these rules per attribute (each an
  error or a warning):

  | Rule | Finds |
  |---|---|
  | Is present / has a single value | missing or multiple values |
  | Is unique | values used by another entry, within each source or across all of them |
  | Matches a pattern / has a valid format | values that don't match a regular expression, or aren't a valid e-mail address, phone number, URL, number, integer, date or DN |
  | Is one of a list / has a length between | values outside a list, or too short or long |
  | Refers to existing entries | DN values (e.g. `manager`, `member`) that point at entries that don't exist |

  **Check every form field's rules** adds the rules already configured on each directory's form
  (required, single value, format, pattern, maximum length and drop-down options), so the data
  can be checked against what the portal would accept today.

The demo includes two dashboards (*Workforce*, *IT assets and groups*) and two analyses
(*Staff vs. white pages*, *People data quality*). Creating, changing and deleting dashboards
and analyses is recorded in the audit log.

### Background tasks
*The bell in the top bar*

Analyses, and metrics that take a while, run on the server independently of the browser:

- Analyses always run in the background. Metrics run in the background when they take longer
  than `METRICS_WAIT_MILLIS` (2 s by default); quicker ones are answered right away as before.
- While a task runs, its page shows what it is doing ("Reading Devices"), how many entries it has
  read and, once a source has been read before, how far along it is. It can be cancelled.
- The page can be left or closed. When the task finishes, the portal announces it with a link to
  the result: a notification in the portal and, if allowed ("Notify me on this device"), a
  desktop notification while the tab is in the background. Tasks whose page is open are not
  announced; the page simply shows the result.
- The bell lists the user's recent tasks with their progress, outcome and links. Opening a result
  shortly after it finished (15 minutes, or `METRICS_CACHE_SECONDS` if longer) shows it without
  computing it again; asking for the same thing while it runs joins the running task.
- Tasks belong to the user who started them. Each user can have `JOBS_MAX_ACTIVE_PER_USER` tasks
  running or queued. Metrics and analyses have separate worker pools (`JOBS_METRICS_THREADS`,
  `JOBS_ANALYSIS_THREADS`), so a long analysis never holds up dashboards. Finished tasks are kept
  for `JOBS_RETAIN_MINUTES`.
- Tasks live in the API's memory: a restart cancels running ones (saved analysis results on disk
  are kept). Run a single API instance.

### Audit logging
*Administration → Audit log* (`/admin/audit`, administrators only: `ROLE_ADMIN`)

Every write goes to the directory as the portal's service account, so the directory's own logs
can't say who made a change. The portal keeps a complete, tamper-evident audit trail in the
format shared by all ENS products, **`ens-audit/1`** (one JSON object per event; field reference
and SIEM setup on the docs site page [Audit logging](https://github.com/winllc/ens-apps-documentation/blob/master/docs/audit-logging.md)), and can deliver it to
Splunk or any SIEM.

**What is recorded** (action ids are stable; the portal's original ids are kept):

| Category | Actions |
|---|---|
| authentication | `auth.login` (success or failure; `details.method` `password` or `x509`, the attempted user name or certificate subject, serial and issuer, and the reason on failure), `auth.logout`, `auth.lockout` (`LOGIN_MAX_FAILURES` failures within `LOGIN_LOCKOUT_WINDOW`, by default 8 in 15 minutes; severity alert), `auth.session-expired` (reason `idle`, `absolute`, `concurrent` or `account-removed`, see [Sessions](#sessions)), `auth.consent-acknowledged` (see [Notice and consent banner](#notice-and-consent-banner)), `auth.password-changed` / `auth.password-reset` (a password attribute changed through *My profile* / by an administrator) |
| authorization | `access.denied` for every 403: role refused, `license_required`, `license_read_only`, `feature_not_licensed`, CSRF header missing, the portal's own permission checks (method, path, reason code). Identical denials (same actor, path, reason) within 60 s are folded into one more event with `details.count` |
| account | `grant.save`, `grant.revoke`: permissions on directories for users and groups |
| data | `entry.create`, `entry.update`, `entry.self_update`, `entry.delete` (each changed attribute as `<attribute>.old` / `.new`; a delete keeps everything the entry held; a rename records `previousDn`), `form.*`, `definition.*`, `schema.import`, `schema.attribute_type.*`, `schema.object_class.*`, `analysis.export` and `data.exported` (findings CSV, schema export) |
| configuration | `dashboard.*`, `analysis.create` / `update` / `delete` |
| license | `license.installed`, `license.rejected` (with the reason), `license.mode-changed` (old and new mode; checked at startup, every minute and after an upload). Older history keeps the original `license.install` |
| system | `system.started` (version), `system.stopped`, `job.completed` / `job.failed` (the retention job) |
| audit | `audit.exported`, `audit.verified`, `audit.token-created`, `audit.token-revoked`, `audit.pruned`, `audit.delivery-failed`, `audit.delivery-recovered` |

Each event says who (`actor`: user name, display name, roles; `details.actorDn` and
`authMethod`; `anonymous` with the attempted name for a failed sign-in), from where
(`source.ip` as the API sees it, honouring `SERVER_FORWARD_HEADERS_STRATEGY`, and the user agent), what
(`target`), the outcome (`success`, `failure`, `denied`) and a severity (`info`, `notice` for
permission, configuration, form/definition/schema and license changes, `warning` for failures
and denials, `alert` for lockouts, a broken chain and failing delivery), plus a request id and a
hash of the session id. Requests that fail validation are not recorded (nothing was attempted),
nor is a save that changes nothing.

- **Secrets are never recorded**: password attributes (`userPassword`, `unicodePwd`, …), tokens,
  keys, license text, session ids, one-time codes and PINs are written as `[redacted]`.
- **The store** is the monthly files `audit-YYYY-MM.jsonl` in `AUDIT_DIR` (default
  `$DATA_DIR/audit`; the `portal-data` volume in Docker), one hash-chained line per event,
  appended and synced in the request that caused it. Writers are serialized by a single
  synchronized writer in the API process, so **run one API instance per data directory**: two
  instances writing the same files would fork the chain (the same applies to sessions and
  background tasks). If an event can't be stored, sign-ins and permission, configuration,
  license and token changes are refused (503); other events log an error. Directory changes
  (create, update, rename, delete) check first that the store is writable, with at least 1 MB
  free, and are refused (503) rather than made unaudited when it is not.
- **Upgrading** from the portal's earlier audit format: at the first start the original
  `audit-YYYY-MM.jsonl` files are moved unchanged to `audit/legacy/` (still readable as they
  were) and their events are appended to the new store in time order, keeping time and id, with
  new seq numbers and hashes and `details.migrated=true`; `audit/legacy/MIGRATED` records it.
- **The page** searches all retained history, newest first and paged, by time range, category,
  action, outcome, severity, actor, target, source IP and free text (over the JSON); a search
  reads at most `ENS_AUDIT_QUERY_MAX_SCAN` events (default 2,000,000) and says when it stopped.
  A row opens every field, the JSON, seq and hash. An entry's **History** button opens the page
  filtered to that entry, including events from before a rename. **Export** writes the current
  filter (up to 100,000 events) as CSV or NDJSON (`audit.exported`). **Verify integrity**
  recomputes the chain: intact, or the first broken seq; after pruning, the first retained
  event is the anchor and its `prevHash` is accepted as given. **Delivery** shows each output's
  state, and **API tokens** manages the pull API's tokens. The page and its API work whatever
  the license says.

**Settings** (environment; `ens.audit.*` Spring properties):

| Variable | Default | Meaning |
|---|---|---|
| `ENS_AUDIT_STDOUT` | `true` | each event as one raw JSON line on stdout (logger `ens.audit`, its own appender, no log prefix) |
| `ENS_AUDIT_FILE` | empty (off) | also a rolling file, e.g. `/var/log/ens/directory-portal/audit.jsonl` (rolled daily and at 100 MB) |
| `ENS_AUDIT_FILE_KEEP` | `30` | days of rolled files kept |
| `ENS_AUDIT_RETENTION_DAYS` | `0` (keep forever) | a daily job deletes older events that every enabled output has delivered, never the newest, and records `audit.pruned` |
| `ENS_AUDIT_SPLUNK_URL` | empty (off) | Splunk HTTP Event Collector, e.g. `https://splunk.example:8088` |
| `ENS_AUDIT_SPLUNK_TOKEN` / `ENS_AUDIT_SPLUNK_TOKEN_FILE` | | the HEC token, or a file holding it (never logged) |
| `ENS_AUDIT_SPLUNK_INDEX` | empty | optional index |
| `ENS_AUDIT_SPLUNK_SOURCETYPE` | `ens:audit` | |
| `ENS_AUDIT_SPLUNK_INSECURE` | `false` | skip the HEC certificate check (test setups only; warned at startup). Otherwise the JVM trust store applies |
| `ENS_AUDIT_SYSLOG_HOST` | empty (off) | syslog receiver |
| `ENS_AUDIT_SYSLOG_PORT` | `514` (udp, tcp), `6514` (tls) | |
| `ENS_AUDIT_SYSLOG_PROTOCOL` | `tcp` | `udp`, `tcp` or `tls` (octet-counting framing on tcp and tls) |
| `ENS_AUDIT_SYSLOG_FACILITY` | `local0` | RFC 5424 messages: APP-NAME `directory-portal`, MSGID the action, severity from the event, the JSON line as the message |
| `ENS_AUDIT_QUERY_MAX_SCAN` | `2000000` | most events one search or export reads |

Splunk HEC and syslog are delivered in the background from the store, by a cursor kept in
`audit-state.json` next to it: at least once (Splunk can de-duplicate on `id`), retried with
backoff up to 5 minutes, never slowing requests. An output failing for over 15 minutes records
`audit.delivery-failed` (alert), then `audit.delivery-recovered`. The API logs one line per
output at startup. The rename of the log category from `audit` to `ens.audit` keeps routing
working: the lines go to stdout (and the file) as before, now without the log prefix; set
`LOGGING_LEVEL_ENS_AUDIT=OFF` (instead of `LOGGING_LEVEL_AUDIT`) to silence them.

**Getting it into Splunk** (any one of):

1. **HEC**: create an HEC token (sourcetype `ens:audit`) and set `ENS_AUDIT_SPLUNK_URL` and
   `ENS_AUDIT_SPLUNK_TOKEN` (and `ENS_AUDIT_SPLUNK_INDEX`).
2. **Universal Forwarder**: tail the file (`ENS_AUDIT_FILE`; the compose files mount the
   `ens-audit` volume at `/var/log/ens`) or the container's Docker logs, with
   `sourcetype=ens:audit` and JSON extraction, e.g. `[monitor:///var/log/ens/directory-portal/audit*.jsonl]`
   with `sourcetype = ens:audit`.
3. **Pull API**: create a token under *Audit log → API tokens* (shown once; stored only as
   SHA-256), then poll
   `curl -H "Authorization: Bearer ensat_…" "https://portal.example/api/audit/events?after=<last seq>&limit=500"`.
   It returns NDJSON in seq order (or `{"events": […], "next": <seq>}` with
   `Accept: application/json`); pass the last seq back as `after`. Tokens only read the audit
   trail, need no session or CSRF header, work while the portal is unlicensed or read-only, and
   record their last use instead of auditing each pull.

### X.509 client-certificate sign-in
Users can sign in with a client certificate (smart cards such as CAC/PIV, or any PKI-issued user
certificate), alongside or instead of passwords. Set `X509_ENABLED=true` and choose where the
certificate comes from:

- **The web proxy terminates mutual TLS** (`X509_SOURCE=header`, recommended). Browsers connect
  to the web tier, so this is how certificate sign-in works with the separate web and API
  services. The proxy verifies the certificate and forwards it in `X509_HEADER`. The formats
  understood are PEM, URL-encoded PEM (nginx `$ssl_client_escaped_cert`, AWS ALB), base64 DER and
  Envoy `x-forwarded-client-cert`. The header is **refused unless the connecting peer is in
  `X509_TRUSTED_PROXIES` and the request carries the proxy secret**: the proxy sends
  `X-DSP-Proxy-Secret` with every request, with the value of `X509_PROXY_SECRET_FILE` (preferred)
  or `X509_PROXY_SECRET` (at least 32 characters, e.g. `openssl rand -hex 32`; the same value for
  the API and the web tier). An address alone is not enough: other pods or local processes can
  share it, and the header carries no proof that the sender holds the certificate's key. The
  address check uses the actual connection, never `X-Forwarded-For`, so use
  `SERVER_FORWARD_HEADERS_STRATEGY=framework` or `none`, not `native`. The proxy must overwrite any
  incoming copy of the certificate header and of `X-DSP-Proxy-Secret`; the bundled nginx configs
  always do, and forward the certificate only to the sign-in endpoints (`/api/auth/x509`,
  `/api/auth/me`). `X509_TRUSTED_CA_FILE` is required too, so that the API validates the chain
  itself. **The API refuses to start in header mode without the proxy secret or the CA file.**
  Refused headers are logged (WARN) and audited (`auth.login` failure with
  `details.reasonCode=untrusted_certificate_header` and the connecting `peerAddress`).

  The web image reads the secret from the same `X509_PROXY_SECRET_FILE` / `X509_PROXY_SECRET`.
  It removes `X-SSL-Client-Cert` and `X509_HEADER` from client requests on plain HTTP, unless a
  TLS-terminating proxy in front of it verifies client certificates and always sets
  `X509_HEADER` itself (edge termination, `X509_EDGE_TERMINATION=true` on the web container;
  then nothing else may reach the web tier). The release packages and the Helm chart generate
  and wire the secret.

  The web image's `templates-mtls` nginx config does this: HTTPS with `ssl_verify_client
  optional`, so password sign-in keeps working. The release package turns it on with
  `./install.sh --https` (`docker-compose.mtls.yml`, certificates in `./certs`); the Helm chart
  with `tls.enabled` and `x509.enabled` (TLS passthrough to the web pod; the chart README also
  covers edge termination at the ingress, with `x509.header`). nginx runs unprivileged (uid 1001,
  group 0), so `server.key` must be readable by that user or group (for example
  `chgrp 0 server.key && chmod 0640 server.key`). To try it from the source tree (development):
  ```bash
  scripts/generate-dev-certs.sh certs          # throw-away CA, server cert, admin/alice/bob .p12 (password: password), proxy secret
  docker compose -f docker-compose.yml -f docker-compose.mtls.yml up --build
  # import certs/alice.p12 into the browser, open https://localhost:8443, "Sign in with certificate"
  ```
- **The API terminates mutual TLS itself** (`X509_SOURCE=servlet`, `mtls` Spring profile with
  `TLS_CERT_FILE`, `TLS_KEY_FILE`, `TLS_CLIENT_CA_FILE`). Tomcat requests a client certificate
  (`client-auth: want`) and verifies it against `TLS_CLIENT_CA_FILE`. Use this when clients
  connect to the API directly, for example scripts or another service. A browser only goes
  through it if the web tier passes TLS straight through to the API.

Every certificate is checked before it is accepted:
- It must be within its validity period.
- It must be allowed for TLS client authentication (extended/key usage).
- When `ENS_X509_ALLOWED_ISSUERS` is set, its issuer must be one of those DNs, even if another
  trusted CA chains to it.
- When `X509_TRUSTED_CA_FILE` is set, it must chain to one of those CAs, and it must not be
  revoked (`ENS_X509_REVOCATION`).

**Revocation checking** (CAC/PIV; DISA STIG requires it, so the API logs a warning at startup when
certificate sign-in is on with `ENS_X509_REVOCATION=off`):

| variable | default | meaning |
|---|---|---|
| `ENS_X509_REVOCATION` | `ocsp-then-crl` | `off`, `ocsp`, `crl` or `ocsp-then-crl` (CRLs when OCSP gives no answer). A mode you set needs `X509_TRUSTED_CA_FILE`: the API does not start without it. `X509_CHECK_REVOCATION=true`, the older setting, means `ocsp-then-crl` failing closed while this is unset or `off` |
| `ENS_X509_OCSP_RESPONDER_URL` | | ask this responder instead of the certificate's AIA one, e.g. a local OCSP relay on an air-gapped network |
| `ENS_X509_CRL_DIR` | | a directory of CRL files (DER or PEM), read again when they change: the air-gapped option. With it set, CRL distribution points are **not** fetched |
| `ENS_X509_REVOCATION_FAIL` | `open` while `ENS_X509_REVOCATION` is unset, otherwise `closed` | `closed`: when the status can't be determined, refuse; `open`: allow and log a warning |
| `ENS_X509_REVOCATION_CACHE` | `10m` | how long a good or revoked answer is kept, per issuer and serial |
| `ENS_X509_ALLOWED_ISSUERS` | | issuer DNs separated by `;` (DNs contain commas); certificates from other issuers are refused |

The checks are configured per sign-in (the JDK's PKIX validator with its own revocation checker;
no global JDK security setting is changed). OCSP responses must be signed by the issuing CA or a
responder it delegated to; CRLs by the issuing CA. The API fetches OCSP responses and CRLs itself:
http or https only (and only https/http redirects, at most 3), 5 s to connect, 10 s to read, OCSP
responses up to 1 MB and CRLs up to 32 MB, never to link-local, multicast or cloud-metadata
addresses; LDAP distribution points are skipped. Fetched CRLs are reused until their next update
(at most a day). A refusal is audited as an `auth.login` failure with the reason
`certificate revoked` or `revocation unknown` (`details.reasonCode` `certificate_revoked` /
`revocation_unknown`).

**The default is a live check that soft-fails.** With neither `ENS_X509_REVOCATION` nor
`ENS_X509_REVOCATION_FAIL` given (or both empty), every certificate is checked over OCSP and then
its CRL. One found revoked is refused and audited. One that no responder or CRL answers for (the
network is closed, the responder is down, the certificate names neither) signs in, and a warning
naming it is logged: `Allowing client certificate … although no OCSP responder or CRL answered for
it (soft-fail; …)`. A warning at startup says the check is soft-fail and how to change it:
- DISA STIG expects fail-closed: set `ENS_X509_REVOCATION_FAIL=closed` once the responders or
  `ENS_X509_CRL_DIR` answer reliably.
- On an air-gapped network, `ENS_X509_CRL_DIR` (or an `ENS_X509_OCSP_RESPONDER_URL` relay) gives the
  check something to answer with, and saves each sign-in waiting on fetches that cannot succeed.
- `ENS_X509_REVOCATION=off` still turns the check off, with the startup warning above.
- Choosing a mode yourself (`ocsp`, `crl` or `ocsp-then-crl`) keeps `closed` as the failure
  behaviour, as in earlier releases; so does `X509_CHECK_REVOCATION=true`.
- The default check verifies answers against `X509_TRUSTED_CA_FILE` (with the `mtls` profile,
  `TLS_CLIENT_CA_FILE`). Without it the API still starts, with a warning, and every certificate
  signs in unchecked, with a warning; set it, or set `ENS_X509_REVOCATION=off`.
- Upgrading: a `.env` that sets `ENS_X509_REVOCATION=off` (the earlier package template did) keeps
  the check off; clear the value to get the default.

It is then mapped to exactly one directory user:

| `X509_MAPPING` | how the user is found |
|---|---|
| `filter` (default) | `X509_USER_FILTER` with placeholders `{{cn}}`, `{{uid}}`, `{{email}}` (rfc822 SAN, else the emailAddress attribute), `{{upn}}` (the SAN otherName UserPrincipalName, OID 1.3.6.1.4.1.311.20.2.3, on CAC/PIV cards), `{{edipi}}` (the DoD EDIPI: the digits of a UPN `1234567890@mil`, else the trailing `.1234567890` of a CN `LAST.FIRST.MI.1234567890`), `{{uuid}}` (from a SAN URI `urn:uuid:…`, the PIV FASC-N UUID, without the `urn:uuid:` prefix), `{{subject}}`, `{{serial}}`. The other ENS applications' form `{upn}` (single braces) works too. Values are filter-escaped; a filter matching no entry or more than one signs nobody in. Examples: `(uid={{cn}})`, `(mail={email})`, `(userPrincipalName={upn})`, `(employeeID={edipi})` |
| `subject-dn` | the certificate subject DN is the user's entry DN (it must also match the user filter's shape) |

Options:
- `X509_REQUIRE_CERTIFICATE_MATCH=true` also requires the presented certificate to be published
  in the user's `userCertificate` attribute. Certificates that aren't registered on the account
  are then refused.
- `X509_AUTO_LOGIN=true` signs users in as soon as their browser presents a valid certificate.
  After an explicit sign-out, automatic sign-in stays off until the user clicks "Sign in with
  certificate".
- `PASSWORD_LOGIN_ENABLED=false` makes the portal certificate-only.

Certificate sign-ins and rejections are logged with the certificate subject, serial and issuer.
Group membership, admin status and permissions work exactly as they do for password sign-ins.

> With TLS 1.3, Java cannot request a client certificate after the handshake. The certificate
> must be presented when the connection is established, which is how `client-auth: want`
> works. Tomcat logs a warning about this at startup.

### Single sign-on (OpenID Connect, SAML 2.0)
Users can sign in through the organization's identity provider. Each protocol is **off until
configured**; password and certificate sign-in work as before alongside it (`PASSWORD_LOGIN_ENABLED=false`
leaves only single sign-on and certificates). The login page shows one button per configured
provider.

How a sign-in is mapped: the provider asserts an identity (an OIDC claim, or a SAML attribute or
the NameID). `SSO_*_USER_FILTER`, with `{{value}}` replaced by that identity (filter-escaped), must
match **exactly one** directory entry; none or several refuses the sign-in. Groups, administrator
status (`ADMIN_USERS`, `ADMIN_GROUPS`) and permissions then come from the directory, exactly as for
password sign-in, and are read again every `GROUP_REFRESH_INTERVAL`. The provider's own groups
make a user an administrator **only** when one of them is listed in `SSO_*_ADMIN_GROUPS`
(`;`-separated, compared case-insensitively); nothing else the provider asserts grants a role.

| variable | default | |
|---|---|---|
| `SSO_OIDC_ISSUER_URI` | | the issuer; `https://` (http only for localhost). Its discovery document must name the same issuer |
| `SSO_OIDC_CLIENT_ID` | | with the issuer, turns OpenID Connect on |
| `SSO_OIDC_CLIENT_SECRET_FILE` / `SSO_OIDC_CLIENT_SECRET` | | the client secret (prefer the file); empty: a public client |
| `SSO_OIDC_SCOPES` | `openid profile email` | |
| `SSO_OIDC_USERNAME_CLAIM` | `preferred_username` | the claim mapped through the user filter |
| `SSO_OIDC_USER_FILTER` | `(&(objectClass=inetOrgPerson)(uid={{value}}))` | |
| `SSO_OIDC_GROUPS_CLAIM` / `SSO_OIDC_ADMIN_GROUPS` | `groups` / empty | provider groups that grant administrator |
| `SSO_OIDC_LABEL` | `Sign in with single sign-on` | button text |
| `SSO_SAML_METADATA` | | the IdP's metadata: an `https://` URL or a file. Turns SAML on |
| `SSO_SAML_ENTITY_ID` | `https://<portal>/api/auth/sso/saml/metadata` | this service provider's entity id |
| `SSO_SAML_USERNAME_ATTRIBUTE` | empty: the NameID | the attribute mapped through the user filter |
| `SSO_SAML_USER_FILTER`, `SSO_SAML_GROUPS_ATTRIBUTE`, `SSO_SAML_ADMIN_GROUPS`, `SSO_SAML_LABEL` | as OIDC | |
| `SSO_SAML_CLOCK_SKEW` | `2m` | tolerated clock difference (at most `10m`) |
| `SSO_SAML_SIGNING_KEY_FILE` / `SSO_SAML_SIGNING_CERT_FILE` | | PKCS#8 PEM key and certificate, for IdPs that require signed AuthnRequests |

Endpoints to register with the provider (`<portal>` is the address users open; behind a proxy it
must forward `X-Forwarded-Proto` and `X-Forwarded-Host`, as the web tier does):

| | |
|---|---|
| OIDC redirect URI | `https://<portal>/api/auth/sso/callback/oidc` |
| SAML entity id and SP metadata | `https://<portal>/api/auth/sso/saml/metadata` |
| SAML assertion consumer service (HTTP-POST) | `https://<portal>/api/auth/sso/saml/acs/saml` |

What is checked. OIDC: the authorization code flow with PKCE (S256, confidential clients too),
`state` and `nonce`; the ID token's signature against the provider's JWKS, its issuer, audience
(the client id) and expiry (60 seconds of clock skew). SAML: AuthnRequests go by HTTP-Redirect;
the response or every assertion must be signed with a key from the IdP's metadata; issuer,
audience (the entity id), recipient, `NotBefore`/`NotOnOrAfter` (with `SSO_SAML_CLOCK_SKEW`) and
`InResponseTo` (this browser's request) are checked; **IdP-initiated** (unsolicited) responses are
refused, and each assertion id is accepted only once (replay). Sign-in requests in flight are kept
on the server under a one-time handle in a short-lived `DSP_SSO` cookie, because the provider's
answer never carries the `SameSite=Strict` session cookie; SAML's cross-site POST needs that cookie
to be `SameSite=None; Secure`, so **SAML requires HTTPS** (set `ENS_COOKIE_SECURE=true` behind a
TLS proxy). The provider's tokens are not kept: the session holds only the portal user. The
notice-and-consent banner must be acknowledged before a sign-on starts. Every sign-on and refusal
is audited as `auth.login` with `method` `oidc` or `saml` (refusals with a `reasonCode`); the user
is sent back to `/login?sso_error=<code>` with a message. Sign-out ends the portal session only
(not the provider's).

**Microsoft Entra ID** (OIDC). App registrations > New registration; platform *Web*, redirect URI
as above. Certificates & secrets > new client secret. Token configuration > add the groups claim
(*Security groups*, emitted as *Group ID*) if administrators come from an Entra group. Then:
`SSO_OIDC_ISSUER_URI=https://login.microsoftonline.com/<tenant-id>/v2.0`,
`SSO_OIDC_CLIENT_ID=<application (client) id>`, the secret, and for Active Directory users
`SSO_OIDC_USERNAME_CLAIM=preferred_username` with
`SSO_OIDC_USER_FILTER=(&(objectClass=user)(userPrincipalName={{value}}))`;
`SSO_OIDC_ADMIN_GROUPS=<object id of the group>`. Users in more than 200 groups get no groups
claim (Entra's overage rule): give them administrator rights through `ADMIN_GROUPS` instead.

**Okta** (OIDC). Applications > Create App Integration > OIDC, *Web Application*; sign-in redirect
URI as above; assign the users. `SSO_OIDC_ISSUER_URI=https://<org>.okta.com/oauth2/default` (or
your custom authorization server), the client id and secret. For administrator groups, add a
`groups` claim to the authorization server (filter, e.g. *Starts with* `portal-`) and list the
group names in `SSO_OIDC_ADMIN_GROUPS`. Map users by `preferred_username` (the Okta login) or
`email` (`SSO_OIDC_USERNAME_CLAIM=email`, `SSO_OIDC_USER_FILTER=(&(objectClass=inetOrgPerson)(mail={{value}}))`).

**AD FS** (SAML, or OIDC on AD FS 2016+). SAML: AD FS Management > Relying Party Trusts > Add,
*Import data about the relying party published online*: `https://<portal>/api/auth/sso/saml/metadata`.
Claim issuance policy: *Send LDAP Attributes as Claims*, User-Principal-Name to *UPN* and
Token-Groups (unqualified names) to *Group*. Then
`SSO_SAML_METADATA=https://<adfs>/FederationMetadata/2007-06/FederationMetadata.xml`,
`SSO_SAML_USERNAME_ATTRIBUTE=http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn`,
`SSO_SAML_USER_FILTER=(&(objectClass=user)(userPrincipalName={{value}}))`, and for administrator
groups `SSO_SAML_GROUPS_ATTRIBUTE=http://schemas.xmlsoap.org/claims/Group` with
`SSO_SAML_ADMIN_GROUPS=Portal Admins`. AD FS signs assertions by default; if it requires signed
requests, give the SP a key (`SSO_SAML_SIGNING_*`) and re-import the metadata. OIDC on AD FS:
Application Groups > *Server application accessing a web API*;
`SSO_OIDC_ISSUER_URI=https://<adfs>/adfs`, `SSO_OIDC_USERNAME_CLAIM=upn`.

**Multi-factor authentication.** Password sign-in has no second factor of its own: time-based
one-time passwords (RFC 6238) for password sign-in are planned but not part of this release. For
MFA today, use single sign-on (the provider's MFA and conditional access apply) or certificate
sign-in (CAC/PIV), and set `PASSWORD_LOGIN_ENABLED=false`.

### High availability (several API instances)
By default the API is a single instance: sessions, the sign-in lockout and background tasks are
in its memory, and configuration and the audit store are files in `DATA_DIR`. To run several
API instances behind one web tier or load balancer, give them a shared **PostgreSQL** (13 or
later) database:

| variable | default | |
|---|---|---|
| `DSP_DATABASE_URL` | | `jdbc:postgresql://host:5432/dsp`; unset: no shared database (nothing changes) |
| `DSP_DATABASE_USERNAME` | | |
| `DSP_DATABASE_PASSWORD_FILE` / `DSP_DATABASE_PASSWORD` | | prefer the file |
| `DSP_DATABASE_POOL_SIZE` | `10` | connections per instance |
| `DSP_CONFIG_REFRESH` | `2s` | how soon an instance sees a configuration change made on another |

PostgreSQL was chosen because the portal had no datastore of its own and the configuration and
audit chain must survive any one instance: a durable SQL store holds all shared state with one
piece of new infrastructure (the other ENS applications already run PostgreSQL), where Redis
would have needed a second store for the configuration and audit trail. The API creates and
upgrades its tables itself (Flyway, `backend/src/main/resources/db/migration`; the role needs
`CREATE` on its schema). With it:

- **Sessions** are Spring Session JDBC sessions (`spring_session`), with the same `DSP_SESSION`
  cookie, timeouts and per-user limit across instances. Idle timeouts are recorded by a job that
  one instance runs at a time.
- **The sign-in lockout** counts failures in `dsp_login_failure` (keys stored as SHA-256), so it
  holds whichever instance a request reaches.
- **Configuration** is a versioned document: a change locks it, applies to the latest version and
  writes the next, so changes made on different instances at once are applied one after the
  other, never lost; other instances read the new version within `DSP_CONFIG_REFRESH`.
- **The audit store** is one hash chain in `dsp_audit_event` (appends are serialized by a database
  lock), with the same lines and verification as the files. Audit API tokens and forwarder
  cursors are shared too. The Splunk and syslog forwarders run on one elected instance (another
  takes over within seconds when it stops); retention and the license-mode check run under a
  cluster lock (ShedLock).
- **Background tasks** run on the instance that started them; their progress and results are
  shared, so the task list, results, cancelling and dismissing work from any instance.
- **Single sign-on** requests in flight and used SAML assertion ids are shared, so the provider's
  answer may reach any instance.

The license state (installation id, license, revisions seen) is still kept in
`ENS_LICENSE_STATE_DIR`: every instance must see the same directory (a shared volume; on
Kubernetes a ReadWriteMany license volume, which the chart requires for more than one replica).
The ENS license library's database store will remove that requirement.

**Moving an existing installation to the database.** Set the `DSP_DATABASE_*` variables and
restart the single instance with its data volume still mounted. The first start imports
`portal-config.json`, the audit files (the chain carries on from the same head), the audit API
tokens and forwarder cursors and, when first read, the saved analysis results, and logs an
`Imported ...` line for each. The files are left as they were but are no longer read or written:
**don't go back** to running without the database afterwards (the files would miss everything
since), and back up the database instead of the data volume. Then add instances. Background tasks
running during the restart are lost, as with any restart. On Kubernetes see the chart README,
Several API replicas; the chart then uses rolling updates and a PodDisruptionBudget.

### Page banners
An optional fixed banner can be shown at the top and bottom of every page, including the login
page. It's typically used for a classification or environment marking. The banners stay visible
above dialogs and menus, and the layout is offset so no content sits underneath them. They're
configured on the API:

| variable | default | meaning |
|---|---|---|
| `BANNER_TEXT` | *(empty: off)* | Banner text, at most 200 characters |
| `BANNER_FOREGROUND` | `#ffffff` | Text color |
| `BANNER_BACKGROUND` | `#007a33` | Background color |

Colors may be `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgb()`/`rgba()`/`hsl()`/`hsla()` or a CSS color
name. Any other value stops the API at startup with an error naming the setting.

### Sessions
Session controls (DISA STIG; the ENS platform baseline):

| variable | default | meaning |
|---|---|---|
| `ENS_SESSION_IDLE_TIMEOUT` | `30m` | idle timeout (a duration: `30m`, `1h`). `SESSION_TTL_MINUTES` (minutes), its older name, still works when this is not set; its default used to be 480 minutes |
| `ENS_SESSION_ADMIN_IDLE_TIMEOUT` | the idle timeout | a shorter idle timeout for administrators (STIG suggests `15m`) |
| `ENS_SESSION_ABSOLUTE_TIMEOUT` | `12h` | a session ends this long after sign-in, whatever its activity |
| `ENS_SESSION_MAX_PER_USER` | `0` (no limit) | concurrent sessions per user; signing in once more ends the **oldest** (STIG suggests `3`) |
| `GROUP_REFRESH_INTERVAL` | `5m` | how often a signed-in user's groups and administrator status are read again from the directory, checked on their next request (`0`: only at sign-in). An account that no longer exists ends its sessions |
| `LOGIN_MAX_FAILURES` | `8` | failed password sign-ins for one user name from one address before further attempts are refused (`429`, `auth.lockout`) (STIG suggests `3`) |
| `LOGIN_LOCKOUT_WINDOW` | `15m` | the window the failures are counted in, from the first; the refusal lasts until it ends. Failures are kept in the API's memory (a restart clears them), or in the shared database (see [High availability](#high-availability-several-api-instances)) |

Idle time counts what the user does: the web client marks its background polling (task progress,
license and audit status) with `X-DSP-Background: 1`, which keeps nothing alive, so an open but
unattended tab still times out. An expired session answers `401` with `"code": "session_expired"`
and the browser shows the sign-in page with the reason; each expiry is audited as
`auth.session-expired` with the reason `idle`, `absolute`, `concurrent` or `account-removed`.

### Health and metrics
The API uses Spring Boot's probes, which need no sign-in or license:

| path | answers |
|---|---|
| `/actuator/health/liveness` (also `/livez`) | the API itself is running |
| `/actuator/health/readiness` (also `/readyz`) | it can serve: the directory answers (`ldap`), the audit log is writable (`auditLog`), and the data disk has 10 MB free (`diskSpace`); `503` otherwise |
| `/actuator/health` | both; details (components and errors) only for signed-in administrators |
| `/api/health` | kept for existing monitoring: always `{"ok":true}` while the API runs (liveness only) |

The web tier publishes only `/api`, so none of these is reachable from browsers. The image's
`HEALTHCHECK`, the release package's compose file and the Helm chart use readiness (and liveness
for restarts in Kubernetes).

**Prometheus** (`/actuator/prometheus`) is served only on a management port of its own:
`MANAGEMENT_SERVER_PORT` (Spring Boot's setting, unset by default; `MANAGEMENT_SERVER_ADDRESS` to
bind it, the native package uses `127.0.0.1`). Without it there is no Prometheus endpoint. On the
management port, health and Prometheus are open without sign-in, and `/livez` and `/readyz`
stay on the main port; never publish the management port to users. Metrics, besides the JVM's and
Tomcat's:

| metric | |
|---|---|
| `ens_license_mode{mode}` | 1 for the mode in effect (`full`, `read_only`, `unlicensed`), 0 for the others |
| `ens_license_days_remaining` | days until the license (or its grace) ends; NaN for a perpetual license or none |
| `ens_audit_events_total{category,outcome}` | audit events stored |
| `ens_audit_forward_failures_total{output}` | failed deliveries to `splunk` or `syslog` |
| `ens_auth_attempts_total{method,outcome}` | sign-ins (`password`, `x509`) by outcome (`success`, `failure`, `denied`) |
| `ens_dsp_ldap_operation_seconds{operation,outcome}` | LDAP operations (`search`, `scan`, `get`, `add`, `modify`, `rename`, `delete`, `bind`, `schema`, `ping`), timed; `failure` when one failed |
| `ens_dsp_ldap_pool_available` | idle connections in the service account's pool |
| `ens_dsp_entry_writes_total{operation,outcome}` | entry `create`, `update` and `delete` through the portal (`success`, `failure`, `denied`) |

**Logs**: `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` (Spring Boot's setting) writes log lines as JSON
in the Elastic Common Schema; the Helm chart does by default (`logFormat: json`). Audit events on
stdout stay `ens-audit/1` JSON lines either way.

### Notice and consent banner
For DoD systems (DISA STIG), the portal can show the standard USG notice-and-consent banner before
sign-in. The login page shows it with an **I agree** button, and the API enforces it: while it is
enabled, no sign-in of any kind (password, certificate, certificate auto-login) succeeds until
this browser session has acknowledged it. The acknowledgement is kept in the session and is
audited as `auth.consent-acknowledged` (authentication, success, with the client address); a
sign-in attempted without it is refused (`403`, `"code": "consent_required"`) and audited as an
`auth.login` denial with `details.reasonCode=consent_required`. Signing out ends the session, so
the banner comes before every sign-in. `POST /api/auth/consent` (with `X-DSP-Request: 1`) and the
banner's text in `/api/info` are reachable without signing in.

| variable | default | meaning |
|---|---|---|
| `ENS_CONSENT_BANNER_ENABLED` | `false` | show and enforce the banner |
| `ENS_CONSENT_BANNER_TITLE` | `U.S. Government (USG) Information System (IS) Notice and Consent` | |
| `ENS_CONSENT_BANNER_TEXT` | the standard DoD notice-and-consent text | plain text; blank lines separate paragraphs, paragraphs starting with `- ` are list items |
| `ENS_CONSENT_BANNER_FILE` | | read the text from this file instead (wins over `_TEXT`); the API does not start if it can't read it |

Exempt, because they are not browser sessions: the audit pull API (`/api/audit/**`, bearer
tokens), the health probes (`/actuator/health/**`, `/livez`, `/readyz`, `/api/health`) and the
Prometheus endpoint on the management port.

### Security headers and cookies
Every response carries the ENS baseline security headers: the web tier (nginx) sends them for the
client's pages and in place of the API's own on `/api`, and the API sends them on its own
responses for anyone who reaches it directly:

- `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'`
  (the client has no inline scripts or event handlers)
- `Strict-Transport-Security: max-age=31536000; includeSubDomains` on HTTPS: always with the
  mTLS config, behind a TLS-terminating proxy when it sends `X-Forwarded-Proto: https`, and from the
  API on secure requests (direct TLS, or behind a proxy with `SERVER_FORWARD_HEADERS_STRATEGY=framework`)
- `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: strict-origin-when-cross-origin`,
  `Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()`

| variable | default | meaning |
|---|---|---|
| `ENS_CSP_REPORT_ONLY` | `false` | `true` sends `Content-Security-Policy-Report-Only` instead (API and web image): an escape hatch for a page the policy breaks. Report it, and turn this off again |
| `ENS_COOKIE_SECURE` | `false` | Always mark the session cookie (`DSP_SESSION`) `Secure`, for TLS terminated at a proxy. Over direct TLS it is `Secure` anyway. `COOKIE_SECURE` is the older name and still works |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `none` | Spring Boot's own setting: `framework` behind a proxy that overwrites `X-Forwarded-*` (the web tier does; every package sets it). `FORWARD_HEADERS_STRATEGY` is the older name and still works |

The session cookie is `HttpOnly` and `SameSite=Strict` (stricter than the baseline's `Lax`; the
browser only ever reaches the API through the same origin).

## FIPS 140-3

The portal brings no cryptography of its own: the API uses the platform JDK's providers (JCA/JSSE)
and the web tier the system's OpenSSL. On a FIPS-enabled RHEL 9 host (`fips=1`), the UBI 9 images
(`openjdk-21-runtime`, `nginx-126`) follow the host into FIPS mode, so these use the RHEL FIPS
140-3 validated modules: TLS to the directory (LDAPS, StartTLS), to Splunk HEC and syslog (TLS),
HTTPS and client-certificate verification in nginx or in the API (`mtls` profile), certificate
path validation and OCSP/CRL signature checks, and the audit chain's SHA-256. The native package
uses the host's Java 21: install RHEL's `java-21-openjdk-headless` on a FIPS-enabled host. (OCSP
requests identify the certificate by a SHA-1 hash of its issuer, as RFC 6960 responders expect;
that is an identifier, not a signature. BouncyCastle is used by the tests only.)

Not FIPS (or STIG) appropriate, for test setups only; the API logs a warning at startup when one
is on:

- `LDAP_TLS_REJECT_UNAUTHORIZED=false`: trusts any directory server certificate. Give the API a
  trust store with the directory's CA instead.
- `ENS_AUDIT_SPLUNK_INSECURE=true`: trusts any Splunk HEC certificate.

### License verification under FIPS

Pending: the ENS license library verifies license signatures with the JDK's Ed25519; its results
on a FIPS-enabled host will be recorded here.

## Security notes
- LDAP filters are built with the UnboundID filter API, which escapes every user-supplied value.
  Admin-configured filter templates get their values escaped with `Filter.encodeValue`. DNs are
  parsed and built with RFC 4514 escaping, and namespace checks compare parsed DNs.
- Writes are limited to the form's writable fields. The server re-checks required fields,
  single-value rules, options, formats and patterns (`FormValidator`, which mirrors the client
  rules in `shared/`).
- Password attributes are never returned to the browser. Empty passwords are rejected before
  any bind, so anonymous binds can't be used to log in.
- Certificate sign-in validates the certificate (dates, key usage, issuer allowlist, chain and
  revocation) before mapping it to exactly one directory user. Forwarded certificate headers are
  only honoured from trusted proxy addresses that also send the proxy secret
  (`X-DSP-Proxy-Secret`, compared in constant time); without the secret and a trusted CA file
  the API does not start in header mode.
- The web proxy overwrites `X-Forwarded-For` (clients can't spoof their address, which login
  throttling relies on) and `X-DSP-Proxy-Secret`, and always replaces or removes the client
  certificate header. The API is not published in Docker Compose, so only the proxy can reach it.
- Sessions are Spring Security `HttpSession`s in a `DSP_SESSION` cookie (`HttpOnly`,
  `SameSite=Strict`, `Secure` over TLS or when `ENS_COOKIE_SECURE=true`). The session ID is rotated at login,
  and every state-changing request must carry the `X-DSP-Request` header (CSRF defence).
  `/api/admin/**` requires `ROLE_ADMIN`, read again from the directory every
  `GROUP_REFRESH_INTERVAL`. Failed logins are throttled (`LOGIN_MAX_FAILURES`), and sessions time
  out (see [Sessions](#sessions)).
- Sessions are held in server memory, or with `DSP_DATABASE_URL` in the shared database (Spring
  Session JDBC; see [High availability](#high-availability-several-api-instances)). Run a single
  instance without the database.
- Single sign-on maps the provider's assertion to one directory entry and never takes a role from
  the provider unless `SSO_*_ADMIN_GROUPS` says so; see [Single sign-on](#single-sign-on-openid-connect-saml-20)
  for what is validated.
- Metrics and analyses read through the same permission checks as browsing (see
  [Metrics](#metrics)); comparisons and quality checks are for administrators only.
- Changes, sign-ins and denials are recorded in the [audit log](#audit-logging). Treat the audit
  files, exports and API tokens as sensitive: they hold the values that were changed and the
  names people tried to sign in with.

## Development

This section is for contributors. None of it is an installation method: customers install the
release package or the Helm chart (see [Install](#install)).

### Quick start (demo directory, no LDAP server needed)

Requirements: Java 21 and Node.js 20+. Maven is not needed; the backend ships the Maven wrapper.

```bash
npm install
npm run dev            # API on :3001, web client on http://localhost:5173 (proxies /api)
```

In demo mode (the default, `DIRECTORY_MODE=memory`) the backend starts an **embedded LDAP server**
(UnboundID in-memory directory) on a loopback port. The portal talks to it over the LDAP protocol,
exactly as it would to a production server. The demo server loads the standard schema, a
non-standard `acme*` schema (`acmePerson`, `acmeDevice`, `acmePartner`), about ten people, groups
and devices, plus example forms, directories and permissions. Every demo password is `password`:

| user    | what they see                                                              |
|---------|----------------------------------------------------------------------------|
| `admin` | administrator: everything, including the admin pages                        |
| `erin`  | HR group: can edit Staff Directory                                          |
| `dave`  | helpdesk group: can edit Devices                                            |
| `alice` | engineering: can view Staff, has a user grant to view Devices, self-service |
| `bob`   | can view Partners; white pages and groups only otherwise                    |
| `frank` | external partner (ou=partners)                                              |

The API needs an ENS license in demo mode too: without one you sign in and land on the license
screen. Download an evaluation license from the ENS licensing portal and upload it there (as
`admin`); it is kept in `backend/data/ens-license`.

Production build without Docker: `npm run build && npm start`. This builds `web/dist` and
`backend/target/directory-services-portal.jar`, then runs the API on :3001 and serves the client
on http://localhost:8080 with `vite preview`, which forwards `/api`. In a real deployment, serve
`web/dist` from any web server that routes `/api` to the API and falls back to `index.html` for
other paths. `web/nginx/templates/default.conf.template` is a ready-made example.

### Docker Compose (demo stack in the source tree)

For development and demos only; customers use the release package or the Helm chart.

`docker compose up --build` starts three services (plus the one-shot `license-init`). **None of them publishes a port on the host.**
To open the portal, either route a reverse proxy on the compose network to `web:8080`, or uncomment
the `ports` entry of the `web` service and open http://localhost:8181.

| service | image | published |
|---|---|---|
| `web` | `web/Dockerfile`: nginx (UBI 9 `nginx-126`) serving the React client on port 8080, proxying `/api` to `api` (`API_URL`) | no (uncomment `8181`) |
| `api` | `backend/Dockerfile`: the Spring Boot API (UBI 9 `openjdk-21-runtime`) on port 3001 | no (uncomment `3001` to call it directly) |
| `openldap` | `deploy/openldap`: Ubuntu's `slapd`, built locally (amd64 and arm64, incl. Apple Silicon) | no (uncomment `1389`) |
| `license-init` | the API image: copies `./license/license.lic` into the `ens-license` volume if it has none, then exits | no |

OpenLDAP is loaded with the same demo organisation, ACME schema and **default credentials as
the built-in demo**: `admin` / `password` (administrator), or `alice`, `bob`, `erin`, `dave`,
`frank` with password `password`. The example directories and permissions are seeded too.

The demo needs an ENS license (download an evaluation license from the ENS licensing portal):
upload it on the license screen after signing in as `admin`, or save it as `license/license.lic`
before `docker compose up`; the one-shot `license-init` service copies it into the `ens-license`
volume when that has no license yet. The volume keeps the license and the installation id
(`docker compose down -v` deletes them).

- The `web` and `api` images are built on Red Hat Universal Base Image 9
  (`registry.access.redhat.com/ubi9/...`: `nodejs-22` and `openjdk-21` to build, `nginx-126` and
  `openjdk-21-runtime` to run). Both run as unprivileged users (uid 1001 and 185, group 0) and
  listen on unprivileged ports, so they also run under an arbitrary UID (OpenShift `restricted`).
  The demo `openldap` image stays on Ubuntu because RHEL 9 no longer ships the OpenLDAP server.
- Building the `web` and `api` images applies the latest Red Hat updates to their runtime layers
  (`yum update`, or `microdnf update` on the minimal Java image), so the build needs access to
  the UBI repositories (`cdn-ubi.redhat.com`). Rebuild regularly to pick up security fixes. The
  base images are pinned by digest (Dependabot proposes new ones).

- The LDIF in `deploy/openldap/ldif` is only loaded into an empty directory. Run
  `docker compose down -v` after changing it, or if you started the stack from an older version.
- The API trusts `X-Forwarded-*` headers from the proxy (`SERVER_FORWARD_HEADERS_STRATEGY=framework`; the
  `api` service is reachable only from the compose network). It therefore sees real client addresses, which login throttling relies on.
- If a container fails to start, `docker compose logs <service>` shows why.
- The web container looks up the API (`API_URL`) with the container runtime's own DNS server, the
  first `nameserver` in its `/etc/resolv.conf` (Docker `127.0.0.11`, Podman's aardvark-dns).
  Other listed servers (Podman adds the host's) don't know service names, so they are not used.
  Set `NGINX_LOCAL_RESOLVERS` on the web service to choose the server yourself. In Kubernetes,
  give `API_URL` a fully qualified service name, because nginx doesn't apply search domains.
- Requests may carry up to 32 KB of headers (`MAX_HTTP_REQUEST_HEADER_SIZE` on the API), which
  leaves room for large single-sign-on cookies.

### Building the images

The production images (the license is never baked into them):

```bash
docker build -t directory-services-portal-api:0.1.0 backend
docker run -d --name dsp-api -p 3001:3001 -v dsp-data:/data -v dsp-license:/var/lib/ens/directory-portal \
  -e DIRECTORY_MODE=ldap -e LDAP_URL=ldaps://ldap.example.com:636 ... \
  directory-services-portal-api:0.1.0
# then upload the license on the license screen, or copy it into the volume:
docker exec -i dsp-api sh -c 'cat > /var/lib/ens/directory-portal/license.lic' < acme.lic
# JAVA_OPTS defaults to -XX:MaxRAMPercentage=75.0.
```

### Building deployment packages

`scripts/package.sh` (or `npm run package`) builds a standalone package: one archive with
everything a host needs, without the source tree, build tools or internet access.

```bash
scripts/package.sh                          # dist/directory-services-portal-<version>-docker.tar.gz
scripts/package.sh --with-openldap          # ... including the demo OpenLDAP image
scripts/package.sh --target native          # jar + web client + systemd unit + nginx site (no Docker)
scripts/package.sh --registry reg.example.com/dsp --push --no-images   # images in your registry
```

CI builds the release packages with `scripts/release-bundle.sh` (see [Releases](#releases));
`scripts/package.sh` builds the same packages locally, for testing them. The **docker** package
holds the images (`docker save`), compose files that use them, every setting in one documented
`.env`, `install.sh`, `upgrade.sh` and `portal.sh`. The **native** package installs the API as a
systemd service (Java 21+, listening on 127.0.0.1:3001 only) and the client behind nginx. Each
package has its own README (templates in `deploy/package/`), a `VERSION` and `SHA256SUMS`, which
the installers verify; the archive gets a `.sha256` file.

The version defaults to the `package.json` version plus the git revision (`--version` to set
it). Builds behind a proxy or with a corporate CA can pass `DOCKER_BUILD_ARGS`, or alternative
Dockerfiles in `API_DOCKERFILE` / `WEB_DOCKERFILE`. `--api-image` / `--web-image` package
images that are already built (by reference, e.g. `ghcr.io/winllc/directory-services-portal-api:1.2.3`)
instead of building them; with `--target native` they supply the jar and the web client (this is
how CI builds the native package from the images it tested).

### Helm chart

```bash
helm lint --strict deploy/helm/directory-services-portal -f deploy/helm/directory-services-portal/ci/default-values.yaml
helm template dsp deploy/helm/directory-services-portal -f deploy/helm/directory-services-portal/ci/mtls-values.yaml
```

### Tests and layout

```bash
npm test            # backend: JUnit + MockMvc against the embedded LDAP server; web: Vitest
npm run typecheck   # shared + web TypeScript
npm run build       # web bundle + Spring Boot jar
cd backend && ./mvnw spring-boot:run   # API only
```

Layout:
- `backend/`: the Spring Boot service, package `com.winllc.dsp`:
  - `ldap/`: gateway, embedded demo server, DN and schema helpers
  - `schema/`: schema service, index and form linter
  - `service/`: auth, permissions, entries and validation
  - `web/`: REST controllers and DTOs
  - `store/`: the JSON config store
  - `x509/`: client-certificate parsing, validation and user mapping
  - `metrics/`: metric widgets, aggregation engine and the out-of-the-box overviews
  - `analysis/`: directory comparisons and data-quality checks, and their stored results
  - `jobs/`: background tasks (worker pools, progress, cancellation)
  - `config/`: security and SPA serving
- `shared/`: TypeScript types, DN utilities, validation and schema helpers used by the client.
- `web/`: the React client (`src/pages` for user, metrics and admin pages, `src/components`,
  including the SVG charts in `src/components/charts`), with
  `web/Dockerfile` and its nginx setup in `web/nginx/` (`nginx.conf`, the server `templates`
  and `entrypoint.sh`, which renders them at start).
- `backend/Dockerfile`: the API image. `deploy/openldap/`: the demo OpenLDAP image.
- `scripts/release-bundle.sh`: the customer release bundle (docker and native packages, Helm
  chart, SBOMs, checksums).
- `deploy/helm/directory-services-portal/`: the Helm chart (its `ci/` values files are what CI
  lints and validates).
- `scripts/package.sh` and `deploy/package/`: the standalone deployment packages (templates for
  their compose files, installers and READMEs).
