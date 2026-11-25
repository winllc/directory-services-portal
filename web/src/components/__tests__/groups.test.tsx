import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { AccessRequest, GroupDetail, GroupMember, GroupPerson, GroupSummary } from '@dsp/shared';
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
/** A day from today (UTC), as the API takes end dates. */
const inDays = (n: number) => new Date(Date.now() + n * 86_400_000).toISOString().slice(0, 10);
const shown = (date: string) => new Date(`${date}T00:00:00Z`).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC' });
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

function server(overrides: Partial<GroupDetail> = {}, licensed = ['directory-editor', 'self-service-profile']) {
  const state = {
    owners: [ADA, CAROL],
    mine: [] as AccessRequest[],
    review: [] as AccessRequest[],
    members: [CAROL] as GroupMember[],
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
      if (path === '/api/license') {
        return json({ mode: 'FULL', state: 'VALID', message: '', evaluation: false, features: features.map((f) => ({ ...f, enabled: licensed.includes(f.id) })) });
      }
      if (path === '/api/groups/def-groups/members/export') {
        return new Response('name,login,mail,dn,until\r\n', {
          status: 200,
          headers: { 'Content-Disposition': 'attachment; filename="engineering-members-2026-11-25.csv"', 'X-DSP-Export-Count': '1' },
        });
      }
      if (path === '/api/groups/directories') return json([{ id: 'def-groups', name: 'Groups', slug: 'groups', requireReason: true, allowLeave: true }]);
      if (path === '/api/groups/requests/summary') return json({ toReview: 0, myPending: 0 });
      if (path === '/api/groups/requests' && url.includes('scope=mine')) return json(state.mine.map((r) => ({ request: r, definitionName: 'Groups', canDecide: false })));
      if (path === '/api/groups/requests' && url.includes('scope=review')) return json(state.review.map((r) => ({ request: r, definitionName: 'Groups', canDecide: true })));
      if (path === '/api/groups/requests/decide') {
        const sentIds = (body as { ids: string[] }).ids;
        const approve = (body as { approve: boolean }).approve;
        // The second request was decided by someone else meanwhile.
        const results = sentIds.map((id) =>
          id === 'req-2' ? { id, ok: false, error: 'This request was already rejected', code: 409 } : { id, ok: true, status: approve ? 'approved' : 'rejected' },
        );
        state.review = state.review.filter((r) => !sentIds.includes(r.id));
        return json({ results, decided: results.filter((x) => x.ok).length, failed: results.filter((x) => !x.ok).length });
      }
      if (/^\/api\/groups\/requests\/[^/]+\/(approve|reject)$/.test(path)) {
        const id = path.split('/')[4];
        const decided = { ...state.review.find((r) => r.id === id)!, status: path.endsWith('approve') ? 'approved' : 'rejected' };
        state.review = state.review.filter((r) => r.id !== id);
        return json(decided);
      }
      if (path === '/api/groups/mine') return json({ groups: state.groups.filter((g) => g.member), truncated: false });
      if (path === '/api/groups/def-groups/requests') return json(request('req-new', { requester: 'carol', ...(body as object) }), 201);
      if (path === '/api/groups/def-groups/people/resolve') {
        return json({
          found: [
            { line: 'george', value: GEORGE.value, dn: GEORGE.dn, name: 'George Gupta (george)', member: false },
            { line: 'carol@example.com', value: CAROL.value, dn: CAROL.dn, name: 'Carol Chen (carol)', member: true },
          ],
          notFound: ['nobody'],
          ambiguous: [{ line: 'dave@example.com', matches: ['Dave Diaz (dave)', 'Dana Diaz (dana)'] }],
        });
      }
      if (path === '/api/groups/def-groups/members/until') {
        const change = body as { member: string; until?: string };
        state.members = state.members.map((m) => (m.value === change.member ? { ...m, until: change.until ?? null } : m));
        return json({ value: change.member, name: change.member, until: change.until ?? null });
      }
      if (path === '/api/groups/def-groups/members' && method === 'POST') {
        const change = body as { add: string[]; remove: string[]; until?: string };
        state.members = [...state.members.filter((m) => !change.remove.includes(m.value)), ...[GEORGE].filter((p) => change.add.includes(p.value)).map((p) => ({ ...p, until: change.until ?? null }))];
        return json({ dn: ENGINEERING, attribute: 'member', added: change.add, removed: change.remove, partial: false });
      }
      if (path === '/api/groups') return json({ groups: state.groups, truncated: false });
      if (path === '/api/groups/def-groups/join') {
        const dn = new URLSearchParams(url.split('?')[1]).get('dn');
        state.groups = state.groups.map((g) => (g.dn === dn ? { ...g, member: true } : g));
        return json({ dn, attribute: 'member', added: [CAROL.value], removed: [], partial: false });
      }
      if (path === '/api/groups/def-groups/group') return json(detail());
      if (path === '/api/groups/def-groups/members' && method === 'GET') return json({ items: state.members, total: state.members.length, page: 1, pageSize: 25, partial: false });
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

describe('time-limited membership', () => {
  it('asks for access until a date, within the directory\'s longest membership', async () => {
    const u = userEvent.setup();
    const api = server();
    renderAt('/groups');

    const sales = (await screen.findByRole('link', { name: 'sales' })).closest('tr')!;
    await u.click(within(sales).getByRole('button', { name: 'Request access' }));
    const dialog = await screen.findByRole('dialog', { name: 'Ask to join sales' });
    await u.type(within(dialog).getByLabelText(/Why do you need access/), 'Quarter end');
    const until = within(dialog).getByLabelText('Access until (optional)');
    expect(until).toHaveAttribute('min', inDays(0));
    expect(until).not.toHaveAttribute('max');
    fireEvent.change(until, { target: { value: inDays(30) } });
    await u.click(within(dialog).getByRole('button', { name: 'Send request' }));
    expect(await screen.findByText('Asked to join sales. Its owners will decide.')).toBeInTheDocument();
    expect(api.sent).toEqual([{ method: 'POST', path: '/api/groups/def-groups/requests', body: { reason: 'Quarter end', until: inDays(30) } }]);
  });

  it('lets the approver shorten the access asked for', async () => {
    const u = userEvent.setup();
    const api = server();
    api.state.review = [request('req-9', { until: inDays(30) })];
    renderAt('/groups?tab=review');

    const row = (await screen.findByText('Dave Diaz')).closest('tr')!;
    expect(row).toHaveTextContent(`Until ${shown(inDays(30))}`);
    await u.click(within(row).getByRole('button', { name: 'Approve' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add Dave Diaz to engineering?' });
    const until = within(dialog).getByLabelText('Access until (optional)');
    // No later than the day asked for.
    expect(until).toHaveAttribute('max', inDays(30));
    expect(dialog).toHaveTextContent(`Dave Diaz asked for access until ${shown(inDays(30))}. You can shorten it; empty: as asked.`);
    fireEvent.change(until, { target: { value: inDays(10) } });
    await u.click(within(dialog).getByRole('button', { name: 'Approve and add' }));
    expect(await screen.findByText('Dave Diaz was added to engineering.')).toBeInTheDocument();
    expect(api.sent).toEqual([{ method: 'POST', path: '/api/groups/requests/req-9/approve', body: { until: inDays(10) } }]);
  });

  it('shows members\' end dates to owners, who add with one and change or remove it', async () => {
    const u = userEvent.setup();
    const api = server();
    api.state.members = [{ ...CAROL, until: inDays(5) }];
    renderAt(GROUP_PAGE);

    await screen.findByRole('heading', { name: /^Members/ });
    const members = card(/^Members/);
    const carolRow = (await within(members).findByText('Carol Chen')).closest('li')!;
    expect(carolRow).toHaveTextContent(`until ${shown(inDays(5))}`);

    // Added with the end date typed next to the search.
    fireEvent.change(within(members).getByLabelText('End date for people you add (optional)'), { target: { value: inDays(20) } });
    await u.type(within(members).getByLabelText('Find someone to add'), 'geo');
    await u.click(await within(members).findByRole('button', { name: 'Add' }));
    expect(await screen.findByText('George Gupta was added.')).toBeInTheDocument();
    expect(api.sent[0]).toEqual({ method: 'POST', path: '/api/groups/def-groups/members', body: { add: [GEORGE.value], remove: [], until: inDays(20) } });
    expect((await within(members).findByText('George Gupta')).closest('li')!).toHaveTextContent(`until ${shown(inDays(20))}`);

    // Changed in a dialog; removing a member sends no date.
    await u.click(within(members).getByRole('button', { name: 'End date for Carol Chen' }));
    let dialog = await screen.findByRole('dialog', { name: 'End date for Carol Chen' });
    expect(within(dialog).getByLabelText('Member until')).toHaveValue(inDays(5));
    fireEvent.change(within(dialog).getByLabelText('Member until'), { target: { value: inDays(9) } });
    await u.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await screen.findByText(`Carol Chen is a member until ${shown(inDays(9))}.`)).toBeInTheDocument();
    expect(api.sent[1]).toEqual({ method: 'POST', path: '/api/groups/def-groups/members/until', body: { member: CAROL.value, until: inDays(9) } });

    await u.click(within(members).getByRole('button', { name: 'End date for Carol Chen' }));
    dialog = await screen.findByRole('dialog', { name: 'End date for Carol Chen' });
    await u.click(within(dialog).getByRole('button', { name: 'Remove end date' }));
    expect(await screen.findByText("Carol Chen's membership has no end date any more.")).toBeInTheDocument();
    expect(api.sent[2].body).toEqual({ member: CAROL.value });
  });

  it('limits the dates to the directory\'s longest membership, where an end date cannot be removed', async () => {
    const u = userEvent.setup();
    const api = server({ maxMembershipDays: 30 });
    api.state.members = [{ ...CAROL, until: inDays(5) }];
    renderAt(GROUP_PAGE);

    await screen.findByRole('heading', { name: /^Members/ });
    const members = card(/^Members/);
    expect(within(members).getByLabelText('End date for people you add (optional)')).toHaveAttribute('max', inDays(30));
    await u.click(await within(members).findByRole('button', { name: 'End date for Carol Chen' }));
    const dialog = await screen.findByRole('dialog', { name: 'End date for Carol Chen' });
    expect(within(dialog).getByLabelText('Member until')).toHaveAttribute('max', inDays(30));
    expect(within(dialog).queryByRole('button', { name: 'Remove end date' })).not.toBeInTheDocument();
    expect(api.sent).toEqual([]);
  });

  it('shows the member their own end date', async () => {
    const api = server({ group: summary(ENGINEERING, 'engineering', { member: true, memberUntil: inDays(12) }), canManageOwners: false });
    api.state.groups = [summary(SOCIAL, 'social-club', { member: true, open: true, memberUntil: inDays(12) })];
    renderAt(GROUP_PAGE);
    expect(await screen.findByText(`You are a member of this group until ${shown(inDays(12))}.`)).toBeInTheDocument();
  });

  it('lists My groups with the end dates', async () => {
    const api = server();
    api.state.groups = [summary(SOCIAL, 'social-club', { member: true, open: true, memberUntil: inDays(12) }), summary(ENGINEERING, 'engineering', { member: true })];
    renderAt('/groups?tab=mine');
    const social = (await screen.findByRole('link', { name: 'social-club' })).closest('tr')!;
    expect(social).toHaveTextContent(`Member until ${shown(inDays(12))}`);
    const engineering = screen.getByRole('link', { name: 'engineering' }).closest('tr')!;
    expect(within(engineering).getByText('Member')).toBeInTheDocument();
  });
});

describe('group page: adding people from a pasted list', () => {
  it('shows who was found, who was not and what is ambiguous before adding the people found', async () => {
    const u = userEvent.setup();
    const api = server();
    renderAt(GROUP_PAGE);

    await u.click(await screen.findByRole('button', { name: 'Add from a list' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add people to engineering from a list' });
    const check = within(dialog).getByRole('button', { name: 'Check the list' });
    expect(check).toBeDisabled();
    // Blank and repeated lines are dropped.
    await u.type(within(dialog).getByLabelText('People to add'), 'george\n\ncarol@example.com\n nobody \ndave@example.com\ngeorge');
    await u.click(check);

    const found = await within(dialog).findByRole('region', { name: 'Found' });
    expect(api.sent).toEqual([
      { method: 'POST', path: '/api/groups/def-groups/people/resolve', body: { lines: ['george', 'carol@example.com', 'nobody', 'dave@example.com'] } },
    ]);
    expect(found).toHaveTextContent('Found (2), 1 to add');
    expect(within(found).getByText('Carol Chen (carol)').closest('li')!).toHaveTextContent('Already a member');
    expect(within(dialog).getByRole('region', { name: 'Not found' })).toHaveTextContent('nobody');
    const ambiguous = within(dialog).getByRole('region', { name: 'Ambiguous' });
    expect(ambiguous).toHaveTextContent('dave@example.com');
    expect(ambiguous).toHaveTextContent('Dave Diaz (dave), Dana Diaz (dana)');

    // Only the people found who aren't members yet are added, in one step, with the end date.
    fireEvent.change(within(dialog).getByLabelText('End date (optional)'), { target: { value: inDays(14) } });
    await u.click(within(dialog).getByRole('button', { name: 'Add 1 person' }));
    expect(await screen.findByText('1 person was added to engineering.')).toBeInTheDocument();
    expect(api.sent[1]).toEqual({ method: 'POST', path: '/api/groups/def-groups/members', body: { add: [GEORGE.value], remove: [], until: inDays(14) } });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('refuses a list longer than the limit before asking the server', async () => {
    const api = server();
    renderAt(GROUP_PAGE);

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Add from a list' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('People to add'), { target: { value: Array.from({ length: 201 }, (_, i) => `user${i}`).join('\n') } });
    expect(within(dialog).getByText('At most 200 lines at once: this list has 201.')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Check the list' })).toBeDisabled();
    expect(api.sent).toEqual([]);
  });
});

describe('to review: deciding several requests at once', () => {
  const three = () => [
    request('req-1', {}),
    request('req-2', { requester: 'erin', requesterName: 'Erin Evans', requesterDn: `uid=erin,ou=people,${B}` }),
    request('req-3', { requester: 'bob', requesterName: 'Bob Baker', requesterDn: `uid=bob,ou=people,${B}`, groupName: 'sales' }),
  ];

  it('approves the ticked requests with one note and says what failed and why', async () => {
    const u = userEvent.setup();
    const api = server();
    api.state.review = three();
    renderAt('/groups?tab=review');

    await screen.findByText('Dave Diaz');
    // Nothing is offered until something is ticked.
    expect(screen.queryByRole('button', { name: 'Approve selected' })).not.toBeInTheDocument();
    await u.click(screen.getByRole('checkbox', { name: 'Select the request of Dave Diaz for engineering' }));
    await u.click(screen.getByRole('checkbox', { name: 'Select the request of Erin Evans for engineering' }));
    expect(within(screen.getByRole('group', { name: 'Selected requests' })).getByText('2 selected')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Approve selected' }));

    const dialog = await screen.findByRole('dialog', { name: 'Approve 2 requests?' });
    expect(dialog).toHaveTextContent('Dave Diaz · engineering');
    expect(dialog).toHaveTextContent('Erin Evans · engineering');
    expect(dialog).not.toHaveTextContent('Bob Baker');
    await u.type(within(dialog).getByLabelText('Note for all (optional)'), 'Welcome');
    expect(api.sent).toEqual([]);
    await u.click(within(dialog).getByRole('button', { name: 'Approve and add 2' }));

    const done = await screen.findByRole('dialog', { name: 'Requests approved' });
    expect(api.sent).toEqual([{ method: 'POST', path: '/api/groups/requests/decide', body: { ids: ['req-1', 'req-2'], approve: true, note: 'Welcome' } }]);
    expect(done).toHaveTextContent('1 of 2 requests were approved.');
    expect(done).toHaveTextContent('1 could not be approved');
    expect(done).toHaveTextContent('Erin Evans · engineering: This request was already rejected');
    await u.click(within(done).getByRole('button', { name: 'Done' }));
    // The decided ones are gone; one request left needs no ticking.
    expect(await screen.findByText('Bob Baker')).toBeInTheDocument();
    expect(screen.queryByText('Dave Diaz')).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  });

  it('selects all shown and rejects them together', async () => {
    const u = userEvent.setup();
    const api = server();
    api.state.review = [three()[0], three()[2]];
    renderAt('/groups?tab=review');

    await u.click(await screen.findByRole('checkbox', { name: 'Select all shown' }));
    expect(screen.getByRole('checkbox', { name: 'Select the request of Bob Baker for sales' })).toBeChecked();
    // Unticking all clears the selection again.
    await u.click(screen.getByRole('checkbox', { name: 'Select all shown' }));
    expect(screen.queryByRole('button', { name: 'Reject selected' })).not.toBeInTheDocument();
    await u.click(screen.getByRole('checkbox', { name: 'Select all shown' }));
    await u.click(screen.getByRole('button', { name: 'Reject selected' }));
    const dialog = await screen.findByRole('dialog', { name: 'Reject 2 requests?' });
    await u.click(within(dialog).getByRole('button', { name: 'Reject 2' }));
    // The answer stays up although nothing is left to review behind it.
    const done = await screen.findByRole('dialog', { name: 'Requests rejected' });
    expect(done).toHaveTextContent('2 of 2 requests were rejected.');
    expect(api.sent[0].body).toEqual({ ids: ['req-1', 'req-3'], approve: false });
    await u.click(within(done).getByRole('button', { name: 'Done' }));
    expect(await screen.findByText('Nothing to review')).toBeInTheDocument();
  });
});

describe('group page: exporting the members', () => {
  it('downloads the members as CSV with a recorded POST', async () => {
    const u = userEvent.setup();
    const api = server();
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:x'), revokeObjectURL: vi.fn() }));
    const saved: string[] = [];
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      saved.push(this.download);
    });
    renderAt(GROUP_PAGE);

    await u.click(await screen.findByRole('button', { name: 'Export CSV' }));
    await vi.waitFor(() => expect(saved).toEqual(['engineering-members-2026-11-25.csv']));
    expect(api.sent.map((x) => `${x.method} ${x.path}`)).toEqual(['POST /api/groups/def-groups/members/export']);
    click.mockRestore();
  });

  it('is not offered without the directory editor\'s license', async () => {
    server({}, ['self-service-profile']);
    renderAt(GROUP_PAGE);

    expect(await screen.findByRole('button', { name: 'Add from a list' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Export CSV' })).not.toBeInTheDocument();
  });
});
