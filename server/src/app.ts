import express, { type NextFunction, type Request, type Response } from 'express';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import type {
  DefinitionSummary,
  DirectoryDefinition,
  FormDefinition,
  PermissionGrant,
  ServerInfo,
  SessionUser,
  SubjectSearchResult,
} from '@dsp/shared';
import { escapeFilterValue, firstAttr, lintForm, rdnValue } from '@dsp/shared';
import type { AppConfig } from './config';
import type { Directory } from './ldap/directory';
import { formatAttributeType, formatObjectClass } from './ldap/schemaParser';
import { ConfigStore } from './store/configStore';
import { AuthService } from './services/authService';
import { SchemaService } from './services/schemaService';
import { assertDefinitionShape, EntryService } from './services/entryService';
import { badRequest, errorHandler, forbidden, HttpError, notFound } from './errors';
import {
  attributeTypeSchema,
  definitionInputSchema,
  dnString,
  filterString,
  formInputSchema,
  grantInputSchema,
  listQuerySchema,
  objectClassSchema,
  valuesBody,
} from './routes/validators';

export const VERSION = '0.1.0';
const COOKIE = 'dsp_session';
const CSRF_HEADER = 'x-dsp-request';

declare module 'express-serve-static-core' {
  interface Request {
    user?: SessionUser;
    sessionId?: string;
  }
}

export interface AppContext {
  config: AppConfig;
  directory: Directory;
  store: ConfigStore;
  auth: AuthService;
  schema: SchemaService;
  entries: EntryService;
}

export function createContext(config: AppConfig, directory: Directory, store: ConfigStore): AppContext {
  const schema = new SchemaService(directory, store);
  return {
    config,
    directory,
    store,
    schema,
    auth: new AuthService(directory, config),
    entries: new EntryService(directory, store, schema),
  };
}

function parseCookies(header: string | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  if (!header) return out;
  for (const part of header.split(';')) {
    const i = part.indexOf('=');
    if (i < 0) continue;
    const k = part.slice(0, i).trim();
    try {
      out[k] = decodeURIComponent(part.slice(i + 1).trim());
    } catch {
      /* ignore malformed cookie */
    }
  }
  return out;
}

const dnQuery = (req: Request): string => {
  const dn = req.query.dn;
  if (typeof dn !== 'string' || !dn.trim()) throw badRequest('Query parameter "dn" is required');
  return dnString.parse(dn);
};

const param = (req: Request, name: string): string => {
  const v = req.params[name];
  return Array.isArray(v) ? v[0] : String(v);
};

