# Directory Services Portal @VERSION@: deployment package

Everything needed to run the portal on a host with Docker; no source code, build tools or
internet access are required. Built @BUILD_DATE@ from @GIT_REVISION@.

| Contents | |
|---|---|
| `install.sh` | verifies the package, loads the images, writes `.env` |
| `portal.sh` | start, stop, status, logs, backup and restore |
| `env.template` | every setting, documented (copied to `.env` by the installer) |
| `docker-compose*.yml` | the services, and optional overlays for HTTP, HTTPS/X.509 and a demo OpenLDAP |
| `images/` | the container images (Red Hat UBI 9 based) |
| `scripts/generate-dev-certs.sh` | throw-away certificates for trying HTTPS and X.509 sign-in |

Requirements: Linux with Docker Engine 20.10+ and Docker Compose v2, about 2 GB of disk.

## Install

```bash
tar xzf directory-services-portal-@VERSION@-docker.tar.gz
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
- `COOKIE_SECURE=true` whenever users reach the portal over HTTPS (including through your proxy).
- `BANNER_TEXT` and colours for a classification banner; `LOGIN_HINT` for help text.
- Metrics, analysis and background-task limits: see the comments in the file.

### License

Data analysis, metrics and schema import/export need an ENS Solutions license; everything else
(sign-in, browsing and editing the directory, self-service, administration, audit log) works
without one. Copy the license file to `license/license.lic` (the installer creates `license/`,
which is mounted read-only into the API at `/etc/ens/license`):

```bash
cp ~/acme.lic license/license.lic && chmod 644 license/license.lic
./portal.sh logs api | grep License       # "License: licensed to ..." or why not
```

A renewed or replaced file is picked up within a minute without a restart. When the license
expires the portal falls back to its core features. Administrators see the license status at the
bottom of the navigation.

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
For a test: `scripts/generate-dev-certs.sh certs` creates a CA, a server certificate and user
certificates (`certs/alice.p12`, password `password`).

## Operate

```bash
./portal.sh status                # containers, health, URL
./portal.sh logs api              # follow the API log (the audit trail is on the "audit" logger too)
./portal.sh backup                # portal-backup-<date>.tar.gz: configuration, audit log, analysis results
./portal.sh restore <file>
./portal.sh down                  # stop (data is kept in the portal-data volume)
```

The portal's data (forms, directory definitions, permissions, dashboards, analyses, the audit
log) is in the Docker volume `directory-services-portal_portal-data`. The directory data stays
in your LDAP server.

## Upgrade

Unpack the new package next to this one, copy `.env` (and `certs/`, `license/`) across, then in the new
directory run `./install.sh` and `./portal.sh up`. The data volume is reused. Take a
`./portal.sh backup` first.

## Notes

- Both containers run as unprivileged users on unprivileged ports and also run under an
  arbitrary UID (OpenShift `restricted`).
- The API is never published on the host; only the web tier talks to it.
- Run one API instance: sessions and background tasks are held in its memory.
