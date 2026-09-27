import path from 'node:path';

export interface AppConfig {
  port: number;
  host: string;
  /** "memory" runs against the bundled in-memory demo directory. */
  directoryMode: 'ldap' | 'memory';
  ldap: {
    url: string;
    bindDn: string;
    bindPassword: string;
    startTls: boolean;
    tlsRejectUnauthorized: boolean;
    timeoutMs: number;
  };
  baseDn: string;
  /** Where to look for users when logging in. */
  userSearchBase: string;
  /** Filter used to find a user; `{{username}}` is replaced with the escaped login name. */
  userFilter: string;
  /** Attribute holding the login name (used for display and permission grants). */
  usernameAttribute: string;
  groupSearchBase: string;
  /** Filter used to find groups a user belongs to; `{{dn}}` / `{{username}}` placeholders. */
  groupFilter: string;
  adminUsers: string[];
  adminGroups: string[];
  dataDir: string;
  sessionTtlMinutes: number;
  cookieSecure: boolean;
  trustProxy: boolean;
  staticDir?: string;
  /** Seed demo forms/definitions when the config store is empty. */
  seedDemoConfig: boolean;
}

const list = (v: string | undefined): string[] =>
  (v ?? '')
    .split(/;|\n/)
    .map((s) => s.trim())
    .filter(Boolean);

const bool = (v: string | undefined, dflt: boolean): boolean =>
  v === undefined || v === '' ? dflt : ['1', 'true', 'yes', 'on'].includes(v.toLowerCase());

export function loadConfig(env: NodeJS.ProcessEnv = process.env): AppConfig {
  const directoryMode = (env.DIRECTORY_MODE ?? (env.LDAP_URL ? 'ldap' : 'memory')) as AppConfig['directoryMode'];
  if (directoryMode !== 'ldap' && directoryMode !== 'memory') {
    throw new Error(`DIRECTORY_MODE must be "ldap" or "memory" (got "${directoryMode}")`);
  }
  const baseDn = env.LDAP_BASE_DN ?? 'dc=example,dc=com';
  const isMemory = directoryMode === 'memory';
  return {
    port: Number(env.PORT ?? 3001),
    host: env.HOST ?? '0.0.0.0',
    directoryMode,
    ldap: {
      url: env.LDAP_URL ?? 'ldap://localhost:389',
      bindDn: env.LDAP_BIND_DN ?? '',
      bindPassword: env.LDAP_BIND_PASSWORD ?? '',
      startTls: bool(env.LDAP_STARTTLS, false),
      tlsRejectUnauthorized: bool(env.LDAP_TLS_REJECT_UNAUTHORIZED, true),
      timeoutMs: Number(env.LDAP_TIMEOUT_MS ?? 10000),
    },
    baseDn,
    userSearchBase: env.LDAP_USER_SEARCH_BASE ?? baseDn,
    userFilter: env.LDAP_USER_FILTER ?? '(&(objectClass=inetOrgPerson)(uid={{username}}))',
    usernameAttribute: env.LDAP_USERNAME_ATTRIBUTE ?? 'uid',
    groupSearchBase: env.LDAP_GROUP_SEARCH_BASE ?? baseDn,
    groupFilter: env.LDAP_GROUP_FILTER ?? '(|(member={{dn}})(uniqueMember={{dn}}))',
    adminUsers: list(env.ADMIN_USERS ?? (isMemory ? 'admin' : '')),
    adminGroups: list(env.ADMIN_GROUPS ?? (isMemory ? `cn=directory-admins,ou=groups,${baseDn}` : '')),
    dataDir: path.resolve(env.DATA_DIR ?? path.join(process.cwd(), 'data')),
    sessionTtlMinutes: Number(env.SESSION_TTL_MINUTES ?? 480),
    cookieSecure: bool(env.COOKIE_SECURE, env.NODE_ENV === 'production'),
    trustProxy: bool(env.TRUST_PROXY, false),
    staticDir: env.STATIC_DIR,
    seedDemoConfig: bool(env.SEED_DEMO_CONFIG, isMemory),
  };
}
