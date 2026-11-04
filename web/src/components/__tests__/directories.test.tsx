import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { LdapDirectoryListing } from '@dsp/shared';
import { App } from '../../App';
import { AuthProvider } from '../../lib/auth';
import { ToastProvider } from '../ui';

const admin = { username: 'admin', dn: 'uid=admin,dc=x', displayName: 'Ada Admin', groups: [], isAdmin: true, authMethod: 'password', directory: 'default' };
const features = ['directory-editor', 'self-service-profile', 'form-builder', 'multi-directory'].map((id) => ({ id, enabled: true }));
const form = { id: 'form-person', name: 'Person', objectClasses: ['inetOrgPerson'], rdnAttribute: 'uid', fields: [{ id: 'f1', attribute: 'cn', label: 'Name', widget: 'text' }] };

const two: LdapDirectoryListing = {
  multiDirectoryLicensed: true,
  directories: [
    { id: 'corp', name: 'Corporate', primary: true, servers: ['ldaps://dc1.corp:636', 'ldaps://dc2.corp:636'], serverSelection: 'failover', currentServer: 'dc2.corp:636', reachable: true, enabled: true, baseDn: 'dc=corp,dc=example', x509: true, sso: true },
    { id: 'lab', name: 'Lab', primary: false, servers: ['ldap://lab:389'], serverSelection: 'failover', reachable: false, error: 'Directory server unavailable', enabled: true, baseDn: 'o=lab', x509: true, sso: true },
  ],
};
const one: LdapDirectoryListing = { multiDirectoryLicensed: false, directories: [two.directories[0]] };

afterEach(() => vi.unstubAllGlobals());

interface Sent {
  method: string;
  url: string;
  body: unknown;
}

/** The API: `directories` as GET /api/admin/directories lists them; `signedIn` whether a session exists. */
function server(opts: { directories: LdapDirectoryListing; signedIn: boolean; loginDirectories?: { id: string; name: string }[] }) {
  const sent: Sent[] = [];
  let user = opts.signedIn ? admin : null;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const path = url.split('?')[0];
      const method = init?.method ?? 'GET';
      const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
      if (method !== 'GET') sent.push({ method, url, body: init?.body ? JSON.parse(String(init.body)) : undefined });
      if (path === '/api/auth/me') return json({ user, consentRequired: false });
      if (path === '/api/auth/login') {
        user = admin;
        return json({ user });
      }
      if (path === '/api/info') {
        return json({ mode: 'ldap', connected: true, version: '1', auth: { password: true, x509: false, x509AutoLogin: false, directories: opts.loginDirectories } });
      }
      if (path === '/api/license') return json({ mode: 'FULL', state: 'VALID', message: '', evaluation: false, features });
      if (path === '/api/admin/directories') return json(opts.directories);
      if (path === '/api/admin/forms') return json([form]);
      if (path === '/api/admin/schema') return json({ merged: { attributeTypes: [], objectClasses: [] }, custom: { attributeTypes: [], objectClasses: [] } });
      if (path === '/api/admin/definitions' && method === 'POST') return json({ id: 'def-new', name: 'Lab people', slug: 'lab-people' }, 201);
      if (path === '/api/admin/definitions') {
        return json([
          { id: 'def-staff', name: 'Staff', slug: 'staff', formId: 'form-person', baseDn: 'ou=people,dc=corp,dc=example', scope: 'one', mode: 'readwrite', everyoneCanRead: false, listAttributes: ['cn'], searchAttributes: [], titleAttribute: 'cn', createContainers: [], selfMatch: { type: 'none' }, createdAt: '', updatedAt: '', directory: 'default' },
          { id: 'def-lab', name: 'Lab people', slug: 'lab-people', formId: 'form-person', baseDn: 'o=lab', scope: 'one', mode: 'readwrite', everyoneCanRead: false, listAttributes: ['cn'], searchAttributes: [], titleAttribute: 'cn', createContainers: [], selfMatch: { type: 'none' }, createdAt: '', updatedAt: '', directory: 'lab' },
        ]);
      }
      return json([]);
    }),
  );
  return sent;
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

