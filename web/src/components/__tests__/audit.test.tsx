import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import type { AuditEvent, AuditPage, AuditStatus, LicenseStatus } from '@dsp/shared';
import { App } from '../../App';
import { AuthProvider } from '../../lib/auth';
import { ToastProvider } from '../ui';

const admin = { username: 'admin', dn: 'uid=admin,dc=x', displayName: 'Ada Admin', groups: [], isAdmin: true, authMethod: 'password' };
const alice = { username: 'alice', dn: 'uid=alice,dc=x', displayName: 'Alice Anderson', groups: [], isAdmin: false, authMethod: 'password' };

const license = (mode: LicenseStatus['mode'] = 'FULL'): LicenseStatus => ({
  product: 'directory-portal',
  mode,
  state: mode === 'FULL' ? 'VALID' : 'MISSING',
  message: mode === 'FULL' ? 'Licensed.' : 'No license is installed.',
  evaluation: false,
  features: [],
  details: { installationId: 'DP-7F3K-29QD', file: '/var/lib/ens/directory-portal/license.lic' },
});

function event(seq: number, extra: Partial<AuditEvent> = {}): AuditEvent {
  return {
    schema: 'ens-audit/1',
    id: `00000000-0000-4000-8000-${String(seq).padStart(12, '0')}`,
    seq,
    time: '2026-10-16T02:10:11.123Z',
    product: 'directory-portal',
    version: '1.0.0',
    host: 'portal-1',
    installationId: 'DP-7F3K-29QD',
    category: 'authentication',
    action: 'auth.login',
    outcome: 'success',
    severity: 'info',
    actor: { type: 'user', id: 'bob', name: 'Bob Baker', roles: ['USER'] },
    source: { ip: '10.1.2.3', userAgent: 'Mozilla/5.0' },
    target: { type: 'user', id: 'uid=bob,dc=x', name: 'Bob Baker' },
    reason: null,
    details: { method: 'password' },
    requestId: null,
    session: 'abcdef0123456789',
    prevHash: '0'.repeat(64),
    hash: 'f'.repeat(64),
    ...extra,
  };
}

const page = (events: AuditEvent[], total = events.length, p = 1): AuditPage => ({
  events,
  total,
  page: p,
  pageSize: 50,
  scanned: total,
  truncated: false,
  headSeq: 200,
  oldestSeq: 1,
  oldestTime: '2026-01-01T00:00:00.000Z',
});

const status: AuditStatus = {
  outputs: [
    { output: 'stdout', enabled: true, background: false, destination: 'ens.audit logger', lastDeliveredSeq: 200, lastDeliveredAt: null, backlog: 0, lastError: null, lastErrorAt: null, failingSince: null },
    { output: 'file', enabled: false, background: false, destination: null, lastDeliveredSeq: null, lastDeliveredAt: null, backlog: null, lastError: null, lastErrorAt: null, failingSince: null },
    { output: 'splunk', enabled: true, background: true, destination: 'https://splunk:8088/services/collector/event', lastDeliveredSeq: 150, lastDeliveredAt: '2026-10-16T02:00:00.000Z', backlog: 50, lastError: 'HEC returned HTTP 503', lastErrorAt: '2026-10-16T02:09:00.000Z', failingSince: '2026-10-16T01:50:00.000Z' },
    { output: 'syslog', enabled: false, background: true, destination: null, lastDeliveredSeq: null, lastDeliveredAt: null, backlog: null, lastError: null, lastErrorAt: null, failingSince: null },
  ],
  retentionDays: 0,
  headSeq: 200,
  oldestSeq: 1,
  oldestTime: '2026-01-01T00:00:00.000Z',
  store: '/data/audit',
  writer: 'single synchronized writer',
};

type Handler = (url: URL, init?: RequestInit) => Response | undefined;

