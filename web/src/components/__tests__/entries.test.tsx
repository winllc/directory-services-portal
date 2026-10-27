import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import type { DirectoryEntry, FormDefinition, FormField } from '@dsp/shared';
import { App } from '../../App';
import { AuthProvider } from '../../lib/auth';
import { ToastProvider } from '../ui';

/*
 * Entry editing as people do it, through the real pages, hooks and API client, with only the
 * server faked: what is sent (method, URL with the entry's DN, the changed values only, the CSRF
 * header) and what the person then sees (the saved values, the new DN after a rename, the toast).
 */

const B = 'dc=example,dc=com';
const ALICE = `uid=alice,ou=people,${B}`;
const BOB = `uid=bob,ou=people,${B}`;
const ENGINEERING = `cn=engineering,ou=groups,${B}`;

const field = (id: string, attribute: string, label: string, extra: Partial<FormField> = {}): FormField => ({
  id,
  attribute,
  label,
  widget: 'text',
  multiValued: false,
  required: false,
  readOnly: false,
  selfEditable: false,
  ...extra,
});

const personForm: FormDefinition = {
  id: 'form-person',
  name: 'Person',
  objectClasses: ['inetOrgPerson'],
  rdnAttribute: 'uid',
  createdAt: '',
  updatedAt: '',
  fields: [
    field('f-uid', 'uid', 'Username', { required: true }),
    field('f-cn', 'cn', 'Full name', { required: true }),
    field('f-title', 'title', 'Title'),
    field('f-mobile', 'mobile', 'Mobile', { multiValued: true, selfEditable: true }),
    field('f-badge', 'acmeBadgeNumber', 'Badge number', { readOnly: true }),
  ],
};

const groupForm: FormDefinition = {
  id: 'form-group',
  name: 'Group',
  objectClasses: ['groupOfNames'],
  rdnAttribute: 'cn',
  createdAt: '',
  updatedAt: '',
  fields: [field('g-cn', 'cn', 'Name', { required: true }), field('g-member', 'member', 'Members', { multiValued: true })],
};

const definition = (id: string, slug: string, name: string, formId: string, access: 'read' | 'write') => ({
  id,
  slug,
  name,
  formId,
  baseDn: B,
  scope: 'sub' as const,
  mode: 'readwrite' as const,
  everyoneCanRead: false,
  listAttributes: ['cn'],
  searchAttributes: ['cn'],
  titleAttribute: 'cn',
  createContainers: [],
  selfMatch: { type: 'dn' },
  createdAt: '',
  updatedAt: '',
  access,
});

const features = ['directory-editor', 'self-service-profile', 'form-builder', 'metrics', 'data-analysis'].map((id) => ({ id, enabled: true }));
const user = (username: string, dn: string, displayName: string) => ({ username, dn, displayName, groups: [], isAdmin: false, authMethod: 'password' });

interface Sent {
  method: string;
  url: string;
  body: unknown;
  csrf: string | null;
}