describe('the login page', () => {
  it('asks which directory when there are several, and signs in to the one chosen', async () => {
    const sent = server({ directories: two, signedIn: false, loginDirectories: [{ id: 'corp', name: 'Corporate' }, { id: 'lab', name: 'Lab' }] });
    renderAt('/login');
    const picker = await screen.findByLabelText('Directory');
    expect(within(picker).getAllByRole('option').map((o) => o.textContent)).toEqual(['Corporate', 'Lab']);
    expect(picker).toHaveValue('corp');
    fireEvent.change(picker, { target: { value: 'lab' } });
    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'alice' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'password' } });
    fireEvent.click(screen.getByRole('button', { name: /sign in/i }));
    await waitFor(() => expect(sent.find((s) => s.url === '/api/auth/login')?.body).toEqual({ username: 'alice', password: 'password', directory: 'lab' }));
  });

  it('shows no picker, and sends no directory, with one directory', async () => {
    const sent = server({ directories: one, signedIn: false });
    renderAt('/login');
    fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'alice' } });
    expect(screen.queryByLabelText('Directory')).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'password' } });
    fireEvent.click(screen.getByRole('button', { name: /sign in/i }));
    await waitFor(() => expect(sent.find((s) => s.url === '/api/auth/login')?.body).toEqual({ username: 'alice', password: 'password' }));
  });
});

describe('directory definitions', () => {
  it('name their LDAP directory in the list when there are several', async () => {
    server({ directories: two, signedIn: true });
    renderAt('/admin/definitions');
    expect(await screen.findByRole('columnheader', { name: 'LDAP directory' })).toBeInTheDocument();
    const rows = screen.getAllByRole('row');
    // "default" stands for the first directory.
    expect(within(rows[1]).getByText('Corporate')).toBeInTheDocument();
    expect(within(rows[2]).getByText('Lab')).toBeInTheDocument();
  });

  it('have no directory column or select with one directory', async () => {
    server({ directories: one, signedIn: true });
    renderAt('/admin/definitions');
    expect(await screen.findByText('Staff')).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'LDAP directory' })).not.toBeInTheDocument();
    renderAt('/admin/definitions/new');
    expect(await screen.findByLabelText(/^Name/)).toBeInTheDocument();
    expect(screen.queryByLabelText('LDAP directory')).not.toBeInTheDocument();
  });

  it('are given a directory in the editor, whose base DN becomes the default', async () => {
    const sent = server({ directories: two, signedIn: true });
    renderAt('/admin/definitions/new');
    const select = await screen.findByLabelText('LDAP directory');
    expect(select).toHaveValue('corp');
    fireEvent.change(select, { target: { value: 'lab' } });
    expect(screen.getByLabelText(/^Base DN/)).toHaveValue('o=lab');
    fireEvent.change(screen.getByLabelText(/^Name/), { target: { value: 'Lab people' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(sent.find((s) => s.url === '/api/admin/definitions')?.body).toMatchObject({ directory: 'lab', baseDn: 'o=lab', slug: 'lab-people' }));
  });
});

describe('the LDAP directories page', () => {
  it('lists each directory with its servers, the one in use, health and license', async () => {
    server({ directories: { ...two, directories: [two.directories[0], { ...two.directories[1], enabled: false, reachable: undefined }] }, signedIn: true });
    renderAt('/admin/ldap-directories');
    expect(await screen.findByRole('heading', { name: 'LDAP directories' })).toBeInTheDocument();
    await screen.findByText('Corporate');
    const rows = screen.getAllByRole('row');
    expect(within(rows[1]).getByText('ldaps://dc2.corp:636')).toBeInTheDocument();
    expect(within(rows[1]).getByText('Failover, in this order')).toBeInTheDocument();
    expect(within(rows[1]).getByText('dc2.corp:636')).toBeInTheDocument();
    expect(within(rows[1]).getByText('Reachable')).toBeInTheDocument();
    expect(within(rows[2]).getByText('Disabled: not licensed')).toBeInTheDocument();
    expect(within(rows[2]).getByText('Not checked')).toBeInTheDocument();
  });
});
