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

Browsers can't speak LDAP, so a Spring Boot service sits in between. It talks to the directory
with a pooled service-account connection ([UnboundID LDAP SDK](https://github.com/pingidentity/ldapsdk))
and enforces the portal's own permissions. The production build is a single executable jar that
also serves the React client.

## Quick start (demo directory, no LDAP server needed)

Requirements: Java 21 and Node.js 20+. Maven is not needed; the backend ships the Maven wrapper.

```bash
npm install
npm run dev            # Spring Boot API on :3001, web on http://localhost:5173
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

Production build: `npm run build && npm start`. This builds the client, packages
`backend/target/directory-services-portal.jar` (client included) and runs it on port 3001.

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
| `DATA_DIR` | where `portal-config.json` (forms, definitions, grants, custom schema) is stored |
| `PORT`, `SESSION_TTL_MINUTES`, `COOKIE_SECURE`, `FORWARD_HEADERS_STRATEGY` | HTTP settings |

Users sign in with their own directory password (the portal verifies it with an LDAP bind).
The service account needs read access to the schema and to the namespaces you expose, and write
access wherever portal users should be able to edit.

`docker compose up --build` starts OpenLDAP with an example custom schema (`deploy/ldif`) and the
portal. Sign in as `admin` / `admin`.

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

### X.509 client-certificate sign-in
Users can sign in with a client certificate (smart cards such as CAC/PIV, or any PKI-issued user
certificate), alongside or instead of passwords. Set `X509_ENABLED=true` and choose where the
certificate comes from:

- **The portal terminates mutual TLS** (`X509_SOURCE=servlet`): run with the `mtls` profile.
  Tomcat requests a client certificate (`client-auth: want`, so password sign-in still works
  without one) and verifies it against `TLS_CLIENT_CA_FILE`.
  ```bash
  scripts/generate-dev-certs.sh certs          # throw-away CA, server cert, admin/alice/bob .p12 (password: password)
  SPRING_PROFILES_ACTIVE=mtls TLS_CERT_FILE=certs/server.pem TLS_KEY_FILE=certs/server.key \
    TLS_CLIENT_CA_FILE=certs/ca.pem java -jar backend/target/directory-services-portal.jar
  # import certs/alice.p12 into the browser, open https://localhost:8443, "Sign in with certificate"
  ```
- **A reverse proxy terminates mutual TLS** (`X509_SOURCE=header`): the proxy forwards the
  certificate in `X509_HEADER`. The formats understood are PEM, URL-encoded PEM (nginx
  `$ssl_client_escaped_cert`, AWS ALB), base64 DER and Envoy `x-forwarded-client-cert`. The
  header is **ignored unless the request comes from `X509_TRUSTED_PROXIES`**. The proxy must
  overwrite any incoming copy of the header. Also set `X509_TRUSTED_CA_FILE` so the portal
  validates the chain itself.

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
- Sessions are Spring Security `HttpSession`s in a `DSP_SESSION` cookie (`HttpOnly`,
  `SameSite=Strict`, `Secure` when `COOKIE_SECURE=true`). The session ID is rotated at login,
  and every state-changing request must carry the `X-DSP-Request` header (CSRF defence).
  `/api/admin/**` requires `ROLE_ADMIN`. Failed logins are throttled.
- Sessions are held in server memory. Run a single instance, or add Spring Session (e.g.
  Redis or JDBC) before scaling out.

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
- `web/`: the React client (`src/pages` for user and admin pages, `src/components`).