/** A fake API holding a few entries; records every request that changes something. */
function server(opts: { who: ReturnType<typeof user>; staffAccess: 'read' | 'write' }) {
  const entries = new Map<string, DirectoryEntry>([
    [ALICE, { dn: ALICE, attributes: { uid: ['alice'], cn: ['Alice Anderson'], title: ['Senior Software Engineer'], mobile: ['+1 555 0101'], acmebadgenumber: ['B-1001'] } }],
    [BOB, { dn: BOB, attributes: { uid: ['bob'], cn: ['Bob Brown'], title: ['Partner Manager'], acmebadgenumber: ['B-1002'] } }],
    [ENGINEERING, { dn: ENGINEERING, attributes: { cn: ['engineering'], member: [ALICE] } }],
  ]);
  const staff = definition('def-staff', 'staff', 'Staff', 'form-person', opts.staffAccess);
  const groups = definition('def-groups', 'groups', 'Groups', 'form-group', 'write');
  const sent: Sent[] = [];
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });

  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET';
      const [path, query = ''] = url.split('?');
      const dn = new URLSearchParams(query).get('dn');
      const body = init?.body ? JSON.parse(String(init.body)) : undefined;
      if (method !== 'GET') sent.push({ method, url, body, csrf: new Headers(init?.headers).get('X-DSP-Request') });

      if (path === '/api/auth/me') return json({ user: opts.who, consentRequired: false });
      if (path === '/api/info') return json({ mode: 'memory', connected: true, version: '1' });
      if (path === '/api/license') return json({ mode: 'FULL', state: 'VALID', message: '', evaluation: false, features });
      if (path === '/api/definitions') return json([staff, groups]);
      if (path === '/api/definitions/staff') return json({ definition: staff, form: personForm });
      if (path === '/api/definitions/groups') return json({ definition: groups, form: groupForm });
      if (path === '/api/me/entries') {
        const own = entries.get(opts.who.dn);
        return json([{ definition: staff, form: personForm, entries: own ? [own] : [], editableFields: ['mobile'] }]);
      }
      const m = path.match(/^\/api\/(?:directories|me)\/([^/]+)\/(entry|entries|members)$/);
      if (m && dn) {
        const current = entries.get(dn);
        if (!current) return json({ error: 'Entry not found' }, 404);
        if (method === 'GET') return json(current);
        if (method === 'DELETE') {
          entries.delete(dn);
          return new Response(null, { status: 204 });
        }
        if (method === 'PUT') {
          const values = (body as { values: Record<string, string[]> }).values;
          const attributes = { ...current.attributes };
          for (const [k, v] of Object.entries(values)) {
            if (v.length) attributes[k] = v;
            else delete attributes[k];
          }
          let next = dn;
          if (values.uid && values.uid[0] !== current.attributes.uid?.[0]) next = `uid=${values.uid[0]},ou=people,${B}`;
          entries.delete(dn);
          entries.set(next, { dn: next, attributes });
          return json(entries.get(next));
        }
        if (method === 'POST' && m[2] === 'members') {
          const { add, remove } = body as { add: string[]; remove: string[] };
          const members = (current.attributes.member ?? []).filter((x) => !remove.includes(x));
          const added = add.filter((x) => !members.includes(x));
          current.attributes.member = [...members, ...added];
          return json({ dn, attribute: 'member', added, removed: remove, partial: false });
        }
      }
      return json([]);
    }),
  );
  return { sent, entries };
}

