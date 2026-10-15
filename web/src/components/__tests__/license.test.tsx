import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { LicenseStatus } from '@dsp/shared';
import { Layout } from '../Layout';
import { AuthProvider, RequireAuth } from '../../lib/auth';
import { RequireLicense } from '../../lib/license';

const admin = { username: 'admin', dn: 'uid=admin,dc=x', displayName: 'Ada Admin', groups: [], isAdmin: true, authMethod: 'password' };

function license(enabled: Partial<Record<'analysis' | 'metrics' | 'schema-transfer', boolean>>, extra: Partial<LicenseStatus> = {}): LicenseStatus {
  return {
    state: 'LICENSED',
    product: 'directory-services-portal',
    licensee: 'Acme Corp',
    expires: '2027-12-31',
    features: [
      { id: 'analysis', label: 'Data analysis', enabled: !!enabled.analysis },
      { id: 'metrics', label: 'Metrics', enabled: !!enabled.metrics },
      { id: 'schema-transfer', label: 'Schema import and export', enabled: !!enabled['schema-transfer'] },
    ],
    ...extra,
  };
}

function mockApi(status: LicenseStatus) {
  const routes: Record<string, unknown> = {
    '/api/auth/me': { user: admin },
    '/api/license': status,
    '/api/info': { mode: 'memory', baseDn: 'dc=x', connected: true, version: '1' },
    '/api/definitions': [],
    '/api/jobs': [],
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      const path = url.split('?')[0];
      return path in routes ? new Response(JSON.stringify(routes[path]), { status: 200 }) : new Response('{"error":"not found"}', { status: 404 });
    }),
  );
}

const renderAt = (path: string) =>
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route element={<RequireAuth><Layout /></RequireAuth>}>
              <Route index element={<p>home</p>} />
              <Route path="metrics" element={<RequireLicense feature="metrics"><p>metrics page</p></RequireLicense>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  );

afterEach(() => vi.unstubAllGlobals());

describe('licensed features', () => {
  it('hides navigation for features the license does not enable', async () => {
    mockApi(license({ metrics: true }));
    renderAt('/');
    const nav = await screen.findByRole('navigation');
    expect(await within(nav).findByRole('link', { name: 'Metrics' })).toBeInTheDocument();
    expect(within(nav).getByRole('link', { name: 'Audit log' })).toBeInTheDocument();
    expect(within(nav).queryByRole('link', { name: 'Data analysis' })).not.toBeInTheDocument();
    expect(screen.getByText('Licensed to Acme Corp until 2027-12-31')).toBeInTheDocument();
  });

  it('runs with core features only and says so without a license', async () => {
    mockApi({ state: 'MISSING', product: 'directory-services-portal', features: license({}).features });
    renderAt('/');
    expect(await screen.findByText('Unlicensed: core features only (no license file)')).toBeInTheDocument();
    const nav = screen.getByRole('navigation');
    expect(within(nav).getByRole('link', { name: 'Schema' })).toBeInTheDocument();
    expect(within(nav).queryByRole('link', { name: 'Metrics' })).not.toBeInTheDocument();
    expect(within(nav).queryByRole('link', { name: 'Data analysis' })).not.toBeInTheDocument();
  });

  it('explains instead of showing an unlicensed page opened directly', async () => {
    mockApi(license({ analysis: true }));
    renderAt('/metrics');
    expect(await screen.findByRole('heading', { name: 'Metrics needs a license' })).toBeInTheDocument();
    expect(screen.queryByText('metrics page')).not.toBeInTheDocument();
  });
});
