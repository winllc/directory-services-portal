import { randomBytes } from 'node:crypto';
import type { SessionUser } from '@dsp/shared';
import { dnEquals, escapeFilterValue, firstAttr, getAttr } from '@dsp/shared';
import type { AppConfig } from '../config';
import type { Directory } from '../ldap/directory';
import { HttpError } from '../errors';

interface Session {
  id: string;
  user: SessionUser;
  expiresAt: number;
}

const USERNAME = /^[\p{L}\p{N}._@+-]{1,128}$/u;
const MAX_FAILURES = 8;
const FAILURE_WINDOW_MS = 15 * 60 * 1000;

export class AuthService {
  private readonly sessions = new Map<string, Session>();
  private readonly failures = new Map<string, { count: number; first: number }>();
  private readonly sweeper: NodeJS.Timeout;

  constructor(
    private readonly directory: Directory,
    private readonly config: AppConfig,
  ) {
    this.sweeper = setInterval(() => this.sweep(), 60_000);
    this.sweeper.unref();
  }

  get ttlMs(): number {
    return this.config.sessionTtlMinutes * 60_000;
  }

  private sweep(): void {
    const now = Date.now();
    for (const [id, s] of this.sessions) if (s.expiresAt < now) this.sessions.delete(id);
    for (const [k, f] of this.failures) if (now - f.first > FAILURE_WINDOW_MS) this.failures.delete(k);
  }

  private checkThrottle(key: string): void {
    const f = this.failures.get(key);
    if (f && Date.now() - f.first < FAILURE_WINDOW_MS && f.count >= MAX_FAILURES) {
      throw new HttpError(429, 'Too many failed login attempts. Try again later.');
    }
  }

  private recordFailure(key: string): void {
    const now = Date.now();
    const f = this.failures.get(key);
    if (!f || now - f.first > FAILURE_WINDOW_MS) this.failures.set(key, { count: 1, first: now });
    else f.count++;
  }

  /** Find the user's DN via the service account. Returns null if not exactly one match. */
  async findUserDn(username: string): Promise<string | null> {
    const filter = this.config.userFilter.replaceAll('{{username}}', escapeFilterValue(username));
    const { entries } = await this.directory.search({
      base: this.config.userSearchBase,
      scope: 'sub',
      filter,
      attributes: ['1.1'],
      limit: 2,
    });
    return entries.length === 1 ? entries[0].dn : null;
  }

  async login(username: string, password: string, clientKey: string): Promise<{ sessionId: string; user: SessionUser }> {
    const throttleKey = `${clientKey}|${username.toLowerCase()}`;
    this.checkThrottle(throttleKey);
    if (!USERNAME.test(username) || !password) {
      this.recordFailure(throttleKey);
      throw new HttpError(401, 'Invalid username or password');
    }
    const dn = await this.findUserDn(username);
    const ok = dn ? await this.directory.authenticate(dn, password) : false;
    if (!dn || !ok) {
      this.recordFailure(throttleKey);
      throw new HttpError(401, 'Invalid username or password');
    }
    this.failures.delete(throttleKey);
    const user = await this.buildUser(dn, username);
    const sessionId = randomBytes(32).toString('base64url');
    this.sessions.set(sessionId, { id: sessionId, user, expiresAt: Date.now() + this.ttlMs });
    return { sessionId, user };
  }

  async buildUser(dn: string, username: string): Promise<SessionUser> {
    const entry = await this.directory.get(dn, ['cn', 'displayName', 'mail', this.config.usernameAttribute, 'memberOf']);
    const attrs = entry?.attributes ?? {};
    const groups = new Set<string>(getAttr(attrs, 'memberOf'));
    if (this.config.groupFilter.trim()) {
      try {
        const filter = this.config.groupFilter
          .replaceAll('{{dn}}', escapeFilterValue(dn))
          .replaceAll('{{username}}', escapeFilterValue(username));
        const res = await this.directory.search({
          base: this.config.groupSearchBase,
          scope: 'sub',
          filter,
          attributes: ['1.1'],
          limit: 1000,
        });
        for (const g of res.entries) groups.add(g.dn);
      } catch (err) {
        console.warn('[portal] group lookup failed:', (err as Error).message);
      }
    }
    const groupList = [...groups];
    const canonicalUsername = firstAttr(attrs, this.config.usernameAttribute) ?? username;
    const isAdmin =
      this.config.adminUsers.some((u) => u.toLowerCase() === canonicalUsername.toLowerCase()) ||
      this.config.adminGroups.some((g) => groupList.some((ug) => dnEquals(ug, g)));
    return {
      username: canonicalUsername,
      dn: entry?.dn ?? dn,
      displayName: firstAttr(attrs, 'displayName') ?? firstAttr(attrs, 'cn') ?? canonicalUsername,
      mail: firstAttr(attrs, 'mail'),
      groups: groupList,
      isAdmin,
    };
  }

  /** Resolve a session id; extends the sliding expiry. */
  getSession(id: string | undefined): Session | undefined {
    if (!id) return undefined;
    const s = this.sessions.get(id);
    if (!s) return undefined;
    if (s.expiresAt < Date.now()) {
      this.sessions.delete(id);
      return undefined;
    }
    s.expiresAt = Date.now() + this.ttlMs;
    return s;
  }

  logout(id: string | undefined): void {
    if (id) this.sessions.delete(id);
  }

  close(): void {
    clearInterval(this.sweeper);
  }
}
