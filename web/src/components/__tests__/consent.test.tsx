import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { App } from '../../App';
import { AuthProvider } from '../../lib/auth';
import { ToastProvider } from '../ui';
import { consentBlocks } from '../ConsentNotice';

const TEXT = 'You are accessing a U.S. Government (USG) Information System (IS).\n\n- The USG routinely intercepts\n  communications.\n\n- At any time, the USG may inspect and seize data stored on this IS.';

function mockApi(consentRequired: boolean) {
  const calls: string[] = [];
  let acknowledged = !consentRequired;
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const path = url.split('?')[0];
    calls.push(`${init?.method ?? 'GET'} ${path}`);
    const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
    switch (path) {
      case '/api/info':
        return json({ mode: 'memory', connected: true, version: '1', consent: { title: 'Notice and Consent', text: TEXT } });
      case '/api/auth/me':
        return json({ user: null, consentRequired: !acknowledged });
      case '/api/auth/consent':
        acknowledged = true;
        return json({ consentRequired: false });
      case '/api/auth/login':
        return acknowledged
          ? json({ error: 'Invalid username or password' }, 401)
          : json({ error: 'Read and acknowledge the notice and consent banner before signing in', code: 'consent_required' }, 403);
      default:
        return json({ error: 'not found' }, 404);
    }
  });
  vi.stubGlobal('fetch', fetchMock);
  return calls;
}

const renderLogin = () =>
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/login']}>
        <ToastProvider>
          <AuthProvider>
            <App />
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );

afterEach(() => vi.unstubAllGlobals());

describe('notice and consent banner', () => {
  it('splits the text into paragraphs and one list', () => {
    expect(consentBlocks(TEXT)).toEqual([
      { kind: 'p', text: 'You are accessing a U.S. Government (USG) Information System (IS).' },
      { kind: 'ul', items: ['The USG routinely intercepts communications.', 'At any time, the USG may inspect and seize data stored on this IS.'] },
    ]);
  });

  it('comes before the sign-in form until the user agrees', async () => {
    const calls = mockApi(true);
    renderLogin();
    expect(await screen.findByRole('heading', { name: 'Notice and Consent' })).toBeInTheDocument();
    expect(screen.getByText('At any time, the USG may inspect and seize data stored on this IS.')).toBeInTheDocument();
    expect(screen.queryByLabelText('Username')).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'I agree' }));
    expect(await screen.findByLabelText('Username')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Notice and Consent' })).not.toBeInTheDocument();
    expect(calls).toContain('POST /api/auth/consent');
  });

  it('shows the sign-in form directly when the banner is off or already acknowledged', async () => {
    mockApi(false);
    renderLogin();
    expect(await screen.findByLabelText('Username')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'I agree' })).not.toBeInTheDocument();
  });
});
