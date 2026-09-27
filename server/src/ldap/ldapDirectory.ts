import { Attribute, Change, Client, type Entry } from 'ldapts';
import type { DirectoryEntry, EntryAttributes, SchemaSnapshot } from '@dsp/shared';
import { parentDn } from '@dsp/shared';
import {
  DirectoryError,
  fromLdapResultCode,
  type Directory,
  type ModifyChange,
  type SearchOptions,
  type SearchResult,
} from './directory';
import { parseAttributeType, parseObjectClass } from './schemaParser';

export interface LdapConnectionConfig {
  url: string;
  bindDn: string;
  bindPassword: string;
  startTls: boolean;
  tlsRejectUnauthorized: boolean;
  timeoutMs: number;
}

const BINARY_ATTRIBUTES = ['jpegPhoto', 'thumbnailPhoto', 'userCertificate', 'objectGUID', 'objectSid', 'photo'];

function toError(err: unknown): DirectoryError {
  if (err instanceof DirectoryError) return err;
  const e = err as { code?: unknown; message?: string };
  if (typeof e?.code === 'number') return fromLdapResultCode(e.code, e.message ?? '');
  const msg = e?.message ?? String(err);
  if (/ECONNREFUSED|ENOTFOUND|ETIMEDOUT|EHOSTUNREACH|timeout|Connection closed|socket/i.test(msg)) {
    return new DirectoryError('UNAVAILABLE', `Directory server unavailable: ${msg}`);
  }
  return new DirectoryError('OTHER', msg);
}

export function normalizeLdapEntry(entry: Entry): DirectoryEntry {
  const attributes: EntryAttributes = {};
  for (const [key, raw] of Object.entries(entry)) {
    if (key === 'dn') continue;
    const list = Array.isArray(raw) ? raw : [raw];
    const values = list.map((v) => (Buffer.isBuffer(v) ? v.toString('base64') : String(v)));
    const k = key.toLowerCase();
    attributes[k] = [...(attributes[k] ?? []), ...values];
  }
  return { dn: entry.dn, attributes };
}

export class LdapDirectory implements Directory {
  readonly mode = 'ldap' as const;

  constructor(private readonly config: LdapConnectionConfig) {}

  private newClient(): Client {
    const secure = this.config.url.startsWith('ldaps://') || this.config.startTls;
    return new Client({
      url: this.config.url,
      timeout: this.config.timeoutMs,
      connectTimeout: this.config.timeoutMs,
      tlsOptions: secure ? { rejectUnauthorized: this.config.tlsRejectUnauthorized } : undefined,
    });
  }

  /** Run an operation on a freshly bound service-account connection. */
  private async withClient<T>(fn: (client: Client) => Promise<T>): Promise<T> {
    const client = this.newClient();
    try {
      if (this.config.startTls && !this.config.url.startsWith('ldaps://')) {
        await client.startTLS({ rejectUnauthorized: this.config.tlsRejectUnauthorized });
      }
      if (this.config.bindDn) await client.bind(this.config.bindDn, this.config.bindPassword);
      return await fn(client);
    } catch (err) {
      throw toError(err);
    } finally {
      await client.unbind().catch(() => undefined);
    }
  }

  async search(options: SearchOptions): Promise<SearchResult> {
    const limit = options.limit ?? 1000;
    return this.withClient(async (client) => {
      const entries: DirectoryEntry[] = [];
      let truncated = false;
      try {
        const pages = client.searchPaginated(options.base, {
          scope: options.scope,
          filter: options.filter,
          attributes: options.attributes?.length ? options.attributes : undefined,
          explicitBufferAttributes: BINARY_ATTRIBUTES,
          paged: { pageSize: Math.min(500, limit + 1) },
        });
        for await (const page of pages) {
          for (const e of page.searchEntries) {
            if (entries.length >= limit) {
              truncated = true;
              break;
            }
            entries.push(normalizeLdapEntry(e));
          }
          if (truncated) break;
        }
      } catch (err) {
        const code = (err as { code?: number }).code;
        // sizeLimitExceeded: server-side limit hit, return what we have.
        if (code === 4) truncated = true;
        else throw err;
      }
      return { entries, truncated };
    });
  }