function mockApi(user: object, routes: Record<string, unknown | Handler>) {
  const all: Record<string, unknown | Handler> = {
    '/api/auth/me': { user },
    '/api/info': { mode: 'memory', baseDn: 'dc=x', connected: true, version: '1' },
    '/api/license': license(),
    '/api/definitions': [],
    '/api/jobs': [],
    '/api/me/entries': [],
    '/api/admin/audit/actions': {
      categories: ['authentication', 'authorization', 'account', 'configuration', 'data', 'license', 'system', 'audit'],
      outcomes: ['success', 'failure', 'denied'],
      severities: ['info', 'notice', 'warning', 'alert'],
      actions: [
        { action: 'auth.login', category: 'authentication' },
        { action: 'entry.update', category: 'data' },
      ],
    },
    '/api/admin/audit/status': status,
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

const auditCalls = (fetchMock: ReturnType<typeof mockApi>, path = '/api/admin/audit') =>
  fetchMock.mock.calls.map((c) => new URL(c[0] as string, 'http://localhost')).filter((u) => u.pathname === path);

function Where() {
  return <div data-testid="path">{useLocation().pathname}</div>;
}

const renderAt = (path: string) =>
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <ToastProvider>
          <AuthProvider>
            <App />
            <Where />
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );

afterEach(() => vi.unstubAllGlobals());

describe('audit log page', () => {
  it('lists events with severity, action, outcome, actor, target and source address', async () => {
    mockApi(admin, {
      '/api/admin/audit': page([
        event(2, { action: 'access.denied', category: 'authorization', outcome: 'denied', severity: 'warning', reason: 'Administrator access required' }),
        event(1),
      ]),
    });
    renderAt('/admin/audit');
    const table = await screen.findByRole('table');
    expect(await within(table).findByText('Access denied')).toBeInTheDocument();
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(within(rows[2]).getByText('Signed in')).toBeInTheDocument();
    expect(within(rows[2]).getByText('auth.login')).toBeInTheDocument();
    expect(within(rows[2]).getByText('info')).toBeInTheDocument();
    expect(within(rows[2]).getByText('bob (Bob Baker)')).toBeInTheDocument();
    expect(within(rows[2]).getByText('10.1.2.3')).toBeInTheDocument();
    expect(within(rows[2]).getByText('Success')).toBeInTheDocument();
    expect(within(rows[1]).getByText('Denied')).toBeInTheDocument();
    expect(within(rows[1]).getByText('warning')).toBeInTheDocument();
    // Local time, with UTC on hover.
    expect(within(rows[2]).getByTitle('2026-10-16T02:10:11.123Z (UTC)')).toBeInTheDocument();
  });

  it('filters and pages on the server', async () => {
    const fetchMock = mockApi(admin, {
      '/api/admin/audit': (url: URL) => new Response(JSON.stringify(page([event(Number(url.searchParams.get('page') ?? 1))], 120))),
    });
    const user = userEvent.setup();
    renderAt('/admin/audit');
    expect(await screen.findByText('120 events · page 1 of 3')).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Category'), 'authentication');
    await user.selectOptions(screen.getByLabelText('Severity'), 'warning');
    await user.type(screen.getByLabelText('Source IP'), '10.1');
    await waitFor(() => {
      const last = auditCalls(fetchMock).at(-1)!;
      expect(last.searchParams.get('category')).toBe('authentication');
      expect(last.searchParams.get('severity')).toBe('warning');
      expect(last.searchParams.get('ip')).toBe('10.1');
    });

    await user.click(screen.getByRole('button', { name: /Next/ }));
    await waitFor(() => expect(auditCalls(fetchMock).at(-1)!.searchParams.get('page')).toBe('2'));
    expect(auditCalls(fetchMock).at(-1)!.searchParams.get('pageSize')).toBe('50');
  });

  it('opens every field of an event, with its JSON, seq and hash', async () => {
    mockApi(admin, {
      '/api/admin/audit': page([event(7, { action: 'entry.update', category: 'data', details: { 'mail.old': 'a@x', 'mail.new': 'b@x', definitionId: 'def-staff' } })]),
    });
    const user = userEvent.setup();
    renderAt('/admin/audit');
    await user.click(await screen.findByText('Updated entry'));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'Updated entry · seq 7' })).toBeInTheDocument();
    expect(within(dialog).getByText('f'.repeat(64))).toBeInTheDocument();
    expect(within(dialog).getByText('a@x')).toBeInTheDocument();
    expect(within(dialog).getByText('b@x')).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Event JSON').textContent).toContain('"schema": "ens-audit/1"');
  });

  it('exports the current filter as CSV', async () => {
    const fetchMock = mockApi(admin, {
      '/api/admin/audit': page([event(1)]),
      '/api/admin/audit/export': () => new Response('seq,time\r\n1,x\r\n', { status: 200, headers: { 'X-Audit-Count': '1' } }),
    });
    const createObjectURL = vi.fn(() => 'blob:x');
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() }));
    const user = userEvent.setup();
    renderAt('/admin/audit?category=authentication&outcome=failure');
    await screen.findByText('Signed in');
    await user.click(screen.getByRole('button', { name: 'Export CSV' }));
    await waitFor(() => expect(auditCalls(fetchMock, '/api/admin/audit/export')).toHaveLength(1));
    const call = auditCalls(fetchMock, '/api/admin/audit/export')[0];
    expect(call.searchParams.get('format')).toBe('csv');
    expect(call.searchParams.get('category')).toBe('authentication');
    expect(call.searchParams.get('outcome')).toBe('failure');
    expect(await screen.findByText('Exported 1 event as CSV.')).toBeInTheDocument();
  });

  it('verifies the chain and shows the result', async () => {
    mockApi(admin, {
      '/api/admin/audit': page([]),
      '/api/admin/audit/verify': (_url: URL, init?: RequestInit) =>
        init?.method === 'POST'
          ? new Response(
              JSON.stringify({ intact: false, brokenSeq: 42, problem: 'hash', anchorSeq: 1, anchorIsGenesis: true, lastSeq: 41, checked: 41, unreadableLines: 0, message: 'Broken at seq 42: the event\'s content does not match its hash.', verifiedAt: '' }),
            )
          : undefined,
    });
    const user = userEvent.setup();
    renderAt('/admin/audit');
    await user.click(await screen.findByRole('button', { name: 'Verify integrity' }));
    expect(await screen.findByText('Broken at seq 42')).toBeInTheDocument();
  });

  it('shows delivery status per output and the retention setting', async () => {
    mockApi(admin, { '/api/admin/audit': page([]) });
    const user = userEvent.setup();
    renderAt('/admin/audit');
    await user.click(await screen.findByRole('tab', { name: 'Delivery' }));
    const table = await screen.findByRole('table', { name: 'Delivery status' });
    const splunk = within(table).getByText('Splunk HEC').closest('tr')!;
    expect(within(splunk).getByText('Failing')).toBeInTheDocument();
    expect(within(splunk).getByText('50')).toBeInTheDocument();
    expect(within(splunk).getByText('HEC returned HTTP 503')).toBeInTheDocument();
    expect(screen.getByText(/Kept forever/)).toBeInTheDocument();
  });

  it('creates an API token, shows it once, and revokes tokens', async () => {
    const tokens = [
      { id: 't1', name: 'Splunk', prefix: 'ensat_abcdef', createdBy: 'admin', createdAt: '2026-10-01T00:00:00.000Z', lastUsedAt: null, revokedAt: null, revokedBy: null, active: true },
    ];
    const fetchMock = mockApi(admin, {
      '/api/admin/audit': page([]),
      '/api/admin/audit/tokens': (_url: URL, init?: RequestInit) =>
        init?.method === 'POST'
          ? new Response(JSON.stringify({ token: { ...tokens[0], id: 't2', name: 'SIEM' }, secret: 'ensat_SECRETVALUE' }), { status: 201 })
          : new Response(JSON.stringify(tokens)),
      '/api/admin/audit/tokens/t1': () => new Response(JSON.stringify({ ...tokens[0], active: false })),
    });
    const user = userEvent.setup();
    renderAt('/admin/audit');
    await user.click(await screen.findByRole('tab', { name: 'API tokens' }));
    expect(await screen.findByText('ensat_abcdef…')).toBeInTheDocument();
    await user.type(screen.getByLabelText('New token'), 'SIEM');
    await user.click(screen.getByRole('button', { name: 'Create token' }));
    expect(await screen.findByTestId('audit-token-secret')).toHaveTextContent('ensat_SECRETVALUE');
    await user.click(screen.getByRole('button', { name: 'Done' }));
    expect(screen.queryByText('ensat_SECRETVALUE')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Revoke' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Revoke' }));
    await waitFor(() =>
      expect(fetchMock.mock.calls.some((c) => (c[0] as string).endsWith('/api/admin/audit/tokens/t1') && c[1]?.method === 'DELETE')).toBe(true),
    );
  });

  it('is for administrators only', async () => {
    mockApi(alice, { '/api/admin/audit': page([event(1)]) });
    renderAt('/admin/audit');
    expect(await screen.findByRole('heading', { name: 'Administrators only' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Audit log' })).not.toBeInTheDocument();
  });

  it('stays open to administrators while the portal is unlicensed', async () => {
    mockApi(admin, { '/api/license': license('UNLICENSED'), '/api/admin/audit': page([event(1)]) });
    renderAt('/admin/audit');
    expect(await screen.findByRole('heading', { name: 'Audit log' })).toBeInTheDocument();
    expect(screen.getByTestId('path')).toHaveTextContent('/admin/audit');
    expect(await screen.findByText('Signed in')).toBeInTheDocument();
  });
});
