# Directory Services Portal @VERSION@: deployment package

Everything needed to run the portal on a host with Docker or Podman; no source code, build tools
or internet access are required. Built @BUILD_DATE@ from @GIT_REVISION@.

| Contents | |
|---|---|
| `install.sh` | verifies the package, loads the images, writes `.env` |
| `upgrade.sh` | upgrades a running deployment to this version, keeping its settings and data |
| `portal.sh` | start, stop, status, logs, backup, restore and license install |
| `engine.sh` | picks Docker or Podman and its compose command (used by the scripts above) |
| `env.template` | every setting, documented (copied to `.env` by the installer) |
| `docker-compose.yml` | the services, with the web tier published on the host (HTTP on `WEB_BIND:WEB_PORT`; `install.sh` writes `127.0.0.1:8080`) |
| `docker-compose.no-publish.yml` | the same without the published port, for a proxy on the compose network |
| `docker-compose.*.yml` | optional overlays for HTTPS/X.509 and a demo OpenLDAP |
| `images/` | the container images (Red Hat UBI 9 based) |
| `license/` | optional drop-in for your ENS Solutions license (`license/license.lic`), installed by the installer |
| `scripts/generate-dev-certs.sh` | throw-away certificates for trying HTTPS and X.509 sign-in |

Requirements: Linux with about 2 GB of disk and either

- **Docker** Engine 20.10+ with Docker Compose v2 (`docker compose`; the old `docker-compose`
  1.29 is used when v2 is missing, best effort), or
