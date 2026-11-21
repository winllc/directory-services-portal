import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Job, PendingResponse } from '@dsp/shared';
import { jobResult } from '../hooks';

/*
 * Waiting for a background import: a status poll or the "seen" call that fails once (a replica
 * restarting) does not turn a finished import into a failed one.
 */

const job = (status: Job['status']): Job => ({ id: 'j1', status }) as Job;
const pending: PendingResponse = { pending: true, job: job('queued') };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });

/** Answers each request in turn from `answers` (by path), recording the paths asked. */
function stub(answers: Record<string, (() => Response)[]>) {
  const asked: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      const path = url.split('?')[0];
      asked.push(path);
      const next = answers[path]?.shift();
      if (!next) throw new Error(`unexpected ${path}`);
      return next();
    }),
  );
  return asked;
}

afterEach(() => vi.unstubAllGlobals());

describe('jobResult', () => {
  it('rides out a failed poll and a failed "seen" call', async () => {
    const asked = stub({
      '/api/jobs/j1': [() => json({ error: 'Bad gateway' }, 502), () => json(job('running')), () => json(job('succeeded'))],
      '/api/jobs/j1/result': [() => json({ result: { created: 3 } })],
      '/api/jobs/seen': [() => json({ error: 'license_read_only' }, 403)],
    });
    await expect(jobResult(pending, 0)).resolves.toEqual({ created: 3 });
    await vi.waitFor(() => expect(asked).toContain('/api/jobs/seen'));
  });

  it('gives up after repeated failures without calling the import failed', async () => {
    stub({ '/api/jobs/j1': Array.from({ length: 10 }, () => () => json({ error: 'Bad gateway' }, 502)) });
    await expect(jobResult(pending, 0)).rejects.toThrow(/may still be running.*Check Jobs/);
  });

  it('reports a job that failed on the server', async () => {
    stub({ '/api/jobs/j1': [() => json({ ...job('failed'), error: 'directory unavailable' })] });
    await expect(jobResult(pending, 0)).rejects.toThrow('The import failed: directory unavailable');
  });
});
