import { afterEach, describe, expect, it, vi } from 'vitest';
import { downloadEntries, downloadMembers } from '../hooks';

interface Saved {
  name: string;
}

/** Stubs fetch with one answer and records the file the browser was handed. */
function setUp(response: Response) {
  const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => response);
  vi.stubGlobal('fetch', fetchMock);
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:x'), revokeObjectURL: vi.fn() }));
  const saved: Saved[] = [];
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    saved.push({ name: this.download });
  });
  return { fetchMock, saved };
}

const file = (body: string, disposition: string | null, headers: Record<string, string> = {}) =>
  new Response(body, { status: 200, headers: { ...(disposition ? { 'Content-Disposition': disposition } : {}), ...headers } });

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('exports', () => {
  it('posts the export with the CSRF header and saves it under the server-given name', async () => {
    const { fetchMock, saved } = setUp(
      file('dn,uid\r\n', 'attachment; filename="staff-2026-10-21.csv"', { 'X-DSP-Export-Count': '7', 'X-DSP-Export-Truncated': 'true' }),
    );
    const r = await downloadEntries('def-staff', 'csv', 'ali ce');
    expect(r).toEqual({ count: 7, truncated: true });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/directories/def-staff/export?format=csv&q=ali+ce');
    expect(init?.method).toBe('POST');
    expect(init?.credentials).toBe('same-origin');
    expect((init?.headers as Record<string, string>)['X-DSP-Request']).toBe('1');
    expect(saved).toEqual([{ name: 'staff-2026-10-21.csv' }]);
  });

  it('posts a group-member export and falls back to a generic name', async () => {
    const { fetchMock, saved } = setUp(file('member\r\n', null));
    await downloadMembers('def-groups', 'cn=eng,ou=groups,dc=x', 'ldif', 'member');
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/directories/def-groups/members/export?dn=cn%3Deng%2Cou%3Dgroups%2Cdc%3Dx&attribute=member&format=ldif');
    expect(init?.method).toBe('POST');
    expect((init?.headers as Record<string, string>)['X-DSP-Request']).toBe('1');
    expect(saved).toEqual([{ name: 'members.ldif' }]);
  });

  it('reports the server error and saves nothing', async () => {
    const { saved } = setUp(new Response(JSON.stringify({ error: 'You do not have access to this directory' }), { status: 403 }));
    await expect(downloadEntries('def-partners', 'csv')).rejects.toThrow('You do not have access to this directory');
    expect(saved).toEqual([]);
  });
});