- **Podman** 4.0+ with podman-compose 1.1+ (`pip install --user podman-compose`, or your
  distribution's package), rootful or rootless; `podman compose` is used when podman-compose
  is not installed. See [Docker or Podman](#docker-or-podman).

## Install

```bash
tar xzf directory-services-portal-@VERSION@*.tar.gz
cd directory-services-portal-@VERSION@
./install.sh                      # plain HTTP on 127.0.0.1:8080: this host only, e.g. for your TLS proxy on it
vi .env                           # LDAP connection and administrators
./portal.sh up
```

By default plain HTTP is published on **this host only** (`WEB_BIND=127.0.0.1`), port 8080
(`WEB_PORT`): plain HTTP carries directory passwords and the session cookie in clear, so it is not
offered to the network unless you choose it. To serve the network, either put a TLS-terminating
proxy on this host in front of it (and set `ENS_COOKIE_SECURE=true` in `.env`), or use `--https`
(published on every interface, `ENS_COOKIE_SECURE=true`). `--bind 0.0.0.0` publishes plain HTTP on
every interface, for a TLS proxy on another host; the installer warns about it.

Other ways to publish it:

```bash
./install.sh --http 8081          # plain HTTP on another port (this host only)
./install.sh --bind 0.0.0.0       # plain HTTP on every interface: only behind a TLS proxy elsewhere
./install.sh --https 8443         # HTTPS only on every interface (+ optional X.509 sign-in); put certs in ./certs
./install.sh --https 8443 --http  # HTTPS, and HTTP on 8080 redirecting to it
./install.sh --no-publish         # nothing published: route a proxy on the compose network to web:8080
./install.sh --demo               # try it: built-in demo directory, users admin/alice/... password "password"
./install.sh --demo-openldap      # demo with the bundled OpenLDAP (if this package includes it)
```

`--no-publish` uses `docker-compose.no-publish.yml` instead of `docker-compose.yml` (the same
services without the published port; `COMPOSE_FILE` in `.env`). Your proxy joins the
`directory-services-portal_default` network and forwards to `http://web:8080`.

The installer never overwrites an existing `.env`; `--force-env` recreates it (keeping a copy).
A new `.env` gets a random `DSP_SECRETS_KEY`, the key webhook signing secrets are stored encrypted
with (`--force-env` carries the previous one over): **keep a copy of `.env` with, but apart from,
your backups**, since the stored secrets can't be read without it. An `.env` from an earlier
release is not given one (secrets stay unencrypted, with a warning); add
`DSP_SECRETS_KEY=$(openssl rand -base64 32)` to it to encrypt them at the next start.

### Docker or Podman

The scripts use Docker if its daemon answers, else Podman, and say which engine and compose
command they chose. `--engine docker|podman` (install.sh, upgrade.sh) or the `CONTAINER_ENGINE`
environment variable chooses explicitly; the installer records the engine in `.env`
(`CONTAINER_ENGINE`), and `portal.sh` keeps using it. Each engine has its own containers and
volumes: switching an installed portal to the other engine starts it with empty data (move the
data with `./portal.sh backup` and `./portal.sh restore`).

With Podman (4.0 or later; podman-compose 1.1 or later):

- **Rootless** works: the images run as unprivileged users, and the portal's data is in named
  volumes, so there is nothing to `chown`. Rootless Podman can't publish ports below 1024 (the
  defaults, 8080 and 8443, are fine); for 443, use `sudo sysctl net.ipv4.ip_unprivileged_port_start=443`
  (persist it in `/etc/sysctl.d/`), or a proxy, and the installer warns about it. On a host
  still on cgroups v1, rootless Podman ignores the memory limits (and says so).
- **Keep it running after logout** (rootless): `sudo loginctl enable-linger $USER`.
- **Start at boot**: Podman has no daemon restarting `restart: unless-stopped` containers. Run
  `./portal.sh up` from a systemd unit (for rootless, a user unit, `systemctl --user`):

  ```ini
  [Unit]
  Description=Directory Services Portal
  Wants=network-online.target
  After=network-online.target

  [Service]
  Type=oneshot
  RemainAfterExit=yes
  WorkingDirectory=/opt/directory-services-portal
  ExecStart=/opt/directory-services-portal/portal.sh up
  ExecStop=/opt/directory-services-portal/portal.sh down

  [Install]
  WantedBy=multi-user.target
  ```

  (`WantedBy=default.target` for a user unit.) `podman generate systemd --new` or Quadlet units
  work too, but then systemd, not `./portal.sh`, owns the containers.
- **SELinux** (RHEL, Fedora): the certificate files in `./certs` are mounted with `:Z`/`:z`, so
  Podman relabels them; nothing else is bind-mounted.
- **Healthchecks**: Podman runs them from systemd timers; `./portal.sh up` also runs the API's
  check itself while it waits, so hosts without systemd work as well.

## Configure

All settings are in `.env`; after a change run `./portal.sh up`. The first section chooses the
compose files (`COMPOSE_FILE`) and ports; the rest configures the portal:

- `LDAP_URL`, `LDAP_BIND_DN`, `LDAP_BIND_PASSWORD`, `LDAP_BASE_DN`: the directory and the service
  account the portal uses (`LDAP_BIND_PASSWORD_FILE` reads the password from a file in the
  container instead). For `ldaps://` with a private CA, put a trust store in the data volume
  and point `JAVA_TOOL_OPTIONS` at it, or set `LDAP_TLS_REJECT_UNAUTHORIZED=false` for testing.
- `LDAP_USER_SEARCH_BASE`, `LDAP_USER_FILTER`, `LDAP_GROUP_*`: how users and their groups are found.
- `ADMIN_USERS`, `ADMIN_GROUPS`: who administers the portal (`;` separated).
- `ENS_COOKIE_SECURE=true` whenever users reach the portal over HTTPS (including through your
  proxy; `COOKIE_SECURE`, the older name, still works). The web tier sends the security headers
  (CSP, HSTS behind an HTTPS proxy that sets `X-Forwarded-Proto`, and others); `ENS_CSP_REPORT_ONLY=true`
  only reports CSP violations, as an escape hatch.
- Monitoring: `./portal.sh status` shows whether the API is ready (the directory answers and the
  audit log is writable). `MANAGEMENT_SERVER_PORT` adds a Prometheus endpoint (see the comments
  in `.env`); `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` writes JSON log lines.
- `ENS_SESSION_*` (idle timeout, administrators' idle timeout, absolute lifetime, sessions per
  user), `GROUP_REFRESH_INTERVAL` and `LOGIN_MAX_FAILURES` / `LOGIN_LOCKOUT_WINDOW`: session and
  sign-in controls. An `.env` from an earlier release keeps `SESSION_TTL_MINUTES=480`, which still
  applies until you replace it with `ENS_SESSION_IDLE_TIMEOUT` (new installs: `30m`).
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
- **Command line**: put it at `license/license.lic` before `./install.sh`, or install it at any
  time with `./portal.sh license /path/to/license.lic`. Either sets `ENS_LICENSE` in `.env`, which
  the API installs as it starts when no usable license is installed yet, or when it is a newer
  revision of the installed one. `./portal.sh license` also applies it at once: without the shared
  database it writes the file into the license volume (replacing any license there); with it, it
  restarts the API. With the database a different license already installed is kept: upload the
  new one on the license screen.

The license lives in the `ens-license` volume (`/var/lib/ens/directory-portal` in the api
container), or in the database with `DSP_DATABASE_URL`, together with this **installation id** (shown on the license screen; you enter it on
the ENS portal to bind a license to this installation) and the license revisions seen. Upgrades
keep the volume; removing the volumes (`docker compose down -v`, `podman volume rm`) would delete it, and a new installation id would mean
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
| `certs/server.key` | its private key, readable by uid 1001 (`chmod 644`, or `chgrp 0` + `chmod 640`; rootless Podman: `podman unshare chown 1001:0 certs/server.key` + `chmod 640`) |
| `certs/ca.pem` | CA(s) that issue user certificates |

Set `X509_ENABLED=true` (done by the installer) and adjust `X509_USER_FILTER` to map certificates to
directory users. Password sign-in keeps working unless `PASSWORD_LOGIN_ENABLED=false`.
nginx forwards the verified certificate to the API together with `X509_PROXY_SECRET`, a random
secret `install.sh` writes to `.env` (and `upgrade.sh` adds when an older `.env` has none): the
API accepts a forwarded certificate only with it, and checks it against `certs/ca.pem` too. Keep
`.env` private (mode 600).
For a test: `scripts/generate-dev-certs.sh certs` creates a CA, a server certificate and user
certificates (`certs/alice.p12`, password `password`).
Certificates are checked for revocation at sign-in by default, as DISA STIG requires: with
`ENS_X509_REVOCATION` and `ENS_X509_REVOCATION_FAIL` empty, over OCSP and then the CRL, against
`certs/ca.pem`, soft-fail. A certificate found revoked is refused; one that no responder or CRL
answers for signs in with a warning in the API log. Set `ENS_X509_REVOCATION_FAIL=closed` to
refuse those too (as the STIG expects), or `ENS_X509_REVOCATION=off` to check nothing (the API
then warns at startup). For CRLs on an air-gapped network, mount a directory of CRL files into the
API container (a compose override) and set `ENS_X509_CRL_DIR` to it.

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
log) is in the volume `directory-services-portal_portal-data` (Docker or Podman). The directory data stays
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
`license/license.lic` is set as `ENS_LICENSE` in `.env`, installed unless a different usable license,
or a newer revision of it, is installed already.
To go back, run `./portal.sh up` in the old directory and, if needed, `./portal.sh restore` the
backup.

