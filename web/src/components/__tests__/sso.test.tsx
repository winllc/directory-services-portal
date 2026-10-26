import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { App } from '../../App';
import { AuthProvider, browser } from '../../lib/auth';
import { ToastProvider } from '../ui';
import { ssoErrorMessage } from '../../pages/LoginPage';

function mockApi(auth: unknown) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      const path = url.split('?')[0];
      const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
      if (path === '/api/info') return json({ mode: 'ldap', connected: true, version: '1', auth });
      if (path === '/api/auth/me') return json({ user: null, consentRequired: false });
      return json({ error: 'not found' }, 404);
    }),
  );
}

const renderAt = (entry: string) =>
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[entry]}>
        <ToastProvider>
          <AuthProvider>
            <App />
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('single sign-on on the login page', () => {
  it('offers each configured provider as a link next to password sign-in', async () => {
    mockApi({
      password: true,
      x509: false,
      x509AutoLogin: false,
      sso: [
        { id: 'oidc', label: 'Sign in with Entra ID', url: '/api/auth/sso/authorize/oidc' },
        { id: 'saml', label: 'Sign in with ADFS', url: '/api/auth/sso/saml/authenticate/saml' },
      ],
    });
    renderAt('/login');
    const entra = await screen.findByRole('link', { name: /Sign in with Entra ID/ });
    expect(entra.getAttribute('href')).toBe('/api/auth/sso/authorize/oidc');
    expect(screen.getByRole('link', { name: /Sign in with ADFS/ }).getAttribute('href')).toBe('/api/auth/sso/saml/authenticate/saml');
    expect(screen.getByLabelText('Username')).toBeTruthy();
  });

  it('shows no provider when none is configured', async () => {
    mockApi({ password: true, x509: false, x509AutoLogin: false, sso: [] });
    renderAt('/login');
    await screen.findByLabelText('Username');
    expect(screen.queryByRole('link', { name: /Sign in with/ })).toBeNull();
  });

  it('explains why a single sign-on came back refused', async () => {
    mockApi({ password: false, x509: false, x509AutoLogin: false, sso: [{ id: 'oidc', label: 'Sign in with Okta', url: '/api/auth/sso/authorize/oidc' }] });
    renderAt('/login?sso_error=no_account');
    expect(await screen.findByText(/no directory account matches it/)).toBeTruthy();
    expect(screen.queryByLabelText('Username')).toBeNull();
    expect(ssoErrorMessage(null)).toBeNull();
    expect(ssoErrorMessage('consent')).toMatch(/notice and consent/);
    expect(ssoErrorMessage('busy')).toMatch(/Wait a minute/);
    expect(ssoErrorMessage('anything-else')).toMatch(/did not complete/);
  });

  it('after signing out goes on to the provider when the API says so, and says so on return', async () => {
    const alice = { username: 'alice', dn: 'uid=alice,dc=x', displayName: 'Alice Anderson', groups: [], isAdmin: false, authMethod: 'oidc' };
    let signedIn = true;
    const end = 'https://idp.example/logout?client_id=portal&post_logout_redirect_uri=https%3A%2F%2Fportal.example%2Flogin%3Fsigned_out';
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      const path = url.split('?')[0];
      const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
      if (path === '/api/info') return json({ mode: 'ldap', connected: true, version: '1', auth: { password: true, x509: false, x509AutoLogin: false, sso: [] } });
      if (path === '/api/auth/me') return json({ user: signedIn ? alice : null, consentRequired: false });
      if (path === '/api/license') return json({ mode: 'FULL', state: 'VALID', message: '', evaluation: false, features: [] });
      if (path === '/api/auth/logout' && init?.method === 'POST') {
        signedIn = false;
        return json({ ok: true, consentRequired: false, redirect: end });
      }
      return json([]);
    });
    vi.stubGlobal('fetch', fetchMock);
    const leave = vi.spyOn(browser, 'leave').mockImplementation(() => undefined);
    renderAt('/');
    const signOut = await screen.findAllByRole('button', { name: 'Sign out' });
    signOut[0].click();
    await vi.waitFor(() => expect(leave).toHaveBeenCalledWith(end));
    // The portal's own session was ended first, by the API call.
    expect(fetchMock.mock.calls.some((c) => (c as unknown as [string])[0] === '/api/auth/logout')).toBe(true);
  });

  it('signs out here only when the API names no provider sign-out', async () => {
    const alice = { username: 'alice', dn: 'uid=alice,dc=x', displayName: 'Alice Anderson', groups: [], isAdmin: false, authMethod: 'password' };
    let signedIn = true;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) => {
        const path = url.split('?')[0];
        const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
        if (path === '/api/info') return json({ mode: 'ldap', connected: true, version: '1', auth: { password: true, x509: false, x509AutoLogin: false, sso: [] } });
        if (path === '/api/auth/me') return json({ user: signedIn ? alice : null, consentRequired: false });
        if (path === '/api/license') return json({ mode: 'FULL', state: 'VALID', message: '', evaluation: false, features: [] });
        if (path === '/api/auth/logout' && init?.method === 'POST') {
          signedIn = false;
          return json({ ok: true, consentRequired: false });
        }
        return json([]);
      }),
    );
    const leave = vi.spyOn(browser, 'leave').mockImplementation(() => undefined);
    renderAt('/');
    (await screen.findAllByRole('button', { name: 'Sign out' }))[0].click();
    expect(await screen.findByLabelText('Username')).toBeTruthy();
    expect(leave).not.toHaveBeenCalled();
  });

  it('confirms the sign-out when the provider sends the browser back', async () => {
    mockApi({ password: true, x509: false, x509AutoLogin: false, sso: [] });
    renderAt('/login?signed_out');
    expect(await screen.findByText(/signed out of the portal and of your organization/)).toBeTruthy();
  });
});