export function createApp(ctx: AppContext): express.Express {
  const { config, store, auth, schema, entries, directory } = ctx;
  const app = express();
  app.disable('x-powered-by');
  if (config.trustProxy) app.set('trust proxy', 1);

  app.use((_req, res, next) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('X-Frame-Options', 'DENY');
    res.setHeader('Referrer-Policy', 'same-origin');
    next();
  });
  app.use('/api', express.json({ limit: '1mb' }));
  app.use('/api', (_req, res, next) => {
    res.setHeader('Cache-Control', 'no-store');
    next();
  });

  // Session resolution + CSRF protection (custom header cannot be sent cross-site without CORS).
  app.use('/api', (req, _res, next) => {
    const sid = parseCookies(req.headers.cookie)[COOKIE];
    const session = auth.getSession(sid);
    if (session) {
      req.user = session.user;
      req.sessionId = session.id;
    }
    if (!['GET', 'HEAD', 'OPTIONS'].includes(req.method) && req.headers[CSRF_HEADER] !== '1') {
      next(new HttpError(403, 'Missing request verification header'));
      return;
    }
    next();
  });

  const requireAuth = (req: Request, _res: Response, next: NextFunction) => {
    if (!req.user) next(new HttpError(401, 'Authentication required'));
    else next();
  };
  const requireAdmin = (req: Request, _res: Response, next: NextFunction) => {
    if (!req.user) next(new HttpError(401, 'Authentication required'));
    else if (!req.user.isAdmin) next(forbidden('Administrator access required'));
    else next();
  };

  const summarize = (user: SessionUser, def: DirectoryDefinition): DefinitionSummary => ({
    ...def,
    access: entries.access(user, def),
    formName: store.snapshot.forms.find((f) => f.id === def.formId)?.name,
  });

  // ---------------------------------------------------------------------------
  // Public
  // ---------------------------------------------------------------------------

  app.get('/api/health', (_req, res) => {
    res.json({ ok: true });
  });

  app.get('/api/info', async (_req, res) => {
    const info: ServerInfo = {
      mode: directory.mode,
      ldapUrl: directory.mode === 'ldap' ? config.ldap.url : undefined,
      baseDn: config.baseDn,
      connected: true,
      version: VERSION,
    };
    try {
      await directory.ping();
    } catch (err) {
      info.connected = false;
      info.error = (err as Error).message;
    }
    res.json(info);
  });

  // ---------------------------------------------------------------------------
  // Auth
  // ---------------------------------------------------------------------------

  app.post('/api/auth/login', async (req, res) => {
    const body = z.object({ username: z.string().trim().min(1).max(256), password: z.string().min(1).max(1024) }).parse(req.body);
    const { sessionId, user } = await auth.login(body.username, body.password, req.ip ?? 'unknown');
    res.cookie(COOKIE, sessionId, {
      httpOnly: true,
      sameSite: 'strict',
      secure: config.cookieSecure,
      path: '/',
      maxAge: auth.ttlMs,
    });
    res.json({ user });
  });

  app.post('/api/auth/logout', (req, res) => {
    auth.logout(req.sessionId);
    res.clearCookie(COOKIE, { path: '/' });
    res.json({ ok: true });
  });

  app.get('/api/auth/me', requireAuth, (req, res) => {
    res.json({ user: req.user });
  });

  // ---------------------------------------------------------------------------
  // Directories (end-user)
  // ---------------------------------------------------------------------------

  app.get('/api/definitions', requireAuth, (req, res) => {
    const list = store.snapshot.definitions
      .map((d) => summarize(req.user!, d))
      .filter((d) => d.access !== 'none')
      .sort((a, b) => a.name.localeCompare(b.name));
    res.json(list);
  });

  app.get('/api/definitions/:id', requireAuth, (req, res) => {
    const def = entries.definition(param(req, 'id'));
    const summary = summarize(req.user!, def);
    if (summary.access === 'none') throw forbidden('You do not have access to this directory');
    res.json({ definition: summary, form: entries.form(def) });
  });

  app.get('/api/directories/:id/entries', requireAuth, async (req, res) => {
    const def = entries.definition(param(req, 'id'));
    res.json(await entries.list(req.user!, def, listQuerySchema.parse(req.query)));
  });

  app.get('/api/directories/:id/entry', requireAuth, async (req, res) => {
    const def = entries.definition(param(req, 'id'));
    res.json(await entries.get(req.user!, def, dnQuery(req)));
  });

  app.post('/api/directories/:id/entries', requireAuth, async (req, res) => {
    const def = entries.definition(param(req, 'id'));
    const body = z.object({ parentDn: z.string().optional(), values: valuesBody }).parse(req.body);
    res.status(201).json(await entries.create(req.user!, def, body));
  });

  app.put('/api/directories/:id/entry', requireAuth, async (req, res) => {
    const def = entries.definition(param(req, 'id'));
    const body = z.object({ values: valuesBody }).parse(req.body);
    res.json(await entries.update(req.user!, def, dnQuery(req), body.values));
  });

  app.delete('/api/directories/:id/entry', requireAuth, async (req, res) => {
    const def = entries.definition(param(req, 'id'));
    await entries.remove(req.user!, def, dnQuery(req));
    res.status(204).end();
  });

  app.get('/api/directories/:id/options/:fieldId', requireAuth, async (req, res) => {
    const def = entries.definition(param(req, 'id'));
    res.json(await entries.options(req.user!, def, param(req, 'fieldId')));
  });

  // ---------------------------------------------------------------------------
  // Self service ("My profile")
  // ---------------------------------------------------------------------------

  app.get('/api/me/entries', requireAuth, async (req, res) => {
    res.json(await entries.selfOverview(req.user!, (d) => summarize(req.user!, d)));
  });

  app.put('/api/me/:id/entry', requireAuth, async (req, res) => {
    const def = entries.definition(param(req, 'id'));
    const body = z.object({ values: valuesBody }).parse(req.body);
    res.json(await entries.update(req.user!, def, dnQuery(req), body.values, 'self'));
  });

  // ---------------------------------------------------------------------------
  // Admin: schema
  // ---------------------------------------------------------------------------

  const admin = express.Router();
  admin.use(requireAdmin);

  admin.get('/schema', async (_req, res) => {
    const index = await schema.mergedIndex();
    const server = store.snapshot.schemaCache;
    res.json({
      merged: index.schema,
      custom: schema.custom(),
      server: server
        ? { fetchedAt: server.fetchedAt, subschemaDn: server.subschemaDn, attributeTypes: server.attributeTypes.length, objectClasses: server.objectClasses.length }
        : null,
    });
  });

  admin.post('/schema/refresh', async (_req, res) => {
    const s = await schema.refresh();
    res.json({ fetchedAt: s.fetchedAt, attributeTypes: s.attributeTypes.length, objectClasses: s.objectClasses.length });
  });

  admin.post('/schema/import', async (req, res) => {
    const { text } = z.object({ text: z.string().min(1).max(2_000_000) }).parse(req.body);
    res.json(await schema.importText(text));
  });

  admin.get('/schema/export', (_req, res) => {
    const custom = schema.custom();
    const lines = [
      '# Custom schema exported from Directory Services Portal',
      ...custom.attributeTypes.map((a) => `attributetype ${formatAttributeType(a)}`),
      ...custom.objectClasses.map((o) => `objectclass ${formatObjectClass(o)}`),
    ];
    res.type('text/plain').send(lines.join('\n') + '\n');
  });

  admin.put('/schema/attribute-types', async (req, res) => {
    const body = z.object({ definition: attributeTypeSchema, originalOid: z.string().optional() }).parse(req.body);
    res.json(await schema.upsertAttributeType({ ...body.definition, source: 'custom' }, body.originalOid));
  });

  admin.delete('/schema/attribute-types/:oid', async (req, res) => {
    await schema.deleteCustom('attributeTypes', param(req, 'oid'));
    res.status(204).end();
  });

  admin.put('/schema/object-classes', async (req, res) => {
    const body = z.object({ definition: objectClassSchema, originalOid: z.string().optional() }).parse(req.body);
    res.json(await schema.upsertObjectClass({ ...body.definition, source: 'custom' }, body.originalOid));
  });

  admin.delete('/schema/object-classes/:oid', async (req, res) => {
    await schema.deleteCustom('objectClasses', param(req, 'oid'));
    res.status(204).end();
  });

  // ---------------------------------------------------------------------------
  // Admin: forms
  // ---------------------------------------------------------------------------

  const checkForm = async (input: z.infer<typeof formInputSchema>) => {
    const lint = lintForm(input, await schema.mergedIndex());
    if (lint.errors.length) throw badRequest(lint.errors[0], Object.fromEntries(lint.errors.map((e, i) => [`form.${i}`, e])));
    return lint;
  };

  admin.get('/forms', (_req, res) => {
    res.json(store.snapshot.forms);
  });

  admin.get('/forms/:id', (req, res) => {
    const form = store.snapshot.forms.find((f) => f.id === param(req, 'id'));
    if (!form) throw notFound('Form not found');
    res.json(form);
  });

  admin.post('/forms/lint', async (req, res) => {
    const input = formInputSchema.parse(req.body);
    res.json(lintForm(input, await schema.mergedIndex()));
  });

  admin.post('/forms', async (req, res) => {
    const input = formInputSchema.parse(req.body);
    const lint = await checkForm(input);
    const now = new Date().toISOString();
    const form: FormDefinition = { ...input, id: `form-${randomUUID()}`, createdAt: now, updatedAt: now };
    await store.update((d) => {
      d.forms.push(form);
    });
    res.status(201).json({ form, warnings: lint.warnings });
  });

  admin.put('/forms/:id', async (req, res) => {
    const id = param(req, 'id');
    const input = formInputSchema.parse(req.body);
    const lint = await checkForm(input);
    const form = await store.update((d) => {
      const i = d.forms.findIndex((f) => f.id === id);
      if (i < 0) throw notFound('Form not found');
      d.forms[i] = { ...d.forms[i], ...input, id, updatedAt: new Date().toISOString() };
      return d.forms[i];
    });
    res.json({ form, warnings: lint.warnings });
  });

  admin.delete('/forms/:id', async (req, res) => {
    const id = param(req, 'id');
    await store.update((d) => {
      const used = d.definitions.filter((x) => x.formId === id);
      if (used.length) throw new HttpError(409, `Form is used by: ${used.map((u) => u.name).join(', ')}`);
      const i = d.forms.findIndex((f) => f.id === id);
      if (i < 0) throw notFound('Form not found');
      d.forms.splice(i, 1);
    });
    res.status(204).end();
  });

  // ---------------------------------------------------------------------------
  // Admin: directory definitions
  // ---------------------------------------------------------------------------

  const checkDefinition = (input: z.infer<typeof definitionInputSchema>, id?: string) => {
    if (!store.snapshot.forms.some((f) => f.id === input.formId)) throw badRequest('Unknown form', { formId: 'Unknown form' });
    if (store.snapshot.definitions.some((d) => d.slug === input.slug && d.id !== id)) {
      throw badRequest('Another directory already uses that slug', { slug: 'Already in use' });
    }
    const def = { ...input, filter: input.filter || undefined, id: id ?? '', createdAt: '', updatedAt: '' } as DirectoryDefinition;
    assertDefinitionShape(def);
    return def;
  };

  admin.get('/definitions', (_req, res) => {
    res.json(store.snapshot.definitions);
  });

  admin.post('/definitions', async (req, res) => {
    const input = definitionInputSchema.parse(req.body);
    const now = new Date().toISOString();
    const def: DirectoryDefinition = { ...checkDefinition(input), id: `def-${randomUUID()}`, createdAt: now, updatedAt: now };
    await store.update((d) => {
      d.definitions.push(def);
    });
    res.status(201).json(def);
  });

  admin.put('/definitions/:id', async (req, res) => {
    const id = param(req, 'id');
    const input = definitionInputSchema.parse(req.body);
    const checked = checkDefinition(input, id);
    const def = await store.update((d) => {
      const i = d.definitions.findIndex((x) => x.id === id);
      if (i < 0) throw notFound('Definition not found');
      d.definitions[i] = { ...checked, id, createdAt: d.definitions[i].createdAt, updatedAt: new Date().toISOString() };
      return d.definitions[i];
    });
    res.json(def);
  });

  admin.delete('/definitions/:id', async (req, res) => {
    const id = param(req, 'id');
    await store.update((d) => {
      const i = d.definitions.findIndex((x) => x.id === id);
      if (i < 0) throw notFound('Definition not found');
      d.definitions.splice(i, 1);
      d.grants = d.grants.filter((g) => g.definitionId !== id);
    });
    res.status(204).end();
  });

  /** Preview which entries a base/scope/filter combination selects. */
  admin.post('/preview', async (req, res) => {
    const body = z
      .object({ baseDn: dnString, scope: z.enum(['base', 'one', 'sub']), filter: filterString, attributes: z.array(z.string()).max(20).optional() })
      .parse(req.body);
    const result = await directory.search({
      base: body.baseDn,
      scope: body.scope,
      filter: body.filter || '(objectClass=*)',
      attributes: body.attributes,
      limit: 25,
    });
    res.json({ entries: result.entries.map((e) => ({ dn: e.dn, attributes: e.attributes })), truncated: result.truncated });
  });

  /** One-level browse of the directory tree for DN pickers. */
  admin.get('/browse', async (req, res) => {
    const base = typeof req.query.base === 'string' && req.query.base ? dnString.parse(req.query.base) : config.baseDn;
    const result = await directory.search({ base, scope: 'one', filter: '(objectClass=*)', attributes: ['objectClass'], limit: 500 });
    res.json({
      base,
      children: result.entries
        .map((e) => ({ dn: e.dn, name: rdnValue(e.dn), objectClasses: e.attributes['objectclass'] ?? [] }))
        .sort((a, b) => a.name.localeCompare(b.name)),
      truncated: result.truncated,
    });
  });

  // ---------------------------------------------------------------------------
  // Admin: permissions
  // ---------------------------------------------------------------------------

  admin.get('/grants', (req, res) => {
    const defId = typeof req.query.definitionId === 'string' ? req.query.definitionId : undefined;
    res.json(store.snapshot.grants.filter((g) => !defId || g.definitionId === defId));
  });

  admin.post('/grants', async (req, res) => {
    const input = grantInputSchema.parse(req.body);
    const def = entries.definition(input.definitionId);
    if (input.subjectType === 'group') dnString.parse(input.subject);
    if (def.mode === 'readonly' && input.access === 'write') {
      throw badRequest('Read-only directories cannot grant write access', { access: 'Read only directory' });
    }
    const grant = await store.update((d) => {
      const existing = d.grants.find(
        (g) =>
          g.definitionId === input.definitionId &&
          g.subjectType === input.subjectType &&
          g.subject.toLowerCase() === input.subject.toLowerCase(),
      );
      if (existing) {
        existing.access = input.access;
        existing.subjectLabel = input.subjectLabel ?? existing.subjectLabel;
        return existing;
      }
      const g: PermissionGrant = { ...input, id: `grant-${randomUUID()}`, createdAt: new Date().toISOString() };
      d.grants.push(g);
      return g;
    });
    res.status(201).json(grant);
  });

  admin.delete('/grants/:id', async (req, res) => {
    const id = param(req, 'id');
    await store.update((d) => {
      const i = d.grants.findIndex((g) => g.id === id);
      if (i < 0) throw notFound('Grant not found');
      d.grants.splice(i, 1);
    });
    res.status(204).end();
  });

  /** Search users and groups to grant permissions to. */
  admin.get('/subjects', async (req, res) => {
    const q = typeof req.query.q === 'string' ? req.query.q.trim().slice(0, 100) : '';
    const type = req.query.type === 'group' ? 'group' : req.query.type === 'user' ? 'user' : undefined;
    const t = escapeFilterValue(q);
    const out: SubjectSearchResult[] = [];
    if (type !== 'group') {
      const ua = config.usernameAttribute;
      const userFilter = config.userFilter.replaceAll('{{username}}', q ? `*${t}*` : '*');
      const filter = q ? `(|${userFilter}(&${config.userFilter.replaceAll('{{username}}', '*')}(|(cn=*${t}*)(mail=*${t}*))))` : userFilter;
      const r = await directory.search({ base: config.userSearchBase, scope: 'sub', filter, attributes: [ua, 'cn', 'displayName'], limit: 25 });
      for (const e of r.entries) {
        const id = firstAttr(e.attributes, ua);
        if (!id) continue;
        const name = firstAttr(e.attributes, 'displayName') ?? firstAttr(e.attributes, 'cn') ?? id;
        out.push({ type: 'user', id, label: `${name} (${id})`, dn: e.dn });
      }
    }
    if (type !== 'user') {
      const filter = `(&(|(objectClass=groupOfNames)(objectClass=groupOfUniqueNames)(objectClass=group)(objectClass=posixGroup))${q ? `(cn=*${t}*)` : ''})`;
      const r = await directory.search({ base: config.groupSearchBase, scope: 'sub', filter, attributes: ['cn', 'description'], limit: 25 });
      for (const e of r.entries) {
        out.push({ type: 'group', id: e.dn, label: firstAttr(e.attributes, 'cn') ?? rdnValue(e.dn), dn: e.dn });
      }
    }
    res.json(out);
  });

  app.use('/api/admin', admin);

  app.use('/api', (_req, _res, next) => next(notFound('Unknown API endpoint')));
  app.use('/api', errorHandler);

  // ---------------------------------------------------------------------------
  // Static web client (production)
  // ---------------------------------------------------------------------------
  const staticDir = config.staticDir ?? path.resolve(process.cwd(), '../web/dist');
  if (existsSync(path.join(staticDir, 'index.html'))) {
    app.use(express.static(staticDir, { index: false, maxAge: '1h' }));
    app.get(/^(?!\/api\/).*/, (_req, res) => {
      res.sendFile(path.join(staticDir, 'index.html'));
    });
  }
  app.use(errorHandler);
  return app;
}