From a package before Podman support, the upgrade keeps what the portal publishes and where:
earlier, `docker-compose.yml` published nothing and `docker-compose.publish.yml` published
`WEB_PORT`. Now `docker-compose.yml` publishes it, so `upgrade.sh` (through `install.sh`)
rewrites `COMPOSE_FILE` in `.env`: without `docker-compose.publish.yml` it uses
`docker-compose.no-publish.yml` instead of `docker-compose.yml` (still nothing published),
with it the file is dropped (the same `WEB_BIND:WEB_PORT`). `WEB_BIND`, `WEB_PORT` and
`HTTPS_PORT` are not changed: an installation keeps the address it published on (an `.env` without
`WEB_BIND` keeps every interface), and only new installations get the `127.0.0.1` default for
plain HTTP. `install.sh` warns when an `.env` publishes plain HTTP beyond this host, or does not
set `ENS_COOKIE_SECURE=true` there. It also adds `CONTAINER_ENGINE=docker`, so the portal
stays on Docker (`.env.bak-<time>` keeps the previous `.env`). Nothing needs doing by hand. To
publish an upgraded installation that published nothing, set `COMPOSE_FILE` to start with
`docker-compose.yml` and run `./portal.sh up`.

Settings a release adds are not written into an existing `.env` (except `X509_PROXY_SECRET`, and
`DEMO_MODE=true` for an `.env` with `DIRECTORY_MODE=memory`, without which the demo would not start):
they take their defaults, and older names keep working (`SESSION_TTL_MINUTES`, `COOKIE_SECURE`,
`X509_CHECK_REVOCATION`). Compare your `.env` with this package's `env.template` to adopt them.
An existing `.env` from an earlier package sets `ENS_X509_REVOCATION=off` and
`ENS_X509_REVOCATION_FAIL=closed`, which keep revocation checking off; the installer says so. Empty
both for the default live, soft-fail check.

## Notes

- Both containers run as unprivileged users on unprivileged ports and also run under an
  arbitrary UID (OpenShift `restricted`), without Linux capabilities or privilege escalation
  (`no-new-privileges`), on a read-only root file system (tmpfs where they write) and with memory
  limits (`API_MEMORY_LIMIT`, default `1536m`; `WEB_MEMORY_LIMIT`, `256m`).
- The API is never published on the host; only the web tier talks to it.
- Run one API instance: sessions and background tasks are held in its memory.
