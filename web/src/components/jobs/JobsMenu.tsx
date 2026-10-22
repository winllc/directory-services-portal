import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { Bell, BellRing, CheckCircle2, CircleSlash, Loader2, OctagonAlert, X } from 'lucide-react';
import type { Job } from '@dsp/shared';
import { jobActive } from '@dsp/shared';
import { keys, useJobMutations, useJobs } from '../../api/hooks';
import { isWatched } from '../../lib/jobWatch';
import { cx, useToast } from '../ui';
import { ProgressBar, elapsed, jobDetail, useTick } from './JobProgress';

const KIND: Record<Job['kind'], string> = { analysis: 'Analysis', metrics: 'Metrics', import: 'Import' };

function StatusIcon({ job }: { job: Job }) {
  switch (job.status) {
    case 'succeeded':
      return <CheckCircle2 size={16} className="job-ok" aria-label="Ready" />;
    case 'failed':
      return <OctagonAlert size={16} className="job-failed" aria-label="Failed" />;
    case 'cancelled':
      return <CircleSlash size={16} className="muted" aria-label="Cancelled" />;
    default:
      return <Loader2 size={16} className="spin" aria-label="Running" />;
  }
}

function canNotify() {
  return typeof Notification !== 'undefined' && Notification.permission === 'granted';
}

/**
 * The bell in the top bar: the user's background computations, with progress while they run.
 * When one finishes it announces it (a toast with a link, plus a desktop notification when the
 * tab is in the background and the user allowed them), unless the page showing it is open.
 */
export function JobsMenu() {
  const jobs = useJobs();
  const qc = useQueryClient();
  const { cancel, seen, dismiss } = useJobMutations();
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const known = useRef<Map<string, Job['status']> | null>(null);
  const ref = useRef<HTMLDivElement>(null);
  const list = jobs.data ?? [];
  const running = list.filter(jobActive);
  const unseen = list.filter((j) => !jobActive(j) && !j.seen && j.status !== 'cancelled');
  useTick(open && running.length > 0);

  // Announce jobs that finish while the portal is open.
  useEffect(() => {
    if (!jobs.data) return;
    const prev = known.current;
    known.current = new Map(jobs.data.map((j) => [j.id, j.status]));
    if (!prev) return; // first load: already-finished jobs are only shown in the menu
    const announce: string[] = [];
    for (const j of jobs.data) {
      const before = prev.get(j.id);
      const justFinished = (before === undefined || before === 'queued' || before === 'running') && (j.status === 'succeeded' || j.status === 'failed');
      if (!justFinished) continue;
      // A saved analysis that finished has a new result and "last run" wherever it is shown.
      if (j.target?.type === 'analysis' && j.target.id) {
        qc.invalidateQueries({ queryKey: keys.analyses });
        qc.invalidateQueries({ queryKey: keys.analysis(j.target.id) });
        qc.invalidateQueries({ queryKey: keys.analysisResult(j.target.id) });
      }
      if (j.seen || isWatched(j.id)) continue;
      announce.push(j.id);
      const message = j.status === 'succeeded' ? `“${j.title}” is ready` : `“${j.title}” failed: ${j.error ?? 'unknown error'}`;
      toast.notify(j.status === 'succeeded' ? 'success' : 'danger', message, j.link ? { label: 'View', to: j.link } : undefined);
      if (document.hidden && canNotify()) {
        try {
          new Notification('Directory Portal', { body: message, tag: j.id });
        } catch {
          // Some browsers only allow notifications from a service worker.
        }
      }
    }
    if (announce.length) seen.mutate(announce);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jobs.data]);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const toggle = () => {
    const next = !open;
    setOpen(next);
    if (next && unseen.length) seen.mutate(unseen.map((j) => j.id));
  };

  const badge = running.length || unseen.length;
  return (
    <div className="jobs-menu" ref={ref}>
      <button
        type="button"
        className={cx('icon-btn', 'jobs-button', running.length > 0 && 'is-running')}
        onClick={toggle}
        aria-expanded={open}
        aria-haspopup="dialog"
        aria-label={`Background tasks${running.length ? `, ${running.length} running` : ''}${unseen.length ? `, ${unseen.length} new` : ''}`}
        title="Background tasks"
      >
        {running.length ? <Loader2 size={18} className="spin" /> : unseen.length ? <BellRing size={18} /> : <Bell size={18} />}
        {badge > 0 && <span className={cx('jobs-badge', !running.length && 'jobs-badge-new')}>{badge}</span>}
      </button>
      {open && (
        <div className="jobs-panel" role="dialog" aria-label="Background tasks">
          <div className="jobs-panel-head">
            <strong>Background tasks</strong>
            {typeof Notification !== 'undefined' && Notification.permission === 'default' && (
              <button type="button" className="link-btn small" onClick={() => Notification.requestPermission()}>
                Notify me on this device
              </button>
            )}
          </div>
          {list.length === 0 ? (
            <p className="muted small jobs-empty">Analyses and large metrics run here, on the server. You'll be notified when they're ready.</p>
          ) : (
            <ul className="jobs-list">
              {list.map((j) => (
                <li key={j.id} className={cx('jobs-item', !j.seen && !jobActive(j) && 'is-new')}>
                  <StatusIcon job={j} />
                  <div className="jobs-item-main">
                    <div className="jobs-item-title">
                      {j.link && j.status === 'succeeded' ? (
                        <Link to={j.link} onClick={() => setOpen(false)}>
                          {j.title}
                        </Link>
                      ) : (
                        j.title
                      )}
                    </div>
                    <div className="muted small">
                      {KIND[j.kind]} ·{' '}
                      {jobActive(j)
                        ? jobDetail(j)
                        : j.status === 'failed'
                          ? `Failed: ${j.error ?? 'unknown error'}`
                          : j.status === 'cancelled'
                            ? 'Cancelled'
                            : `Ready · took ${elapsed(j.startedAt, j.finishedAt)} · ${new Date(j.finishedAt ?? j.createdAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })}`}
                    </div>
                    {jobActive(j) && <ProgressBar job={j} />}
                  </div>
                  {jobActive(j) ? (
                    <button type="button" className="link-btn small" onClick={() => cancel.mutate(j.id)}>
                      Cancel
                    </button>
                  ) : (
                    <button type="button" className="icon-btn icon-btn-sm" aria-label={`Remove ${j.title}`} title="Remove" onClick={() => dismiss.mutate(j.id)}>
                      <X size={14} />
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
