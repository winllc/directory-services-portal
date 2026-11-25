import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { AccessRequest, GroupDetail, GroupPerson, GroupSummary } from '@dsp/shared';
import { App } from '../../App';
import { AuthProvider } from '../../lib/auth';
import { ToastProvider } from '../ui';

/*
 * Group access as a group's owner sees it: what the group page offers follows what the server
 * says the viewer may do, and each action sends exactly the change the owner chose.
 */

const B = 'dc=example,dc=com';
const ENGINEERING = `cn=engineering,ou=groups,${B}`;
const person = (uid: string, name: string): GroupPerson => ({ value: `uid=${uid},ou=people,${B}`, dn: `uid=${uid},ou=people,${B}`, name });
const CAROL = person('carol', 'Carol Chen');
const ADA = person('admin', 'Ada Admin');
const GEORGE = person('george', 'George Gupta');
const carol = { username: 'carol', dn: CAROL.dn, displayName: 'Carol Chen', groups: [ENGINEERING], isAdmin: false, authMethod: 'password' };
const features = ['directory-editor', 'self-service-profile'].map((id) => ({ id, enabled: true }));
const GROUP_PAGE = `/groups/def-groups/group?dn=${encodeURIComponent(ENGINEERING)}`;

const request = (id: string, patch: Partial<AccessRequest>): AccessRequest => ({
  id,
  definitionId: 'def-groups',
  groupDn: ENGINEERING,
  groupName: 'engineering',
  requester: 'dave',
  requesterDn: `uid=dave,ou=people,${B}`,
  requesterName: 'Dave Diaz',
  member: `uid=dave,ou=people,${B}`,
  reason: 'On-call rotation',
  status: 'pending',
  createdAt: '2026-03-01T09:00:00.000Z',
  ...patch,
});

const SOCIAL = `cn=social-club,ou=groups,${B}`;
const summary = (dn: string, name: string, patch: Partial<GroupSummary>): GroupSummary => ({
  definitionId: 'def-groups',
  definitionName: 'Groups',
  dn,
  name,
  member: false,
  owner: false,
  canManage: false,
  requestable: true,
  open: false,
  ...patch,
});

function server(overrides: Partial<GroupDetail> = {}) {
  const state = {
    owners: [ADA, CAROL],
    mine: [] as AccessRequest[],
    groups: [summary(SOCIAL, 'social-club', { open: true }), summary(`cn=sales,ou=groups,${B}`, 'sales', {})],
  };
  const sent: { method: string; path: string; body: unknown }[] = [];
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
  const detail = (): GroupDetail => ({
    group: {
      definitionId: 'def-groups',
      definitionName: 'Groups',
      dn: ENGINEERING,
      name: 'engineering',
      description: 'Engineering department',
      member: true,
      owner: true,
      canManage: true,
      requestable: true,
      open: false,
    },
    owners: state.owners,
    moreOwners: false,
    memberCount: 3,
    memberAttribute: 'member',
    requireReason: true,
    allowLeave: true,
    pendingRequests: 0,
    canManageOwners: true,
    ...overrides,
  });
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      const path = url.split('?')[0];
      const body = init?.body ? JSON.parse(String(init.body)) : undefined;
      if (method !== 'GET') sent.push({ method, path, body });
      if (path === '/api/auth/me') return json({ user: carol, consentRequired: false });
      if (path === '/api/info') return json({ mode: 'memory', connected: true, version: '1' });
      if (path === '/api/license') return json({ mode: 'FULL', state: 'VALID', message: '', evaluation: false, features });
      if (path === '/api/groups/directories') return json([{ id: 'def-groups', name: 'Groups', slug: 'groups', requireReason: true, allowLeave: true }]);
      if (path === '/api/groups/requests/summary') return json({ toReview: 0, myPending: 0 });
      if (path === '/api/groups/requests' && url.includes('scope=mine')) return json(state.mine.map((r) => ({ request: r, definitionName: 'Groups', canDecide: false })));
      if (path === '/api/groups') return json({ groups: state.groups, truncated: false });
      if (path === '/api/groups/def-groups/join') {
        const dn = new URLSearchParams(url.split('?')[1]).get('dn');
        state.groups = state.groups.map((g) => (g.dn === dn ? { ...g, member: true } : g));
        return json({ dn, attribute: 'member', added: [CAROL.value], removed: [], partial: false });
      }
      if (path === '/api/groups/def-groups/group') return json(detail());
      if (path === '/api/groups/def-groups/members' && method === 'GET') return json({ items: [CAROL], total: 1, page: 1, pageSize: 25, partial: false });
      if (path === '/api/groups/def-groups/people') return json([GEORGE]);
      if (path === '/api/groups/def-groups/owners') {
        const change = body as { add: string[]; remove: string[] };
        state.owners = [...state.owners.filter((o) => !change.remove.includes(o.value)), ...[GEORGE].filter((p) => change.add.includes(p.value))];
        return json({ dn: ENGINEERING, attribute: 'owner', added: change.add, removed: change.remove, partial: false });
      }
      return json([]);
    }),
  );
  return { sent, state };
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

