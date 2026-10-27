import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { PermissionGrant } from '@dsp/shared';
import { App } from '../../App';
import { AuthProvider } from '../../lib/auth';
import { ToastProvider } from '../ui';

/*
 * Granting access (who may change what) through the Permissions page: the grant the page sends is
 * the subject the administrator picked, with the access level they chose, and a white-pages
 * directory can only ever be granted view access.
 */

const B = 'dc=example,dc=com';
const HR = `cn=hr,ou=groups,${B}`;
const admin = { username: 'admin', dn: `uid=admin,ou=people,${B}`, displayName: 'Ada Admin', groups: [], isAdmin: true, authMethod: 'password' };
const features = ['directory-editor', 'self-service-profile', 'form-builder', 'metrics', 'data-analysis'].map((id) => ({ id, enabled: true }));

const def = (id: string, name: string, mode: 'readwrite' | 'readonly') => ({
  id,
  slug: id.replace('def-', ''),
  name,
  formId: 'form',
  baseDn: B,
  scope: 'sub',
  mode,
  everyoneCanRead: mode === 'readonly',
  listAttributes: [],
  searchAttributes: [],
  titleAttribute: 'cn',
  createContainers: [],
  selfMatch: { type: 'none' },
  createdAt: '',
  updatedAt: '',
});

function server() {
  const grants: PermissionGrant[] = [
    { id: 'g-bob', definitionId: 'def-devices', subjectType: 'user', subject: 'bob', subjectLabel: 'Bob Baker (bob)', access: 'write', createdAt: '' },
  ];
  const sent: { method: string; url: string; body: unknown }[] = [];
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      const path = url.split('?')[0];
      const body = init?.body ? JSON.parse(String(init.body)) : undefined;
      if (method !== 'GET') sent.push({ method, url, body });
      if (path === '/api/auth/me') return json({ user: admin, consentRequired: false });
      if (path === '/api/info') return json({ mode: 'memory', connected: true, version: '1' });
      if (path === '/api/license') return json({ mode: 'FULL', state: 'VALID', message: '', evaluation: false, features });
      if (path === '/api/admin/definitions') return json([def('def-devices', 'Devices', 'readwrite'), def('def-whitepages', 'White pages', 'readonly')]);
      if (path === '/api/admin/grants' && method === 'GET') return json(grants);
      if (path === '/api/admin/grants' && method === 'POST') {
        const g = body as PermissionGrant;
        const existing = grants.findIndex((x) => x.definitionId === g.definitionId && x.subjectType === g.subjectType && x.subject === g.subject);
        const saved = { ...g, id: existing >= 0 ? grants[existing].id : `g-${grants.length + 1}`, createdAt: '' };
        if (existing >= 0) grants[existing] = saved;
        else grants.push(saved);
        return json(saved, 201);
      }
      if (path.startsWith('/api/admin/grants/') && method === 'DELETE') {
        grants.splice(grants.findIndex((g) => g.id === path.split('/').pop()), 1);
        return new Response(null, { status: 204 });
      }
      if (path === '/api/admin/subjects') {
        return json([
          { type: 'group', id: HR, label: 'hr', dn: HR },
          { type: 'group', id: `cn=helpdesk,ou=groups,${B}`, label: 'helpdesk', dn: `cn=helpdesk,ou=groups,${B}` },
        ]);
      }
      return json([]);
    }),
  );
  return { sent, grants };
}

function renderAt(path: string) {
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
}

afterEach(() => vi.unstubAllGlobals());

describe('permissions page', () => {
  it('grants the picked group the chosen access, changes a grant and revokes one', async () => {
    const u = userEvent.setup();
    const api = server();
    renderAt('/admin/permissions?definition=def-devices');

    expect(await screen.findByText('Bob Baker (bob)')).toBeInTheDocument();
    await u.click(screen.getByRole('radio', { name: /Group/ }));
    await u.type(screen.getByLabelText('Search groups'), 'h');
    await u.click(await screen.findByRole('button', { name: /^Group hr/ }));
    await u.selectOptions(screen.getByLabelText('Access level'), 'write');
    await u.click(screen.getByRole('button', { name: 'Grant access' }));

    expect(await screen.findByText('Granted write access to hr')).toBeInTheDocument();
    expect(api.sent).toEqual([
      { method: 'POST', url: '/api/admin/grants', body: { definitionId: 'def-devices', subjectType: 'group', subject: HR, subjectLabel: 'hr', access: 'write' } },
    ]);

    // Lowering bob's access updates his grant (same subject), it doesn't add another.
    await u.selectOptions(screen.getByLabelText('Access for Bob Baker (bob)'), 'read');
    expect(await screen.findByText('Access updated')).toBeInTheDocument();
    expect(api.sent[1]).toEqual({
      method: 'POST',
      url: '/api/admin/grants',
      body: { definitionId: 'def-devices', subjectType: 'user', subject: 'bob', subjectLabel: 'Bob Baker (bob)', access: 'read' },
    });
    expect(api.grants.filter((g) => g.subject === 'bob')).toEqual([expect.objectContaining({ id: 'g-bob', access: 'read' })]);

    const hrRow = (await within(screen.getByRole('table')).findByText(HR)).closest('tr')!;
    await u.click(within(hrRow).getByRole('button', { name: 'Revoke' }));
    expect(await screen.findByText('Access revoked')).toBeInTheDocument();
    expect(api.sent[2]).toEqual({ method: 'DELETE', url: '/api/admin/grants/g-2', body: undefined });
    expect(api.grants.map((g) => g.subject)).toEqual(['bob']);
  });

  it('only grants view access on a white-pages directory', async () => {
    const u = userEvent.setup();
    const api = server();
    renderAt('/admin/permissions?definition=def-whitepages');

    expect(await screen.findByText('This is a read-only definition: grants can only give view access.')).toBeInTheDocument();
    expect(screen.getByLabelText('Access level')).toBeDisabled();
    await u.click(screen.getByRole('radio', { name: /Group/ }));
    await u.type(screen.getByLabelText('Search groups'), 'h');
    await u.click(await screen.findByRole('button', { name: /^Group hr/ }));
    await u.click(screen.getByRole('button', { name: 'Grant access' }));

    expect(await screen.findByText('Granted read access to hr')).toBeInTheDocument();
    expect(api.sent.map((s) => (s.body as { access: string }).access)).toEqual(['read']);
  });
});
