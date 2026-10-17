import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { App } from '../../App';
import { AuthProvider } from '../../lib/auth';
import { ToastProvider } from '../ui';
import { api } from '../../api/client';

const alice = { username: 'alice', dn: 'uid=alice,dc=x', displayName: 'Alice Anderson', groups: [], isAdmin: false, authMethod: 'password' };

afterEach(() => vi.unstubAllGlobals());

describe('session controls', () => {
  it('marks polling as background, so it does not keep an unattended session alive', async () => {
    const fetchMock = vi.fn(async () => new Response('[]', { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    await api.get('/api/jobs', { background: true });
    await api.get('/api/definitions');
    const headers = fetchMock.mock.calls.map((c) => (c as unknown as [string, RequestInit])[1].headers as Record<string, string>);
    expect(headers[0]['X-DSP-Background']).toBe('1');
    expect(headers[1]['X-DSP-Background']).toBeUndefined();
  });

  it('explains on the login page that the session timed out', async () => {
    let signedIn = true;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => {
        const path = url.split('?')[0];
        const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
        if (path === '/api/auth/me') return json({ user: signedIn ? alice : null, consentRequired: false });
        if (path === '/api/info') return json({ mode: 'memory', connected: true, version: '1' });
        if (path === '/api/license') return json({ mode: 'FULL', state: 'VALID', message: '', evaluation: false, features: [] });
        // The first API call after the timeout: the session is gone.
        signedIn = false;
        return json({ error: 'Your session timed out after a period of inactivity. Sign in again.', code: 'session_expired' }, 401);
      }),
    );
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={['/']}>
          <ToastProvider>
            <AuthProvider>
              <App />
            </AuthProvider>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    expect(await screen.findByText('Your session timed out after a period of inactivity. Sign in again.')).toBeInTheDocument();
    expect(screen.getByLabelText('Username')).toBeInTheDocument();
  });
});
