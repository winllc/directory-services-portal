import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { ApiToken, ImportResult, LicenseStatus, Webhook } from '@dsp/shared';
import { App } from '../../App';
import { AuthProvider } from '../../lib/auth';
import { ToastProvider } from '../ui';

const admin = { username: 'admin', dn: 'uid=admin,dc=x', displayName: 'Ada Admin', groups: [], isAdmin: true, authMethod: 'password' };

const license: LicenseStatus = {
  product: 'directory-portal',
  mode: 'FULL',
  state: 'VALID',
  message: 'Licensed.',
  evaluation: false,
  features: [{ id: 'directory-editor', label: 'Directory editor', description: '', enabled: true }],
  details: { installationId: 'DP-1', file: '/x' },
};

const staff = {
  id: 'def-staff',
  name: 'Staff',
  slug: 'staff',
  formId: 'form-person',
  baseDn: 'ou=people,dc=x',
  scope: 'one',
  mode: 'readwrite',
  everyoneCanRead: false,
  listAttributes: ['cn'],
  searchAttributes: ['cn'],
  titleAttribute: 'cn',
  createContainers: [],
  selfMatch: { type: 'none' },
  access: 'write',
};

const token: ApiToken = {
  id: 't1',
  name: 'HR sync',
  prefix: 'dspat_abc123',
  scopes: ['entries:read'],
  definitionIds: [],
  createdBy: 'admin',
  createdAt: '2026-10-01T00:00:00Z',
  expiresAt: '2026-12-30T00:00:00Z',
  rateLimitPerMinute: null,
  lastUsedAt: null,
  revokedAt: null,
  revokedBy: null,
  status: 'active',
};

const hook: Webhook = {
  id: 'w1',
  name: 'Ticketing',
  url: 'https://hooks.example.com/dsp',
  events: ['entry.changed'],
  definitionIds: [],
  enabled: true,
  createdBy: 'admin',
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-01T00:00:00Z',
};

type Handler = (url: URL, init?: RequestInit) => Response | undefined;

