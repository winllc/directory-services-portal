# Directory Services Portal

A web frontend for LDAP directories with custom (non-standard) schemas. Administrators set up
**forms** for any object class, publish them as **directory definitions** scoped to a namespace
(read/write or read-only "white pages"), and grant **read/write permissions** to specific users
and groups. Every user gets a **My profile** page that shows where they appear across all
directories, and lets them edit fields marked self-service.

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

## Quick start (demo directory, no LDAP server needed)

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

Production build without Docker: `npm run build && npm start`. This builds `web/dist` and
`backend/target/directory-services-portal.jar`, then runs the API on :3001 and serves the client
on http://localhost:8080 with `vite preview`, which forwards `/api`. In a real deployment, serve
`web/dist` from any web server that routes `/api` to the API and falls back to `index.html` for
other paths. `web/nginx/templates/default.conf.template` is a ready-made example.

## Connecting to a real directory

Set the environment variables from `.env.example` (they map onto `backend/src/main/resources/application.yml`)
and set `DIRECTORY_MODE=ldap`:

| variable | meaning |
|---|---|
| `LDAP_URL` | `ldap://` or `ldaps://` URL; `LDAP_STARTTLS=true` upgrades plain connections. Certificates are verified against the JVM trust store unless `LDAP_TLS_REJECT_UNAUTHORIZED=false` |
| `LDAP_BIND_DN` / `LDAP_BIND_PASSWORD` | service account used for every read and write |
| `LDAP_BASE_DN` | default suffix (used by the DN browser) |
| `LDAP_USER_SEARCH_BASE`, `LDAP_USER_FILTER` | how to find a user at login (`{{username}}` is escaped) |
| `LDAP_USERNAME_ATTRIBUTE` | login name attribute (`uid`, `sAMAccountName`, …) |
| `LDAP_GROUP_SEARCH_BASE`, `LDAP_GROUP_FILTER` | group lookup (`{{dn}}`, `{{username}}`); `memberOf` is also used |
| `ADMIN_USERS`, `ADMIN_GROUPS` | portal administrators, separated by `;` |
| `DATA_DIR` | where `portal-config.json` (forms, definitions, grants, custom schema) and the audit log are stored |
| `AUDIT_DIR`, `AUDIT_MEMORY_EVENTS` | where the audit files go (default `$DATA_DIR/audit`), and how many recent events the audit page searches (default 10,000) |
| `PORT`, `SESSION_TTL_MINUTES`, `COOKIE_SECURE`, `FORWARD_HEADERS_STRATEGY` | HTTP settings |

Users sign in with their own directory password (the portal verifies it with an LDAP bind).
The service account needs read access to the schema and to the namespaces you expose, and write
access wherever portal users should be able to edit.

### Docker Compose

`docker compose up --build` starts three services. **None of them publishes a port on the host.**
To open the portal, either route a reverse proxy on the compose network to `web:8080`, or uncomment
the `ports` entry of the `web` service and open http://localhost:8181.

| service | image | published |
|---|---|---|
| `web` | `web/Dockerfile`: nginx (UBI 9 `nginx-126`) serving the React client on port 8080, proxying `/api` to `api` (`API_URL`) | no (uncomment `8181`) |
| `api` | `backend/Dockerfile`: the Spring Boot API (UBI 9 `openjdk-21-runtime`) on port 3001 | no (uncomment `3001` to call it directly) |
| `openldap` | `deploy/openldap`: Ubuntu's `slapd`, built locally (amd64 and arm64, incl. Apple Silicon) | no (uncomment `1389`) |

OpenLDAP is loaded with the same demo organisation, ACME schema and **default credentials as
the built-in demo**: `admin` / `password` (administrator), or `alice`, `bob`, `erin`, `dave`,
`frank` with password `password`. The example directories and permissions are seeded too.

- The `web` and `api` images are built on Red Hat Universal Base Image 9
  (`registry.access.redhat.com/ubi9/...`: `nodejs-22` and `openjdk-21` to build, `nginx-126` and
  `openjdk-21-runtime` to run). Both run as unprivileged users (uid 1001 and 185, group 0) and
  listen on unprivileged ports, so they also run under an arbitrary UID (OpenShift `restricted`).
  The demo `openldap` image stays on Ubuntu because RHEL 9 no longer ships the OpenLDAP server.

- The LDIF in `deploy/openldap/ldif` is only loaded into an empty directory. Run
  `docker compose down -v` after changing it, or if you started the stack from an older version.
- The API trusts `X-Forwarded-*` headers from the proxy (`FORWARD_HEADERS_STRATEGY=framework`; the
  `api` service is reachable only from the compose network). It therefore sees real client addresses, which login throttling relies on.
- If a container fails to start, `docker compose logs <service>` shows why.

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

### Audit log
*Admin → Audit log*

Every write goes to the directory as the portal's service account, so the directory's own logs
can't say who made a change. The portal records it instead:

| Recorded | Includes |
|---|---|
| Entry created, updated, renamed or deleted, through a directory or **My profile** | each changed attribute's values before and after. A delete keeps everything the entry held, so it can be put back by hand |
| Sign-ins and sign-outs, by password or certificate | for a failed attempt: the name that was tried or the certificate's subject, serial and issuer, and why it failed |
| Grants, directory definitions, forms and custom schema created, changed or deleted | the settings that changed, before and after |

Every event carries who did it (with their DN and how they signed in), the client address, and an
outcome: **success**, **denied** (the portal's permission checks refused it) or **failed** (the
directory or the credentials said no). Requests that fail validation are not recorded, because
nothing was attempted. A save that changes nothing is not recorded either.

