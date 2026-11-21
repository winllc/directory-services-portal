import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { DirectoryEntry, FormDefinition, FormField } from '@dsp/shared';
import { App } from '../../App';
import { AuthProvider } from '../../lib/auth';
import { ToastProvider } from '../ui';

/*
 * A directory's list page with a column backed by a directory-search drop down (the manager): the
 * labels of the page's values come in one request for the whole column, not one per row.
 */

const B = 'dc=example,dc=com';
const person = (uid: string) => `uid=${uid},ou=people,${B}`;
const MANAGERS: Record<string, string> = { [person('carol')]: 'Carol Chen', [person('dave')]: 'Dave Diaz' };

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

const form: FormDefinition = {
  id: 'form-person',
  name: 'Person',
  objectClasses: ['inetOrgPerson'],
  rdnAttribute: 'uid',
  createdAt: '',
  updatedAt: '',
  fields: [
    field('f-cn', 'cn', 'Full name'),
    field('f-manager', 'manager', 'Manager', {
      widget: 'dropdown',
      dropdown: { type: 'ldap', baseDn: `ou=people,${B}`, scope: 'one', filter: '(objectClass=person)', valueAttribute: 'dn', labelAttribute: 'cn' },
    }),
  ],
};

const staff = {
  id: 'def-staff',
  slug: 'staff',
  name: 'Staff',
  formId: 'form-person',
  baseDn: B,
  scope: 'sub' as const,
  mode: 'readwrite' as const,
  everyoneCanRead: false,
  listAttributes: ['cn', 'manager'],
  searchAttributes: ['cn'],
  titleAttribute: 'cn',
  createContainers: [],
  selfMatch: { type: 'dn' },
  createdAt: '',
  updatedAt: '',
  access: 'read',
};

const entries: DirectoryEntry[] = ['alice', 'bob', 'erin', 'frank'].map((uid, i) => ({
  dn: person(uid),
  attributes: { cn: [`Person ${uid}`], manager: [i % 2 ? person('dave') : person('carol')] },
}));

const features = ['directory-editor', 'self-service-profile'].map((id) => ({ id, enabled: true }));

function server() {
  const optionRequests: string[] = [];
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      const [path, query = ''] = url.split('?');
      if (path === '/api/auth/me') {
        return json({ user: { username: 'erin', dn: person('erin'), displayName: 'Erin', groups: [], isAdmin: false, authMethod: 'password' }, consentRequired: false });
      }
      if (path === '/api/info') return json({ mode: 'memory', connected: true, version: '1' });
      if (path === '/api/license') return json({ mode: 'FULL', state: 'VALID', message: '', evaluation: false, features });
      if (path === '/api/definitions') return json([staff]);
      if (path === '/api/definitions/staff') return json({ definition: staff, form });
      if (path === '/api/directories/def-staff/entries') return json({ entries, total: entries.length, page: 1, pageSize: 25, truncated: false });
      if (path === '/api/directories/def-staff/options/f-manager') {
        optionRequests.push(query);
        const values = new URLSearchParams(query).getAll('value');
        return json({ options: values.map((v) => ({ value: v, label: MANAGERS[v] })), truncated: false });
      }
      return json([]);
    }),
  );
  return { optionRequests };
}

afterEach(() => vi.unstubAllGlobals());

describe('a directory list', () => {
  it('asks for the labels of a drop-down column once for the page', async () => {
    const api = server();
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={['/d/staff']}>
          <ToastProvider>
            <AuthProvider>
              <App />
            </AuthProvider>
          </ToastProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText('Person alice')).toBeInTheDocument();
    expect((await screen.findAllByText('Carol Chen')).length).toBe(2);
    expect(screen.getAllByText('Dave Diaz').length).toBe(2);
    expect(api.optionRequests).toHaveLength(1);
    expect(new URLSearchParams(api.optionRequests[0]).getAll('value')).toEqual([person('carol'), person('dave')]);
  });
});
