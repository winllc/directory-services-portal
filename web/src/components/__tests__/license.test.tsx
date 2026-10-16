import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import type { LicensedFeatureId, LicenseStatus } from '@dsp/shared';
import { App } from '../../App';
import { AuthProvider } from '../../lib/auth';
import { ToastProvider } from '../ui';

const admin = { username: 'admin', dn: 'uid=admin,dc=x', displayName: 'Ada Admin', groups: [], isAdmin: true, authMethod: 'password' };
const alice = { username: 'alice', dn: 'uid=alice,dc=x', displayName: 'Alice Anderson', groups: [], isAdmin: false, authMethod: 'password' };

const ALL: LicensedFeatureId[] = [
  'directory-editor',
  'form-builder',
  'self-service-profile',
  'white-pages',
  'smart-card-sign-in',
  'metrics',
  'data-analysis',
  'schema-transfer',
];

function license(enabled: LicensedFeatureId[] = ALL, extra: Partial<LicenseStatus> = {}): LicenseStatus {
  return {
    product: 'directory-portal',
    mode: 'FULL',
    state: 'VALID',
    message: 'Licensed to Acme Corp until 2027-12-31.',
    evaluation: false,
    features: ALL.map((id) => ({ id, label: id, description: '', enabled: enabled.includes(id) })),
    details: {
      installationId: 'DP-7F3K-29QD',
      file: '/var/lib/ens/directory-portal/license.lic',
      licenseId: 'lic_1',
      revision: 1,
      licensee: 'Acme Corp',
      edition: 'subscription',
      notBefore: '2026-01-01T00:00:00Z',
      expiresAt: '2027-12-31T00:00:00Z',
      graceDays: 30,
      size: { quantity: 2500, unit: 'directory users' },
    },
    ...extra,
  };
}

const unlicensed = (): LicenseStatus => ({
  ...license([]),
  mode: 'UNLICENSED',
  state: 'MISSING',
  message: 'No license is installed. Upload one on the license screen.',
  details: { installationId: 'DP-7F3K-29QD', file: '/var/lib/ens/directory-portal/license.lic' },
});

type Handler = (method: string, body: unknown) => { status: number; body: unknown } | undefined;