- Values of password attributes (`userPassword`, `unicodePwd`, and the others the portal never
  returns to browsers) are recorded as `(redacted)`.
- The audit page filters by activity, outcome, date range, person and target. An entry's
  **History** button opens it filtered to that entry, including events from before a rename.
- Events are appended to `audit-YYYY-MM.jsonl` (one JSON object per line) and synced to disk
  before the request returns. The portal never rewrites or deletes these files; retention and
  archiving are up to you. In Docker Compose they are on the `portal-data` volume.
- Each event is also logged on the `audit` logger, so a log shipper can forward it to a SIEM.
- The page searches the newest `AUDIT_MEMORY_EVENTS` events, reloaded from the files at startup.
  Use the files for anything older.
- The change has already been made by the time it is recorded. If the audit file can't be
  written, the portal logs an error and the event still reaches the `audit` logger. The
  request is not failed.

### X.509 client-certificate sign-in
Users can sign in with a client certificate (smart cards such as CAC/PIV, or any PKI-issued user
certificate), alongside or instead of passwords. Set `X509_ENABLED=true` and choose where the
certificate comes from:

- **The web proxy terminates mutual TLS** (`X509_SOURCE=header`, recommended). Browsers connect
  to the web tier, so this is how certificate sign-in works with the separate web and API
  services. The proxy verifies the certificate and forwards it in `X509_HEADER`. The formats
  understood are PEM, URL-encoded PEM (nginx `$ssl_client_escaped_cert`, AWS ALB), base64 DER and
  Envoy `x-forwarded-client-cert`. The header is **ignored unless the connecting peer is in
  `X509_TRUSTED_PROXIES`**. The check uses the actual connection, never `X-Forwarded-For`, so
  use `FORWARD_HEADERS_STRATEGY=framework` or `none`, not `native`. The proxy must overwrite
  any incoming copy of the header; the bundled nginx configs always do. Also set
  `X509_TRUSTED_CA_FILE` so the API validates the chain itself.

  Docker Compose has this ready as an overlay: the web image's `templates-mtls` nginx config
  runs HTTPS with `ssl_verify_client optional`, so password sign-in keeps working. nginx runs
  unprivileged (uid 1001, group 0), so the mounted `server.key` must be readable by that user or
  group (for example `chgrp 0 server.key && chmod 0640 server.key`).
  ```bash
  scripts/generate-dev-certs.sh certs          # throw-away CA, server cert, admin/alice/bob .p12 (password: password)
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
- When `X509_TRUSTED_CA_FILE` is set, it must chain to one of those CAs. Revocation is also
  checked (OCSP / CRL distribution points) when `X509_CHECK_REVOCATION=true`.

It is then mapped to exactly one directory user:

| `X509_MAPPING` | how the user is found |
|---|---|
| `filter` (default) | `X509_USER_FILTER` with placeholders `{{cn}}`, `{{uid}}`, `{{email}}` (rfc822 SAN, else the emailAddress attribute), `{{upn}}` (Microsoft UPN SAN, common on smart cards), `{{subject}}`, `{{serial}}`. Values are filter-escaped. Examples: `(uid={{cn}})`, `(mail={{email}})`, `(userPrincipalName={{upn}})` |
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

## Security notes
- LDAP filters are built with the UnboundID filter API, which escapes every user-supplied value.
  Admin-configured filter templates get their values escaped with `Filter.encodeValue`. DNs are
  parsed and built with RFC 4514 escaping, and namespace checks compare parsed DNs.
- Writes are limited to the form's writable fields. The server re-checks required fields,
  single-value rules, options, formats and patterns (`FormValidator`, which mirrors the client
  rules in `shared/`).
- Password attributes are never returned to the browser. Empty passwords are rejected before
  any bind, so anonymous binds can't be used to log in.
- Certificate sign-in validates the certificate (dates, key usage, optional chain and revocation)
  before mapping it to exactly one directory user. Forwarded certificate headers are only
  honoured from trusted proxy addresses.
- The web proxy overwrites `X-Forwarded-For` (clients can't spoof their address, which login
  throttling relies on) and always replaces `X-SSL-Client-Cert`. The API is not published in
  Docker Compose, so only the proxy can reach it.
- Sessions are Spring Security `HttpSession`s in a `DSP_SESSION` cookie (`HttpOnly`,
  `SameSite=Strict`, `Secure` when `COOKIE_SECURE=true`). The session ID is rotated at login,
  and every state-changing request must carry the `X-DSP-Request` header (CSRF defence).
  `/api/admin/**` requires `ROLE_ADMIN`. Failed logins are throttled.
- Sessions are held in server memory. Run a single instance, or add Spring Session (e.g.
  Redis or JDBC) before scaling out.
- Changes and sign-ins are recorded in the [audit log](#audit-log). Treat the audit files as
  sensitive: they hold the values that were changed and the names people tried to sign in with.

## Development

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
  - `config/`: security and SPA serving
- `shared/`: TypeScript types, DN utilities, validation and schema helpers used by the client.
- `web/`: the React client (`src/pages` for user and admin pages, `src/components`), with
  `web/Dockerfile` and its nginx setup in `web/nginx/` (`nginx.conf`, the server `templates`
  and `entrypoint.sh`, which renders them at start).
- `backend/Dockerfile`: the API image. `deploy/openldap/`: the demo OpenLDAP image.