  async get(dn: string, attributes?: string[]): Promise<DirectoryEntry | null> {
    try {
      const res = await this.withClient((client) =>
        client.search(dn, {
          scope: 'base',
          filter: '(objectClass=*)',
          attributes: attributes?.length ? attributes : undefined,
          explicitBufferAttributes: BINARY_ATTRIBUTES,
        }),
      );
      return res.searchEntries[0] ? normalizeLdapEntry(res.searchEntries[0]) : null;
    } catch (err) {
      if (err instanceof DirectoryError && err.code === 'NO_SUCH_OBJECT') return null;
      throw err;
    }
  }

  async add(dn: string, attributes: EntryAttributes): Promise<void> {
    const attrs: Record<string, string[]> = {};
    for (const [k, v] of Object.entries(attributes)) if (v.length) attrs[k] = v;
    await this.withClient((client) => client.add(dn, attrs));
  }

  async modify(dn: string, changes: ModifyChange[]): Promise<void> {
    if (!changes.length) return;
    await this.withClient((client) =>
      client.modify(
        dn,
        changes.map(
          (c) =>
            new Change({
              operation: c.operation,
              modification: new Attribute({ type: c.attribute, values: c.values }),
            }),
        ),
      ),
    );
  }

  async rename(dn: string, newRdn: string): Promise<string> {
    const parent = parentDn(dn);
    const newDn = parent ? `${newRdn},${parent}` : newRdn;
    await this.withClient((client) => client.modifyDN(dn, newDn));
    return newDn;
  }

  async delete(dn: string): Promise<void> {
    await this.withClient((client) => client.del(dn));
  }

  async authenticate(dn: string, password: string): Promise<boolean> {
    // Never allow empty passwords: many servers treat them as anonymous (unauthenticated) binds.
    if (!dn || !password) return false;
    const client = this.newClient();
    try {
      if (this.config.startTls && !this.config.url.startsWith('ldaps://')) {
        await client.startTLS({ rejectUnauthorized: this.config.tlsRejectUnauthorized });
      }
      await client.bind(dn, password);
      return true;
    } catch (err) {
      const e = toError(err);
      if (e.code === 'INVALID_CREDENTIALS' || e.code === 'NO_SUCH_OBJECT') return false;
      throw e;
    } finally {
      await client.unbind().catch(() => undefined);
    }
  }

  async readSchema(): Promise<SchemaSnapshot> {
    return this.withClient(async (client) => {
      const root = await client.search('', {
        scope: 'base',
        filter: '(objectClass=*)',
        attributes: ['subschemaSubentry'],
      });
      const raw = root.searchEntries[0]?.subschemaSubentry;
      const subschemaDn = (Array.isArray(raw) ? raw[0] : raw)?.toString() || 'cn=Subschema';
      const res = await client.search(subschemaDn, {
        scope: 'base',
        filter: '(objectClass=*)',
        attributes: ['attributeTypes', 'objectClasses'],
      });
      const entry = res.searchEntries[0];
      if (!entry) throw new DirectoryError('OTHER', `Subschema entry ${subschemaDn} not found`);
      const list = (v: unknown): string[] =>
        v === undefined ? [] : (Array.isArray(v) ? v : [v]).map((x) => String(x));
      const find = (name: string) =>
        Object.entries(entry).find(([k]) => k.toLowerCase() === name.toLowerCase())?.[1];

      const attributeTypes = list(find('attributeTypes')).flatMap((s) => {
        try {
          return [parseAttributeType(s, 'server')];
        } catch {
          return [];
        }
      });
      const objectClasses = list(find('objectClasses')).flatMap((s) => {
        try {
          return [parseObjectClass(s, 'server')];
        } catch {
          return [];
        }
      });
      return { attributeTypes, objectClasses, subschemaDn, fetchedAt: new Date().toISOString() };
    });
  }

  async ping(): Promise<void> {
    await this.withClient((client) =>
      client.search('', { scope: 'base', filter: '(objectClass=*)', attributes: ['namingContexts'] }),
    );
  }
}
