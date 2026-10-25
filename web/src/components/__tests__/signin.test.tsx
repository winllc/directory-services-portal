import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { App } from '../../App';
import { AuthProvider } from '../../lib/auth';
import { ToastProvider } from '../ui';

const alice = { username: 'alice', dn: 'uid=alice,dc=x', displayName: 'Alice Anderson', groups: [], isAdmin: false, authMethod: 'password' };
const admin = { ...alice, username: 'admin', dn: 'uid=admin,dc=x', displayName: 'Ada Admin', isAdmin: true };
const features = ['directory-editor', 'self-service-profile', 'form-builder', 'metrics', 'data-analysis'].map((id) => ({ id, enabled: true }));

afterEach(() => vi.unstubAllGlobals());

/** The API: signed out until /api/auth/login signs in `who`; `staff` is a directory only administrators may open. */
function server(who: typeof alice) {
  let user: typeof alice | null = null;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      const path = url.split('?')[0];
      const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
      if (path === '/api/auth/me') return json({ user, consentRequired: false });
      if (path === '/api/auth/login') {
        user = who;
        return json({ user });
      }
      if (path === '/api/info') return json({ mode: 'memory', connected: true, version: '1' });
      if (path === '/api/license') return json({ mode: 'FULL', state: 'VALID', message: '', evaluation: false, features });
      if (path.startsWith('/api/definitions/staff')) {
        // As over the network: the answer comes after the page has settled.
        await new Promise((r) => setTimeout(r, 400));
        return user?.isAdmin ? json({ error: 'Not found' }, 404) : json({ error: 'You do not have access to this directory' }, 403);
      }
      return json([]);
    }),
  );
}

function Where() {
  return <div data-testid="where">{useLocation().pathname}</div>;
}

function renderAt(path: string) {
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
}

async function signIn(username: string) {
  fireEvent.change(await screen.findByLabelText('Username'), { target: { value: username } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'password' } });
  fireEvent.click(screen.getByRole('button', { name: /sign in/i }));
}

describe('the page signing in leads to', () => {
  it('is the remembered page when the new account may open it', async () => {
    server(admin);
    renderAt('/admin/permissions');
    await signIn('admin');
    expect(await screen.findByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/admin/permissions'));
    expect(screen.queryByText('Administrators only')).not.toBeInTheDocument();
  });

  it('is the home page, not "Administrators only", when the remembered page is for administrators', async () => {
    server(alice);
    renderAt('/admin/permissions');
    await signIn('alice');
    expect(await screen.findByText('Welcome, Alice')).toBeInTheDocument();
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/$/);
    expect(screen.queryByText('Administrators only')).not.toBeInTheDocument();
  });

  it('is the home page when the remembered directory refuses the new account', async () => {
    server(alice);
    renderAt('/d/staff');
    await signIn('alice');
    expect(await screen.findByText('Welcome, Alice')).toBeInTheDocument();
    expect(screen.getByTestId('where')).toHaveTextContent(/^\/$/);
  });
});

describe('once signed in', () => {
  it('still says an administrator page is for administrators', async () => {
    server(alice);
    renderAt('/');
    await signIn('alice');
    expect(await screen.findByText('Welcome, Alice')).toBeInTheDocument();
    // A new tab on the same session, following a link to an administrator page.
    cleanup();
    renderAt('/admin/permissions');
    expect(await screen.findByText('Administrators only')).toBeInTheDocument();
  });
});