function Where() {
  const l = useLocation();
  return <div data-testid="where">{l.pathname + l.search}</div>;
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

const entryUrl = (slug: string, dn: string) => `/d/${slug}/entry?dn=${encodeURIComponent(dn)}`;
const where = () => decodeURIComponent(screen.getByTestId('where').textContent ?? '');

afterEach(() => vi.unstubAllGlobals());

describe('editing a directory entry', () => {
  it('sends only the changed attribute, for that entry, and shows the saved value', async () => {
    const u = userEvent.setup();
    const api = server({ who: user('erin', `uid=erin,ou=people,${B}`, 'Erin Evans'), staffAccess: 'write' });
    renderAt(entryUrl('staff', ALICE));

    expect(await screen.findByRole('heading', { name: 'Alice Anderson' })).toBeInTheDocument();
    expect(screen.getByText('Senior Software Engineer')).toBeInTheDocument();
    await u.click(screen.getByRole('button', { name: 'Edit' }));

    expect(screen.getByLabelText('Badge number')).toBeDisabled();
    await u.clear(screen.getByLabelText('Title'));
    await u.type(screen.getByLabelText('Title'), 'Staff Engineer');
    await u.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Changes saved')).toBeInTheDocument();
    expect(api.sent).toEqual([
      {
        method: 'PUT',
        url: `/api/directories/def-staff/entry?dn=${encodeURIComponent(ALICE)}`,
        body: { values: { title: ['Staff Engineer'] } },
        csrf: '1',
      },
    ]);
    // Back on the details, with the value the server saved; bob's entry untouched.
    expect(await screen.findByText('Staff Engineer')).toBeInTheDocument();
    expect(screen.queryByText('Senior Software Engineer')).not.toBeInTheDocument();
    expect(where()).toBe(`/d/staff/entry?dn=${ALICE}`);
    expect(api.entries.get(BOB)?.attributes.title).toEqual(['Partner Manager']);
  });

  it('follows a rename to the entry’s new DN', async () => {
    const u = userEvent.setup();
    const api = server({ who: user('erin', `uid=erin,ou=people,${B}`, 'Erin Evans'), staffAccess: 'write' });
    renderAt(entryUrl('staff', BOB) + '&edit=1');

    await u.clear(await screen.findByLabelText(/^Username/));
    await u.type(screen.getByLabelText(/^Username/), 'bob.brown');
    await u.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Changes saved')).toBeInTheDocument();
    expect(api.sent.map((s) => [s.method, s.url, s.body])).toEqual([
      ['PUT', `/api/directories/def-staff/entry?dn=${encodeURIComponent(BOB)}`, { values: { uid: ['bob.brown'] } }],
    ]);
    const renamed = `uid=bob.brown,ou=people,${B}`;
    await waitFor(() => expect(where()).toBe(`/d/staff/entry?dn=${renamed}`));
    expect(await screen.findByTitle(renamed)).toBeInTheDocument();
  });

  it('offers no edit or delete to someone who may only read, even with ?edit=1', async () => {
    const api = server({ who: user('alice', ALICE, 'Alice Anderson'), staffAccess: 'read' });
    renderAt(entryUrl('staff', BOB) + '&edit=1');

    expect(await screen.findByRole('heading', { name: 'Bob Brown' })).toBeInTheDocument();
    expect(screen.getByText('Partner Manager')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument();
    expect(api.sent).toEqual([]);
  });

  it('names the entry before deleting it, deletes that DN and returns to the directory', async () => {
    const u = userEvent.setup();
    const api = server({ who: user('erin', `uid=erin,ou=people,${B}`, 'Erin Evans'), staffAccess: 'write' });
    renderAt(entryUrl('staff', BOB));

    await u.click(await screen.findByRole('button', { name: 'Delete' }));
    const dialog = screen.getByRole('dialog', { name: 'Delete entry?' });
    expect(dialog).toHaveTextContent(`This permanently removes Bob Brown (${BOB}) from the directory.`);
    expect(api.sent).toEqual([]);
    await u.click(within(dialog).getByRole('button', { name: 'Delete' }));

    expect(await screen.findByText('Bob Brown deleted')).toBeInTheDocument();
    expect(api.sent).toEqual([{ method: 'DELETE', url: `/api/directories/def-staff/entry?dn=${encodeURIComponent(BOB)}`, body: undefined, csrf: '1' }]);
    await waitFor(() => expect(where()).toBe('/d/staff'));
    expect(api.entries.has(BOB)).toBe(false);
    expect(api.entries.has(ALICE)).toBe(true);
  });

  it('changes group members in bulk, one per line, and reports what changed', async () => {
    const u = userEvent.setup();
    const api = server({ who: user('erin', `uid=erin,ou=people,${B}`, 'Erin Evans'), staffAccess: 'write' });
    renderAt(entryUrl('groups', ENGINEERING));

    await u.click(await screen.findByRole('button', { name: 'Change members in bulk' }));
    const dialog = screen.getByRole('dialog', { name: 'Change members' });
    await u.type(within(dialog).getByLabelText('Add'), `  ${BOB}  {Enter}{Enter}`);
    await u.click(within(dialog).getByRole('button', { name: 'Apply' }));

    expect(await screen.findByText('1 added, 0 removed.')).toBeInTheDocument();
    expect(api.sent).toEqual([
      {
        method: 'POST',
        url: `/api/directories/def-groups/members?dn=${encodeURIComponent(ENGINEERING)}`,
        body: { attribute: 'member', add: [BOB], remove: [] },
        csrf: '1',
      },
    ]);
    expect(api.entries.get(ENGINEERING)?.attributes.member).toEqual([ALICE, BOB]);
  });
});

describe('My profile', () => {
  it('lets a user change only their self-service fields, on their own entry', async () => {
    const u = userEvent.setup();
    const api = server({ who: user('alice', ALICE, 'Alice Anderson'), staffAccess: 'read' });
    renderAt('/me');

    await u.click(await screen.findByRole('button', { name: 'Edit my details' }));
    // Only what the user may change is offered.
    expect(screen.getByLabelText('Mobile value 1')).toHaveValue('+1 555 0101');
    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Badge number')).not.toBeInTheDocument();

    await u.clear(screen.getByLabelText('Mobile value 1'));
    await u.type(screen.getByLabelText('Mobile value 1'), '+1 555 9999');
    await u.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Your details were updated')).toBeInTheDocument();
    expect(api.sent).toEqual([
      { method: 'PUT', url: `/api/me/def-staff/entry?dn=${encodeURIComponent(ALICE)}`, body: { values: { mobile: ['+1 555 9999'] } }, csrf: '1' },
    ]);
    expect(await screen.findByText('+1 555 9999')).toBeInTheDocument();
    expect(api.entries.get(ALICE)?.attributes.title).toEqual(['Senior Software Engineer']);
  });
});
