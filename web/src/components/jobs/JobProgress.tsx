import { useEffect, useState } from 'react';
import { Loader2, X } from 'lucide-react';
import type { Job } from '@dsp/shared';
import { useJobMutations } from '../../api/hooks';
import { Button, cx } from '../ui';
import { compact } from '../charts';

export function elapsed(from: string | undefined, to?: string): string {
  if (!from) return '';
  const s = Math.max(0, Math.round(((to ? new Date(to).getTime() : Date.now()) - new Date(from).getTime()) / 1000));
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  return m < 60 ? `${m} min ${s % 60} s` : `${Math.floor(m / 60)} h ${m % 60} min`;
}

/** Re-render every second (elapsed times). */
export function useTick(active: boolean) {
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!active) return;
    const t = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, [active]);
}

/** Determinate when the expected size is known (from an earlier scan), otherwise indeterminate. */
export function ProgressBar({ job }: { job: Job }) {
  const share = job.expectedEntries ? Math.min(1, job.entries / job.expectedEntries) : undefined;
  return (
    <div
      className={cx('job-bar', share === undefined && 'job-bar-indeterminate')}
      role="progressbar"
      aria-label={job.title}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={share === undefined ? undefined : Math.round(share * 100)}
    >
      <div className="job-bar-fill" style={share === undefined ? undefined : { width: `${share * 100}%` }} />
    </div>
  );
}

export function jobDetail(job: Job): string {
  const parts: string[] = [];
  if (job.status === 'queued') parts.push('Waiting for a free worker');
  else if (job.phase) parts.push(job.phase);
  if (job.entries) parts.push(`${compact(job.entries)}${job.expectedEntries ? ` of ~${compact(job.expectedEntries)}` : ''} entries read`);
  if (job.startedAt) parts.push(elapsed(job.startedAt));
  return parts.join(' · ');
}

/**
 * A computation still running on the server: what it is doing, how far along it is, and that
 * the user may leave (they're notified when it's ready).
 */
export function JobProgress({ job, label = 'Computing on the server' }: { job: Job; label?: string }) {
  const { cancel } = useJobMutations();
  useTick(true);
  return (
    <div className="job-progress" role="status" aria-live="polite">
      <Loader2 size={18} className="spin" aria-hidden />
      <div className="job-progress-main">
        <div className="job-progress-title">
          <strong>{label}</strong> <span className="muted small">{jobDetail(job)}</span>
        </div>
        <ProgressBar job={job} />
        <div className="muted small">This continues if you leave the page; you'll be notified when it's ready.</div>
      </div>
      <Button size="sm" variant="ghost" icon={<X size={14} />} loading={cancel.isPending} onClick={() => cancel.mutate(job.id)}>
        Cancel
      </Button>
    </div>
  );
}
