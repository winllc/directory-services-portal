# Directory Services Portal

A web frontend for LDAP directories with custom (non-standard) schemas. Administrators set up
**forms** for any object class, publish them as **directory definitions** scoped to a namespace
(read/write or read-only "white pages"), and grant **read/write permissions** to specific users
and groups. Every user gets a **My profile** page that shows where they appear across all
directories, and lets them edit fields marked self-service.

```
┌───────────── web (React + Vite) ─────────────┐     ┌──────── server (Express) ────────┐     ┌──────────┐
│ directories · entry CRUD · my profile        │ ──▶ │ auth (LDAP bind) · sessions       │ ──▶ │  LDAP    │
│ admin: schema · forms · definitions · perms  │ /api│ permission checks · namespace     │     │ server   │
└──────────────────────────────────────────────┘     │ scoping · validation · config DB  │     └──────────┘
                    shared/ (types, DN utils, validation, schema helpers)                          or in-memory demo
```

Browsers can't speak LDAP, so a small Node server sits in between. It talks to the directory with a
service account and enforces the portal's own permissions. The same validation code runs in the
browser and on the server.

## Quick start (demo directory, no LDAP server needed)

```bash
npm install
npm run dev            # server on :3001, web on http://localhost:5173
```

The in-memory demo directory includes standard schema, a non-standard `acme*` schema
(`acmePerson`, `acmeDevice`, `acmePartner`), about ten people, groups and devices, plus example
forms, directories and permissions. Every demo password is `password`:

| user    | what they see                                                              |
|---------|----------------------------------------------------------------------------|
| `admin` | administrator: everything, including the admin pages                        |
| `erin`  | HR group: can edit Staff Directory                                          |
| `dave`  | helpdesk group: can edit Devices                                            |
| `alice` | engineering: can view Staff, has a user grant to view Devices, self-service |
| `bob`   | can view Partners; white pages and groups only otherwise                    |
| `frank` | external partner (ou=partners)                                              |

Production build: `npm run build && npm start` (the server serves `web/dist` on port 3001).

## Connecting to a real directory

Copy `.env.example` to `.env` (or set the variables yourself) and set `DIRECTORY_MODE=ldap`:

| variable | meaning |
|---|---|
| `LDAP_URL` | `ldap://` or `ldaps://` URL; `LDAP_STARTTLS=true` upgrades plain connections |
| `LDAP_BIND_DN` / `LDAP_BIND_PASSWORD` | service account used for every read and write |
| `LDAP_BASE_DN` | default suffix (used by the DN browser) |
| `LDAP_USER_SEARCH_BASE`, `LDAP_USER_FILTER` | how to find a user at login (`{{username}}` is escaped) |
| `LDAP_USERNAME_ATTRIBUTE` | login name attribute (`uid`, `sAMAccountName`, …) |
| `LDAP_GROUP_SEARCH_BASE`, `LDAP_GROUP_FILTER` | group lookup (`{{dn}}`, `{{username}}`); `memberOf` is also used |
| `ADMIN_USERS`, `ADMIN_GROUPS` | portal administrators, separated by `;` |
| `DATA_DIR` | where `portal-config.json` (forms, definitions, grants, custom schema) is stored |

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

## Security notes
- Every LDAP filter value built from user input is escaped (RFC 4515), and DNs are built with
  RFC 4514 escaping.
- Writes are limited to the form's writable fields. The server re-checks required fields,
  single-value rules, options, formats and patterns.
- Password attributes are never returned to the browser.
- Sessions are random 256-bit IDs in `HttpOnly`, `SameSite=Strict` cookies. Every mutating
  request must carry a custom header (CSRF defence). Failed logins are throttled.
- Sessions are held in server memory. Run a single instance, or put a shared session store in
  front before scaling out.

## Development

```bash
npm test            # server (API integration, parsers) + web (component) tests
npm run typecheck
npm run build
```

Layout: `shared/` (types, DN utilities, validation, schema helpers), `server/src/ldap`
(ldapts client, in-memory directory, filter and schema parsers), `server/src/services`,
`web/src/pages` (user and admin pages), `web/src/components`.
