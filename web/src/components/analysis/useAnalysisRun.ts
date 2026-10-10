import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { AnalysisResult } from '@dsp/shared';
import { jobActive } from '@dsp/shared';
import { keys, useJob, useJobMutations, useJobResult, useJobs } from '../../api/hooks';
import { watchJob } from '../../lib/jobWatch';

/**
 * Follows the background run of an analysis on its page: the run started here, or one of the
 * saved analysis already in progress (e.g. started before navigating away and back). When a saved
 * run finishes, its stored result and "last run" are reloaded; a draft run's result comes from
 * the job itself.
 */
export function useAnalysisRun(savedId: string | undefined) {
  const qc = useQueryClient();
  const jobs = useJobs();
  const { seen } = useJobMutations();
  const [startedId, setStartedId] = useState<string>();
  const running = savedId ? jobs.data?.find((j) => j.target?.type === 'analysis' && j.target.id === savedId && jobActive(j)) : undefined;
  const trackedId = startedId ?? running?.id;
  const job = useJob(trackedId);
  const status = job.data?.status;
  const isDraft = job.data?.target?.type === 'analysis-draft';
  const draft = useJobResult<AnalysisResult>(trackedId, status === 'succeeded' && isDraft);

  useEffect(() => (trackedId ? watchJob(trackedId) : undefined), [trackedId]);
  useEffect(() => {
    if (!trackedId || !status || jobActive({ status })) return;
    if (status === 'succeeded' && !isDraft && savedId) {
      qc.invalidateQueries({ queryKey: keys.analysisResult(savedId) });
      qc.invalidateQueries({ queryKey: keys.analysis(savedId) });
      qc.invalidateQueries({ queryKey: keys.analyses });
    }
    seen.mutate([trackedId]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [trackedId, status]);

  return {
    job: job.data,
    running: !!job.data && jobActive(job.data),
    isDraft,
    draftResult: draft.data?.result,
    start: (jobId: string) => setStartedId(jobId),
  };
}
