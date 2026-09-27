import type { DirectoryEntry, EntryAttributes, SchemaSnapshot } from '@dsp/shared';

export type SearchScope = 'base' | 'one' | 'sub';

export interface SearchOptions {
  base: string;
  scope: SearchScope;
  filter: string;
  /** Attributes to return; empty/undefined returns all user attributes. */
  attributes?: string[];
  /** Maximum number of entries to collect before stopping (client side). */
  limit?: number;
}

export interface SearchResult {
  entries: DirectoryEntry[];
  truncated: boolean;
}

export interface ModifyChange {
  operation: 'add' | 'replace' | 'delete';
  attribute: string;
  values: string[];
}

/**
 * Abstraction over a directory server. The LDAP implementation performs every
 * operation with the configured service account; the portal enforces its own
 * per-definition permissions on top.
 */
export interface Directory {
  readonly mode: 'ldap' | 'memory';
  search(options: SearchOptions): Promise<SearchResult>;
  /** Base-scope read; resolves to null if the entry does not exist. */
  get(dn: string, attributes?: string[]): Promise<DirectoryEntry | null>;
  add(dn: string, attributes: EntryAttributes): Promise<void>;
  modify(dn: string, changes: ModifyChange[]): Promise<void>;
  /** Change the RDN of an entry (keeping its parent). Returns the new DN. */
  rename(dn: string, newRdn: string): Promise<string>;
  delete(dn: string): Promise<void>;
  /** Verify a password by binding as the given DN. */
  authenticate(dn: string, password: string): Promise<boolean>;
  readSchema(): Promise<SchemaSnapshot>;
  ping(): Promise<void>;
  close?(): Promise<void>;
}

export type DirectoryErrorCode =
  | 'NO_SUCH_OBJECT'
  | 'ALREADY_EXISTS'
  | 'INSUFFICIENT_ACCESS'
  | 'INVALID_CREDENTIALS'
  | 'SCHEMA_VIOLATION'
  | 'NOT_ALLOWED_ON_NON_LEAF'
  | 'INVALID_DN'
  | 'INVALID_FILTER'
  | 'UNAVAILABLE'
  | 'OTHER';

const STATUS: Record<DirectoryErrorCode, number> = {
  NO_SUCH_OBJECT: 404,
  ALREADY_EXISTS: 409,
  INSUFFICIENT_ACCESS: 403,
  INVALID_CREDENTIALS: 401,
  SCHEMA_VIOLATION: 400,
  NOT_ALLOWED_ON_NON_LEAF: 409,
  INVALID_DN: 400,
  INVALID_FILTER: 400,
  UNAVAILABLE: 503,
  OTHER: 502,
};

export class DirectoryError extends Error {
  readonly status: number;
  constructor(
    readonly code: DirectoryErrorCode,
    message: string,
  ) {
    super(message);
    this.name = 'DirectoryError';
    this.status = STATUS[code];
  }
}

/** Map an LDAP result code (RFC 4511) to a portal error. */
export function fromLdapResultCode(code: number, message: string): DirectoryError {
  switch (code) {
    case 32:
      return new DirectoryError('NO_SUCH_OBJECT', 'The entry does not exist');
    case 68:
      return new DirectoryError('ALREADY_EXISTS', 'An entry with that name already exists');
    case 50:
      return new DirectoryError('INSUFFICIENT_ACCESS', 'The directory refused the operation (insufficient access)');
    case 49:
      return new DirectoryError('INVALID_CREDENTIALS', 'Invalid credentials');
    case 16:
    case 17:
    case 18:
    case 19:
    case 20:
    case 21:
    case 64:
    case 65:
    case 67:
    case 69:
      return new DirectoryError('SCHEMA_VIOLATION', message || 'The directory rejected the entry (schema violation)');
    case 66:
      return new DirectoryError('NOT_ALLOWED_ON_NON_LEAF', 'The entry has children and cannot be removed or renamed');
    case 34:
      return new DirectoryError('INVALID_DN', 'Invalid distinguished name');
    case 51:
    case 52:
    case 80:
      return new DirectoryError('UNAVAILABLE', 'The directory server is unavailable');
    default:
      return new DirectoryError('OTHER', message || `Directory error (code ${code})`);
  }
}
