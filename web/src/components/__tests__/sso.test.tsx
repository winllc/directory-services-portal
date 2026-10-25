import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { App } from '../../App';
import { AuthProvider } from '../../lib/auth';
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

afterEach(() => vi.unstubAllGlobals());

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
});
