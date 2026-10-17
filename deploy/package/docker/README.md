# Directory Services Portal @VERSION@: deployment package

Everything needed to run the portal on a host with Docker; no source code, build tools or
internet access are required. Built @BUILD_DATE@ from @GIT_REVISION@.

| Contents | |
|---|---|
| `install.sh` | verifies the package, loads the images, writes `.env` |
| `upgrade.sh` | upgrades a running deployment to this version, keeping its settings and data |
| `portal.sh` | start, stop, status, logs, backup, restore and license install |
| `env.template` | every setting, documented (copied to `.env` by the installer) |
| `docker-compose*.yml` | the services, and optional overlays for HTTP, HTTPS/X.509 and a demo OpenLDAP |
| `images/` | the container images (Red Hat UBI 9 based) |
| `license/` | optional drop-in for your ENS Solutions license (`license/license.lic`), copied in by the installer |
| `scripts/generate-dev-certs.sh` | throw-away certificates for trying HTTPS and X.509 sign-in |

Requirements: Linux with Docker Engine 20.10+ and Docker Compose v2, about 2 GB of disk.

## Install

```bash
tar xzf directory-services-portal-@VERSION@*.tar.gz
cd directory-services-portal-@VERSION@
./install.sh --http 8080          # plain HTTP on port 8080, e.g. behind your TLS proxy
vi .env                           # LDAP connection and administrators
./portal.sh up
```

Other ways to publish it:

```bash
./install.sh --https 443          # HTTPS (+ optional X.509 sign-in); put certs in ./certs
./install.sh                      # nothing published: route a proxy on the compose network to web:8080
./install.sh --demo --http        # try it: built-in demo directory, users admin/alice/... password "password"
./install.sh --demo-openldap --http   # demo with the bundled OpenLDAP (if this package includes it)
```

The installer never overwrites an existing `.env`; `--force-env` recreates it (keeping a copy).

## Configure

All settings are in `.env`; after a change run `./portal.sh up`. The first section chooses the
compose files (`COMPOSE_FILE`) and ports; the rest configures the portal:

- `LDAP_URL`, `LDAP_BIND_DN`, `LDAP_BIND_PASSWORD`, `LDAP_BASE_DN`: the directory and the service
  account the portal uses. For `ldaps://` with a private CA, put a trust store in the data volume
  and point `JAVA_TOOL_OPTIONS` at it, or set `LDAP_TLS_REJECT_UNAUTHORIZED=false` for testing.
- `LDAP_USER_SEARCH_BASE`, `LDAP_USER_FILTER`, `LDAP_GROUP_*`: how users and their groups are found.
- `ADMIN_USERS`, `ADMIN_GROUPS`: who administers the portal (`;` separated).
- `ENS_COOKIE_SECURE=true` whenever users reach the portal over HTTPS (including through your
  proxy; `COOKIE_SECURE`, the older name, still works). The web tier sends the security headers
  (CSP, HSTS behind an HTTPS proxy that sets `X-Forwarded-Proto`, and others); `ENS_CSP_REPORT_ONLY=true`
  only reports CSP violations, as an escape hatch.
- `ENS_CONSENT_BANNER_ENABLED=true` for the DoD notice-and-consent banner before sign-in (DISA
  STIG); the standard DoD text unless `ENS_CONSENT_BANNER_TEXT` / `ENS_CONSENT_BANNER_FILE` say otherwise.
- `BANNER_TEXT` and colours for a classification banner; `LOGIN_HINT` for help text.
- Metrics, analysis and background-task limits: see the comments in the file.

### License

The portal needs an ENS Solutions license file (`.lic`) from your account on the ENS licensing
portal; for a trial (including `--demo`), download an evaluation license there. Until one is
installed only the sign-in page works, and administrators are taken to the license screen.

Install it either way:

- **License screen**: sign in as an administrator; you land on *Administration > License*
  (`/admin/license`). Upload the file there; it says why a file is refused (another product,
  bound to another installation, an older revision, not signed by ENS).