function mockApi(routes: Record<string, unknown | Handler>) {
  const all: Record<string, unknown | Handler> = {
    '/api/auth/me': { user: admin },
    '/api/info': { mode: 'memory', baseDn: 'dc=x', connected: true, version: '1' },
    '/api/license': license,
    '/api/definitions': [staff],
    '/api/jobs': [],
    '/api/admin/api-tokens/settings': {
      scopes: [
        { id: 'entries:read', description: 'Read entries' },
        { id: 'entries:write', description: 'Change entries' },
      ],
      maxTokenDays: 365,
      defaultRateLimitPerMinute: 120,
    },
    '/api/admin/webhooks/settings': { enabled: true, eventTypes: ['entry.changed', 'group.membership_changed', 'form.submitted'], allowedHosts: [], maxAttempts: 8 },
    ...routes,
  };
  const fetchMock = vi.fn(async (input: string, init?: RequestInit) => {
    const url = new URL(input, 'http://localhost');
    const route = all[url.pathname];
    if (typeof route === 'function') {
      const r = (route as Handler)(url, init);
      if (r) return r;
    } else if (url.pathname in all) {
      return new Response(JSON.stringify(route), { status: 200 });
    }
    return new Response('{"error":"not found"}', { status: 404 });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

const renderAt = (path: string) =>
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <ToastProvider>
          <AuthProvider>
            <App />
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );

afterEach(() => vi.unstubAllGlobals());

describe('integrations page', () => {
  it('creates a scoped API token, shows it once, and lists tokens', async () => {
    let body: Record<string, unknown> | undefined;
    mockApi({
      '/api/admin/api-tokens': (_u: URL, init?: RequestInit) => {
        if (init?.method === 'POST') {
          body = JSON.parse(init.body as string);
          return new Response(JSON.stringify({ token: { ...token, id: 't2', name: 'CRM' }, secret: 'dspat_SECRETVALUE' }), { status: 201 });
        }
        return new Response(JSON.stringify([token]), { status: 200 });
      },
    });
    renderAt('/admin/integrations');
    const table = await screen.findByRole('table', { name: 'Integration API tokens' });
    expect(await within(table).findByText('HR sync')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText(/^Name/), 'CRM');
    await userEvent.click(await screen.findByRole('checkbox', { name: /entries:write/ }));
    await userEvent.click(screen.getByRole('checkbox', { name: 'Staff' }));
    await userEvent.click(screen.getByRole('button', { name: /Create token/ }));
    expect(await screen.findByTestId('integration-secret')).toHaveTextContent('dspat_SECRETVALUE');
    expect(body).toMatchObject({ name: 'CRM', scopes: ['entries:read', 'entries:write'], definitionIds: ['def-staff'], expiresInDays: 90 });
  });

  it('adds a webhook and shows its secret once; the server explains a refused URL', async () => {
    let attempts = 0;
    mockApi({
      '/api/admin/webhooks': (_u: URL, init?: RequestInit) => {
        if (init?.method === 'POST') {
          attempts++;
          if (attempts === 1) {
            return new Response(
              JSON.stringify({ error: 'Invalid request', details: { url: '169.254.169.254 resolves to a cloud metadata address, which webhooks may never reach' } }),
              { status: 400 },
            );
          }
          return new Response(JSON.stringify({ webhook: hook, secret: 'whsec_SECRET' }), { status: 201 });
        }
        return new Response(JSON.stringify(attempts > 1 ? [hook] : []), { status: 200 });
      },
    });
    renderAt('/admin/integrations');
    await userEvent.click(await screen.findByRole('tab', { name: 'Webhooks' }));
    await userEvent.click(await screen.findByRole('button', { name: /New webhook/ }));
    const dialog = await screen.findByRole('dialog', { name: 'New webhook' });
    await userEvent.type(within(dialog).getByLabelText(/^Name/), 'Ticketing');
    const url = within(dialog).getByLabelText(/^URL/);
    await userEvent.clear(url);
    await userEvent.type(url, 'https://169.254.169.254/');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await within(dialog).findByText(/cloud metadata address/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await screen.findByTestId('integration-secret')).toHaveTextContent('whsec_SECRET');
  });
});

describe('directory import', () => {
  it('checks a file first, then imports it', async () => {
    const result = (dryRun: boolean): ImportResult => ({
      format: 'csv',
      mode: 'upsert',
      dryRun,
      total: 2,
      created: 1,
      updated: 0,
      unchanged: 0,
      failed: 1,
      rows: [
        { row: 2, dn: 'uid=new,ou=people,dc=x', operation: 'create', attributes: ['uid', 'cn'], error: null, details: null, ignored: [] },
        { row: 3, dn: null, operation: 'error', attributes: [], error: 'Some fields are invalid', details: { mail: '"x" is not a valid e-mail address' }, ignored: [] },
      ],
    });
    const calls: string[] = [];
    mockApi({
      '/api/definitions/staff': { definition: staff, form: { id: 'form-person', name: 'Person', objectClasses: ['inetOrgPerson'], rdnAttribute: 'uid', fields: [] } },
      '/api/directories/def-staff/entries': { entries: [], total: 0, page: 1, pageSize: 25, truncated: false },
      '/api/directories/def-staff/import': (u: URL) => {
        calls.push(u.search);
        return new Response(JSON.stringify(result(u.searchParams.get('dryRun') === 'true')), { status: 200 });
      },
    });
    renderAt('/d/staff');
    await userEvent.click(await screen.findByRole('button', { name: /Import/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Import into Staff' });
    const file = new File(['uid,cn\nnew,New\n'], 'people.csv', { type: 'text/csv' });
    await userEvent.upload(within(dialog).getByLabelText('File'), file);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Check file' }));
    expect(await within(dialog).findByText('"x" is not a valid e-mail address')).toBeInTheDocument();
    expect(calls[0]).toContain('dryRun=true');
    await userEvent.click(within(dialog).getByRole('button', { name: /Import 1 entry/ }));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1]).not.toContain('dryRun');
    expect(await within(dialog).findByText(/1 created, 0 updated, 1 not imported/)).toBeInTheDocument();
  });
});
