import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider, RequireAuth } from '../../lib/auth';
import { MetricsHomePage } from '../../pages/metrics/MetricsHomePage';
import { ToastProvider } from '../ui';

/*
 * Metric results stay on the page until the user refreshes them: coming back to the page, however
 * much later, shows what was computed before instead of asking the server to compute it again.
 */

function server() {
  const summaryRequests: string[] = [];
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      const [path, query = ''] = url.split('?');
      if (path === '/api/auth/me') {
        return json({ user: { username: 'erin', dn: 'uid=erin,dc=example,dc=com', displayName: 'Erin', groups: [], isAdmin: false, authMethod: 'password' }, consentRequired: false });
      }
      if (path === '/api/metrics/dashboards') return json([]);
      if (path === '/api/metrics/summary') {
        summaryRequests.push(query);
        // A recount finds more entries, so the page shows which answer it holds.
        return json([{ id: 'def-staff', name: 'Staff', slug: 'staff', count: summaryRequests.length === 1 ? 12 : 15, truncated: false }]);
      }
      return json([]);
    }),
  );
  return { summaryRequests };
}

function page() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/metrics']}>
        <ToastProvider>
          <AuthProvider>
            <RequireAuth>
              <Routes>
                <Route
                  path="/metrics"
                  element={
                    <>
                      <Link to="/elsewhere">Leave</Link>
                      <MetricsHomePage />
                    </>
                  }
                />
                <Route path="/elsewhere" element={<Link to="/metrics">Back to metrics</Link>} />
              </Routes>
            </RequireAuth>
          </AuthProvider>
        </ToastProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('the metrics page', () => {
  it('does not recompute when it is visited again', async () => {
    const api = server();
    page();
    expect(await screen.findByText('12 entries')).toBeInTheDocument();
    expect(api.summaryRequests).toEqual(['']);

    fireEvent.click(screen.getByText('Leave'));
    await screen.findByText('Back to metrics');
    // Ten minutes later: far past any "fresh for a while" window.
    const later = Date.now() + 10 * 60_000;
    vi.spyOn(Date, 'now').mockReturnValue(later);
    fireEvent.click(screen.getByText('Back to metrics'));

    expect(await screen.findByText('12 entries')).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 50));
    expect(api.summaryRequests).toEqual(['']);
    expect(screen.getByText('12 entries')).toBeInTheDocument();
  });

  it('recomputes when the user asks for it', async () => {
    const api = server();
    page();
    expect(await screen.findByText('12 entries')).toBeInTheDocument();
    expect(screen.getByText(/^Counted at /)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Refresh counts' }));

    expect(await screen.findByText('15 entries')).toBeInTheDocument();
    await waitFor(() => expect(api.summaryRequests).toEqual(['', 'refresh=true']));
  });
});