- **Command line**: put it at `license/license.lic` before `./install.sh` (copied into the
  license volume if that has no license yet), or install/replace it at any time with
  `./portal.sh license /path/to/license.lic`.

The license lives in the `ens-license` volume (`/var/lib/ens/directory-portal` in the api
container), together with this **installation id** (shown on the license screen; you enter it on
the ENS portal to bind a license to this installation) and the license revisions seen. Upgrades
keep the volume; `docker compose down -v` would delete it, and a new installation id would mean
re-binding the license. A renewed license takes effect as soon as it is installed.

What the license unlocks (each a feature on the license, shown ticked or not on the license
screen): the directory editor, form builder, self-service profiles, white pages (directory
access for users who are not administrators), smart-card (X.509) sign-in, metrics and dashboards,
data-quality analysis and schema import/export. An expired license (after its grace period) makes
the portal read-only; evaluation licenses show a banner.

### HTTPS and X.509 client certificates

`./install.sh --https [PORT]` adds `docker-compose.mtls.yml`: nginx serves HTTPS with the
certificates in `./certs` and asks browsers for an (optional) client certificate:

| File | |
|---|---|
| `certs/server.pem` | server certificate (with chain) |
| `certs/server.key` | its private key, readable by uid 1001 (`chmod 644`, or `chgrp 0` + `chmod 640`) |
| `certs/ca.pem` | CA(s) that issue user certificates |

Set `X509_ENABLED=true` (done by the installer) and adjust `X509_USER_FILTER` to map certificates to
directory users. Password sign-in keeps working unless `PASSWORD_LOGIN_ENABLED=false`.
nginx forwards the verified certificate to the API together with `X509_PROXY_SECRET`, a random
secret `install.sh` writes to `.env` (and `upgrade.sh` adds when an older `.env` has none): the
API accepts a forwarded certificate only with it, and checks it against `certs/ca.pem` too. Keep
`.env` private (mode 600).
For a test: `scripts/generate-dev-certs.sh certs` creates a CA, a server certificate and user
certificates (`certs/alice.p12`, password `password`).

## Operate

```bash
./portal.sh status                # containers, health, URL
./portal.sh logs api              # follow the API log (audit events are raw JSON lines on it, logger ens.audit)
./portal.sh backup                # portal-backup-<date>.tar.gz: configuration, audit log, analysis results
./portal.sh restore <file>
./portal.sh license <file.lic>    # install or replace the ENS license (or upload it on the license screen)
./portal.sh down                  # stop (data is kept in the portal-data volume)
```

The portal's data (forms, directory definitions, permissions, dashboards, analyses, the audit
log) is in the Docker volume `directory-services-portal_portal-data`. The directory data stays
in your LDAP server. With `ENS_AUDIT_FILE` set in `.env`, the rolling audit file is in the
`ens-audit` volume (`/var/log/ens` in the container) for a log forwarder to read; Splunk HEC and
syslog delivery are configured in `.env` too (see the main README, Audit logging).

## Upgrade

Unpack the new package next to the running one and run its `upgrade.sh` with the old directory:

```bash
tar xzf directory-services-portal-<new-version>*.tar.gz
cd directory-services-portal-<new-version>
./upgrade.sh ../directory-services-portal-<old-version>
```

It copies `.env`, `license/` and `certs/` across (never overwriting), verifies the package and
loads its images, adds settings new in this release (`X509_PROXY_SECRET`, generated), backs up
the portal data to `backups/`, points `.env` at the new images (keeping a `.env.bak-<time>`) and
restarts with `./portal.sh up`. The data volume and the
license volume (`ens-license`: installed license and installation id) are reused; a
`license/license.lic` is copied in only if the volume has no license yet.
To go back, run `./portal.sh up` in the old directory and, if needed, `./portal.sh restore` the
backup.

## Notes

- Both containers run as unprivileged users on unprivileged ports and also run under an
  arbitrary UID (OpenShift `restricted`).
- The API is never published on the host; only the web tier talks to it.
- Run one API instance: sessions and background tasks are held in its memory.