function mockApi(user: object, routes: Record<string, unknown | Handler>) {
  const all: Record<string, unknown | Handler> = {
    '/api/auth/me': { user },
    '/api/info': { mode: 'memory', baseDn: 'dc=x', connected: true, version: '1' },
    '/api/definitions': [],
    '/api/jobs': [],
    '/api/me/entries': [],
    ...routes,
  };
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const path = url.split('?')[0];
    const route = all[path];
    if (typeof route === 'function') {
      const r = (route as Handler)(init?.method ?? 'GET', init?.body);
      if (r) return new Response(JSON.stringify(r.body), { status: r.status });
    } else if (path in all) {
      return new Response(JSON.stringify(route), { status: 200 });
    }
    return new Response('{"error":"not found"}', { status: 404 });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

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

describe('licensed features', () => {
  it('hides navigation for features the license does not enable', async () => {
    mockApi(admin, { '/api/license': license(['directory-editor', 'metrics']) });
    renderAt('/');
    const nav = await screen.findByRole('navigation');
    expect(await within(nav).findByRole('link', { name: 'Metrics' })).toBeInTheDocument();
    expect(within(nav).getByRole('link', { name: 'Audit log' })).toBeInTheDocument();
    expect(within(nav).getByRole('link', { name: 'License' })).toBeInTheDocument();
    expect(within(nav).queryByRole('link', { name: 'Data analysis' })).not.toBeInTheDocument();
    expect(within(nav).queryByRole('link', { name: 'Forms' })).not.toBeInTheDocument();
    expect(within(nav).queryByRole('link', { name: 'My profile' })).not.toBeInTheDocument();
    expect(screen.getByText('Licensed to Acme Corp until 2027-12-31')).toBeInTheDocument();
  });

  it('explains instead of showing an unlicensed page opened directly', async () => {
    mockApi(admin, { '/api/license': license(['directory-editor', 'data-analysis']) });
    renderAt('/metrics');
    expect(await screen.findByRole('heading', { name: 'Metrics and dashboards is not licensed' })).toBeInTheDocument();
  });
});

describe('without a license', () => {
  it('sends administrators to the license screen, with the installation id and an upload', async () => {
    mockApi(admin, { '/api/license': unlicensed() });
    renderAt('/d/staff');
    expect(await screen.findByRole('heading', { name: 'License' })).toBeInTheDocument();
    expect(screen.getByTestId('path')).toHaveTextContent('/admin/license');
    expect(screen.getByText('This portal is not licensed')).toBeInTheDocument();
    expect(screen.getByLabelText('Installation id')).toHaveTextContent('DP-7F3K-29QD');
    expect(screen.getByLabelText('License file')).toBeInTheDocument();
    // Nothing else is reachable but the audit log (not a licensed feature); signing out is.
    const nav = screen.getByRole('navigation', { name: 'Administration' });
    expect(within(nav).getAllByRole('link').map((l) => l.textContent?.trim())).toEqual(['License', 'Audit log']);
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  });

  it('tells everyone else to ask their administrator', async () => {
    mockApi(alice, { '/api/license': { ...unlicensed(), details: undefined } });
    renderAt('/');
    expect(await screen.findByRole('heading', { name: 'This application is not licensed' })).toBeInTheDocument();
    expect(screen.getByText(/ask your administrator/)).toBeInTheDocument();
    expect(screen.getByTestId('path')).toHaveTextContent('/unlicensed');
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  });

  it('follows a license that goes away while the app is open (403 license_required)', async () => {
    let status = license();
    mockApi(admin, {
      '/api/license': () => ({ status: 200, body: status }),
      '/api/definitions': () => {
        status = unlicensed();
        return { status: 403, body: { error: 'No license is installed.', code: 'license_required', state: 'MISSING' } };
      },
    });
    renderAt('/');
    await waitFor(() => expect(screen.getByTestId('path')).toHaveTextContent('/admin/license'));
    expect(await screen.findByText('This portal is not licensed')).toBeInTheDocument();
  });
});

describe('license screen', () => {
  it('lists every feature and installs an uploaded file', async () => {
    const fetchMock = mockApi(admin, {
      '/api/license': license(['directory-editor']),
      '/api/admin/license': (method: string, body: unknown) =>
        method === 'POST' && String(body).includes('BEGIN ENS LICENSE')
          ? { status: 200, body: { installed: true, message: 'Licensed to Acme Corp until 2028-01-01.', license: license() } }
          : { status: 400, body: { error: 'This is a license for certalert, not directory-portal.', code: 'license_refused' } },
    });
    renderAt('/admin/license');
    expect(await screen.findByText('2,500 directory users')).toBeInTheDocument();
    expect(screen.getAllByLabelText('Included')).toHaveLength(1);
    expect(screen.getAllByLabelText('Not included')).toHaveLength(7);

    const user = userEvent.setup();
    const input = screen.getByLabelText('License file');
    await user.upload(input, new File(['certalert'], 'other.lic', { type: 'text/plain' }));
    await user.click(screen.getByRole('button', { name: 'Install license' }));
    expect(await screen.findByText('This is a license for certalert, not directory-portal.')).toBeInTheDocument();

    await user.upload(input, new File(['-----BEGIN ENS LICENSE-----\nx\n-----END ENS LICENSE-----'], 'ok.lic', { type: 'text/plain' }));
    await user.click(screen.getByRole('button', { name: 'Install license' }));
    expect(await screen.findByText('Licensed to Acme Corp until 2028-01-01.')).toBeInTheDocument();
    const post = fetchMock.mock.calls.find(([url, init]) => url === '/api/admin/license' && init?.method === 'POST');
    expect((post?.[1]?.headers as Record<string, string>)['Content-Type']).toMatch(/^text\/plain/);
  });
});

describe('banners', () => {
  it('shows the evaluation banner to everyone', async () => {
    mockApi(alice, { '/api/license': { ...license(), evaluation: true, details: undefined } });
    renderAt('/');
    expect(await screen.findByText('Evaluation license: not for production use.')).toBeInTheDocument();
  });

  it('reminds administrators to renew, and says when the portal is read-only', async () => {
    mockApi(admin, { '/api/license': license(ALL, { renewalWarning: true, daysLeft: 12 }) });
    const first = renderAt('/');
    expect(await screen.findByText(/The license expires in 12 days\./)).toBeInTheDocument();
    first.unmount();

    mockApi(alice, {
      '/api/license': { ...license(), mode: 'READ_ONLY', state: 'EXPIRED', message: 'This license expired on 2026-01-01.', details: undefined },
    });
    renderAt('/');
    expect(await screen.findByText(/The portal is read-only: This license expired on 2026-01-01\./)).toBeInTheDocument();
    expect(screen.queryByText(/Renew it/)).not.toBeInTheDocument();
  });
});