const card = (title: string | RegExp) => screen.getByRole('heading', { name: title }).closest('.card') as HTMLElement;

afterEach(() => vi.unstubAllGlobals());

describe('group page: owners', () => {
  it('lets an owner add and remove owners where the directory allows it, never the last one', async () => {
    const u = userEvent.setup();
    const api = server();
    renderAt(GROUP_PAGE);

    await screen.findByRole('heading', { name: 'Owners' });
    const owners = card('Owners');
    await u.type(within(owners).getByLabelText('Find someone to make an owner'), 'geo');
    await u.click(await within(owners).findByRole('button', { name: 'Make owner' }));
    expect(await screen.findByText('George Gupta is now an owner.')).toBeInTheDocument();
    expect(api.sent).toEqual([{ method: 'POST', path: '/api/groups/def-groups/owners', body: { add: [GEORGE.dn], remove: [] } }]);

    // Removing asks first, and sends the owner's value.
    await u.click(await within(owners).findByRole('button', { name: 'Remove owner Ada Admin' }));
    const confirm = await screen.findByRole('dialog', { name: 'Remove Ada Admin as an owner?' });
    expect(api.sent).toHaveLength(1);
    await u.click(within(confirm).getByRole('button', { name: 'Remove owner' }));
    expect(await screen.findByText('Ada Admin is no longer an owner.')).toBeInTheDocument();
    expect(api.sent[1].body).toEqual({ add: [], remove: [ADA.value] });

    // Down to one owner, nothing offers to remove them.
    await u.click(await within(owners).findByRole('button', { name: 'Remove owner George Gupta' }));
    await u.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Remove owner' }));
    expect(await within(owners).findByText("The last owner can't be removed. Add another one first.")).toBeInTheDocument();
    expect(within(owners).queryByRole('button', { name: /Remove owner/ })).not.toBeInTheDocument();
  });

  it('only lists the owners where owners may not change them', async () => {
    server({ canManageOwners: false });
    renderAt(GROUP_PAGE);

    await screen.findByRole('heading', { name: 'Owners' });
    const owners = card('Owners');
    expect(within(owners).getByText('Ada Admin')).toBeInTheDocument();
    expect(within(owners).queryByLabelText('Find someone to make an owner')).not.toBeInTheDocument();
    expect(within(owners).queryByRole('button', { name: /Remove owner/ })).not.toBeInTheDocument();
  });
});

describe('my requests', () => {
  it('tells the requester that a request expired, and after how long', async () => {
    const api = server();
    api.state.mine = [
      request('req-1', { requester: 'carol', status: 'expired', decidedAt: '2026-03-15T09:00:00.000Z', expiredAfterDays: 14 }),
      request('req-2', { requester: 'carol', groupName: 'sales', status: 'rejected', decidedAt: '2026-03-02T09:00:00.000Z', decidedByName: 'Ada Admin', note: 'Not now' }),
    ];
    renderAt('/groups?tab=requests');

    const expired = (await screen.findByText('Expired')).closest('tr')!;
    expect(expired).toHaveTextContent('Nobody answered within 14 days. You can ask again.');
    expect(within(expired).queryByRole('button', { name: 'Withdraw' })).not.toBeInTheDocument();
    const rejected = screen.getByText('Rejected').closest('tr')!;
    expect(rejected).toHaveTextContent('Ada Admin');
    expect(rejected).toHaveTextContent('Not now');
  });
});

describe('open groups', () => {
  it('offers Join instead of Request access, and joins at once', async () => {
    const u = userEvent.setup();
    const api = server();
    renderAt('/groups');

    const social = (await screen.findByRole('link', { name: 'social-club' })).closest('tr')!;
    expect(social).toHaveTextContent('Open to join');
    expect(within(social).queryByRole('button', { name: 'Request access' })).not.toBeInTheDocument();
    const sales = screen.getByRole('link', { name: 'sales' }).closest('tr')!;
    expect(within(sales).getByRole('button', { name: 'Request access' })).toBeInTheDocument();
    expect(within(sales).queryByRole('button', { name: 'Join' })).not.toBeInTheDocument();

    await u.click(within(social).getByRole('button', { name: 'Join' }));
    expect(await screen.findByText('You joined social-club.')).toBeInTheDocument();
    expect(api.sent.map((x) => `${x.method} ${x.path}`)).toEqual(['POST /api/groups/def-groups/join']);
    // A member now: nothing left to join.
    expect(await within(social).findByText('Member')).toBeInTheDocument();
    expect(within(social).queryByRole('button', { name: 'Join' })).not.toBeInTheDocument();
  });

  it('offers Join on the page of an open group', async () => {
    const u = userEvent.setup();
    const base = summary(ENGINEERING, 'engineering', { open: true });
    const api = server({ group: base, canManageOwners: false });
    renderAt(GROUP_PAGE);

    expect(await screen.findByText(/This group is open: you can join at once/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request access' })).not.toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Join' }));
    expect(await screen.findByText('You joined engineering.')).toBeInTheDocument();
    expect(api.sent[0].path).toBe('/api/groups/def-groups/join');
  });
});
