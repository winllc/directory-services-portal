import { useEffect, useRef } from 'react';
import { useMutation, useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query';
import type {
  AnalysisDefinition,
  AnalysisInput,
  AttributeTypeDef,
  Dashboard,
  DashboardSummary,
  DirectorySummaryRow,
  DropdownSource,
  OptionPage,
  Job,
  LatestAnalysisResult,
  PendingResponse,
  MetricOutcome,
  MetricSource,
  MetricWidget,
  OverviewResponse,
  AuditPage,
  CustomSchema,
  DefinitionSummary,
  DirectoryDefinition,
  DirectoryEntry,
  EntryListResponse,
  FindingsFilters,
  FindingsPage,
  FormDefinition,
  ObjectClassDef,
  OptionItem,
  PermissionGrant,
  SchemaSnapshot,
  SelfEntryResult,
  ServerInfo,
  SubjectSearchResult,
} from '@dsp/shared';
import { isPending, jobActive } from '@dsp/shared';
import { api, dnParam, qs } from './client';
import { watchJob } from '../lib/jobWatch';
import { saveBlob } from '../lib/csv';

export const keys = {
  info: ['info'] as const,
  definitions: ['definitions'] as const,
  definition: (id: string) => ['definition', id] as const,
  entries: (id: string, params: object) => ['entries', id, params] as const,
  entry: (id: string, dn: string) => ['entry', id, dn] as const,
  options: (id: string, fieldId: string) => ['options', id, fieldId] as const,
  me: ['me-entries'] as const,
  schema: ['admin', 'schema'] as const,
  forms: ['admin', 'forms'] as const,
  form: (id: string) => ['admin', 'form', id] as const,
  adminDefinitions: ['admin', 'definitions'] as const,
  grants: ['admin', 'grants'] as const,
  subjects: (q: string, type?: string) => ['admin', 'subjects', q, type] as const,
  browse: (base: string) => ['admin', 'browse', base] as const,
  audit: (params: object) => ['admin', 'audit', params] as const,
  metricSources: ['metrics', 'sources'] as const,
  metricSummary: ['metrics', 'summary'] as const,
  overview: (id: string) => ['metrics', 'overview', id] as const,
  dashboards: ['metrics', 'dashboards'] as const,
  dashboard: (id: string) => ['metrics', 'dashboard', id] as const,
  dashboardResults: (id: string) => ['metrics', 'dashboard-results', id] as const,
  evaluate: (widgets: MetricWidget[]) => ['metrics', 'evaluate', widgets] as const,
  analyses: ['admin', 'analyses'] as const,
  analysis: (id: string) => ['admin', 'analysis', id] as const,
  analysisResult: (id: string) => ['admin', 'analysis-result', id] as const,
  jobs: ['jobs'] as const,
  job: (id: string) => ['jobs', id] as const,
  jobResult: (id: string) => ['jobs', id, 'result'] as const,
};

export const useServerInfo = () => useQuery({ queryKey: keys.info, queryFn: () => api.get<ServerInfo>('/api/info'), staleTime: 60_000 });

export const useDefinitions = () =>
  useQuery({ queryKey: keys.definitions, queryFn: () => api.get<DefinitionSummary[]>('/api/definitions') });

export const useDefinition = (id: string | undefined) =>
  useQuery({
    queryKey: keys.definition(id ?? ''),
    queryFn: () => api.get<{ definition: DefinitionSummary; form: FormDefinition }>(`/api/definitions/${encodeURIComponent(id!)}`),
    enabled: !!id,
  });

export interface EntryQuery {
  q?: string;
  page?: number;
  pageSize?: number;
  sort?: string;
  order?: 'asc' | 'desc';
}

/** Entries of a directory; waits until the definition id is known (undefined = not loaded). */
export const useEntries = (id: string | undefined, params: EntryQuery) =>
  useQuery({
    queryKey: keys.entries(id ?? '', params),
    queryFn: () =>
      api.get<EntryListResponse>(`/api/directories/${encodeURIComponent(id!)}/entries${qs({ ...params })}`),
    placeholderData: (prev) => prev,
    enabled: !!id,
  });

export const useEntry = (id: string | undefined, dn: string | null) =>
  useQuery({
    queryKey: keys.entry(id ?? '', dn ?? ''),
    queryFn: () => api.get<DirectoryEntry>(`/api/directories/${encodeURIComponent(id!)}/entry?${dnParam(dn!)}`),
    enabled: !!id && !!dn,
  });

const optionsUrl = (definitionId: string, fieldId: string) =>
  `/api/directories/${encodeURIComponent(definitionId)}/options/${encodeURIComponent(fieldId)}`;

/** A drop down's options (directory-search drop downs in list mode: up to their limit). */
export const useFieldOptions = (definitionId: string, fieldId: string, enabled: boolean) =>
  useQuery({
    queryKey: keys.options(definitionId, fieldId),
    queryFn: () => api.get<OptionPage>(optionsUrl(definitionId, fieldId)),
    enabled,
    staleTime: 30_000,
  });

/** Options matching typed text, searched in the directory. Keeps the previous results while typing. */
export const useOptionSearch = (definitionId: string, fieldId: string, q: string, enabled: boolean) =>
  useQuery({
    queryKey: [...keys.options(definitionId, fieldId), 'q', q],
    queryFn: () => api.get<OptionPage>(`${optionsUrl(definitionId, fieldId)}${qs({ q })}`),
    enabled,
    staleTime: 30_000,
    placeholderData: (prev) => prev,
  });

/** Labels (and descriptions) of stored values, looked up without loading every option. */
export const useOptionLabels = (definitionId: string, fieldId: string, values: string[], enabled: boolean) =>
  useQuery({
    queryKey: [...keys.options(definitionId, fieldId), 'values', [...values].sort()],
    queryFn: () => {
      const p = new URLSearchParams();
      values.forEach((v) => p.append('value', v));
      return api.get<OptionPage>(`${optionsUrl(definitionId, fieldId)}?${p.toString()}`);
    },
    enabled: enabled && values.length > 0,
    staleTime: 60_000,
  });

export interface OptionPreview {
  options: OptionItem[];
  truncated: boolean;
  filter?: string;
  durationMs: number;
  error?: string;
}

/** Form editor: try a directory-search drop down's (unsaved) settings. */
export const useOptionPreview = (source: DropdownSource | undefined, q: string, enabled: boolean) =>
  useQuery({
    queryKey: ['admin', 'option-preview', source, q],
    queryFn: () => api.post<OptionPreview>('/api/admin/forms/preview-options', { source, q: q || undefined }),
    enabled: enabled && source?.type === 'ldap' && !!source.baseDn,
    placeholderData: (prev) => prev,
    staleTime: 10_000,
    retry: false,
  });

export const useMyEntries = () => useQuery({ queryKey: keys.me, queryFn: () => api.get<SelfEntryResult[]>('/api/me/entries') });

export type Values = Record<string, string[]>;

export function useEntryMutations(definitionId: string) {
  const qc = useQueryClient();
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['entries', definitionId] });
    qc.invalidateQueries({ queryKey: ['entry', definitionId] });
    qc.invalidateQueries({ queryKey: keys.me });
  };
  const base = `/api/directories/${encodeURIComponent(definitionId)}`;
  return {
    create: useMutation({
      mutationFn: (input: { parentDn?: string; values: Values }) => api.post<DirectoryEntry>(`${base}/entries`, input),
      onSuccess: invalidate,
    }),
    update: useMutation({
      mutationFn: (input: { dn: string; values: Values }) =>
        api.put<DirectoryEntry>(`${base}/entry?${dnParam(input.dn)}`, { values: input.values }),
      onSuccess: invalidate,
    }),
    remove: useMutation({
      mutationFn: (dn: string) => api.del(`${base}/entry?${dnParam(dn)}`),
      onSuccess: invalidate,
    }),
  };
}

export function useSelfUpdate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: { definitionId: string; dn: string; values: Values }) =>
      api.put<DirectoryEntry>(`/api/me/${encodeURIComponent(input.definitionId)}/entry?${dnParam(input.dn)}`, {
        values: input.values,
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.me });
      qc.invalidateQueries({ queryKey: ['entries'] });
      qc.invalidateQueries({ queryKey: ['entry'] });
    },
  });
}

// ---------------------------------------------------------------------------
// Admin
// ---------------------------------------------------------------------------

export interface AdminSchemaResponse {
  merged: SchemaSnapshot;
  custom: CustomSchema;
  server: { fetchedAt?: string; subschemaDn?: string; attributeTypes: number; objectClasses: number } | null;
}

export const useAdminSchema = (enabled = true) =>
  useQuery({ queryKey: keys.schema, queryFn: () => api.get<AdminSchemaResponse>('/api/admin/schema'), enabled, staleTime: 30_000 });

export function useSchemaMutations() {
  const qc = useQueryClient();
  const onSuccess = () => qc.invalidateQueries({ queryKey: keys.schema });
  return {
    refresh: useMutation({ mutationFn: () => api.post<{ fetchedAt: string; attributeTypes: number; objectClasses: number }>('/api/admin/schema/refresh'), onSuccess }),
    importText: useMutation({
      mutationFn: (text: string) => api.post<{ attributeTypes: number; objectClasses: number; errors: string[] }>('/api/admin/schema/import', { text }),
      onSuccess,
    }),
    saveAttribute: useMutation({
      mutationFn: (input: { definition: Omit<AttributeTypeDef, 'source'>; originalOid?: string }) => api.put<AttributeTypeDef>('/api/admin/schema/attribute-types', input),
      onSuccess,
    }),
    deleteAttribute: useMutation({ mutationFn: (oid: string) => api.del(`/api/admin/schema/attribute-types/${encodeURIComponent(oid)}`), onSuccess }),
    saveClass: useMutation({
      mutationFn: (input: { definition: Omit<ObjectClassDef, 'source'>; originalOid?: string }) => api.put<ObjectClassDef>('/api/admin/schema/object-classes', input),
      onSuccess,
    }),
    deleteClass: useMutation({ mutationFn: (oid: string) => api.del(`/api/admin/schema/object-classes/${encodeURIComponent(oid)}`), onSuccess }),
  };
}

export const useForms = () => useQuery({ queryKey: keys.forms, queryFn: () => api.get<FormDefinition[]>('/api/admin/forms') });

export type FormInput = Omit<FormDefinition, 'id' | 'createdAt' | 'updatedAt'>;

export function useFormMutations() {
  const qc = useQueryClient();
  const onSuccess = () => {
    qc.invalidateQueries({ queryKey: ['admin', 'forms'] });
    qc.invalidateQueries({ queryKey: ['admin', 'form'] });
    qc.invalidateQueries({ queryKey: ['definition'] });
    qc.invalidateQueries({ queryKey: keys.me });
  };
  return {
    create: useMutation({ mutationFn: (f: FormInput) => api.post<{ form: FormDefinition; warnings: string[] }>('/api/admin/forms', f), onSuccess }),
    update: useMutation({
      mutationFn: ({ id, form }: { id: string; form: FormInput }) => api.put<{ form: FormDefinition; warnings: string[] }>(`/api/admin/forms/${id}`, form),
      onSuccess,
    }),
    remove: useMutation({ mutationFn: (id: string) => api.del(`/api/admin/forms/${id}`), onSuccess }),
  };
}

export const useAdminDefinitions = () =>
  useQuery({ queryKey: keys.adminDefinitions, queryFn: () => api.get<DirectoryDefinition[]>('/api/admin/definitions') });

export type DefinitionInput = Omit<DirectoryDefinition, 'id' | 'createdAt' | 'updatedAt'>;

export function useDefinitionMutations() {
  const qc = useQueryClient();
  const onSuccess = () => {
    qc.invalidateQueries({ queryKey: keys.adminDefinitions });
    qc.invalidateQueries({ queryKey: keys.definitions });
    qc.invalidateQueries({ queryKey: ['definition'] });
    qc.invalidateQueries({ queryKey: keys.grants });
    qc.invalidateQueries({ queryKey: keys.me });
  };
  return {
    create: useMutation({ mutationFn: (d: DefinitionInput) => api.post<DirectoryDefinition>('/api/admin/definitions', d), onSuccess }),
    update: useMutation({
      mutationFn: ({ id, def }: { id: string; def: DefinitionInput }) => api.put<DirectoryDefinition>(`/api/admin/definitions/${id}`, def),
      onSuccess,
    }),
    remove: useMutation({ mutationFn: (id: string) => api.del(`/api/admin/definitions/${id}`), onSuccess }),
  };
}

export const useGrants = () => useQuery({ queryKey: keys.grants, queryFn: () => api.get<PermissionGrant[]>('/api/admin/grants') });

export function useGrantMutations() {
  const qc = useQueryClient();
  const onSuccess = () => {
    qc.invalidateQueries({ queryKey: keys.grants });
    qc.invalidateQueries({ queryKey: keys.definitions });
  };
  return {
    create: useMutation({
      mutationFn: (g: Omit<PermissionGrant, 'id' | 'createdAt'>) => api.post<PermissionGrant>('/api/admin/grants', g),
      onSuccess,
    }),
    remove: useMutation({ mutationFn: (id: string) => api.del(`/api/admin/grants/${id}`), onSuccess }),
  };
}

export const useSubjects = (q: string, type?: 'user' | 'group') =>
  useQuery({
    queryKey: keys.subjects(q, type),
    queryFn: () => api.get<SubjectSearchResult[]>(`/api/admin/subjects${qs({ q, type })}`),
    placeholderData: (prev) => prev,
  });

export interface BrowseResult {
  base: string;
  children: { dn: string; name: string; objectClasses: string[] }[];
  truncated: boolean;
}

export const useBrowse = (base: string, enabled: boolean) =>
  useQuery({ queryKey: keys.browse(base), queryFn: () => api.get<BrowseResult>(`/api/admin/browse${qs({ base })}`), enabled });

export const previewSearch = (input: { baseDn: string; scope: 'base' | 'one' | 'sub'; filter: string; attributes?: string[] }) =>
  api.post<{ entries: DirectoryEntry[]; truncated: boolean }>('/api/admin/preview', input);

export interface AuditQuery {
  q?: string;
  action?: string;
  outcome?: string;
  actor?: string;
  target?: string;
  from?: string;
  to?: string;
  page?: number;
  pageSize?: number;
}

export const useAudit = (params: AuditQuery) =>
  useQuery({
    queryKey: keys.audit(params),
    queryFn: () => api.get<AuditPage>(`/api/admin/audit${qs({ ...params })}`),
    placeholderData: (prev) => prev,
  });

// ---------------------------------------------------------------------------
// Background jobs
// ---------------------------------------------------------------------------

/** The user's background computations; polled quickly while any is running. */
export const useJobs = (enabled = true) =>
  useQuery({
    queryKey: keys.jobs,
    queryFn: () => api.get<Job[]>('/api/jobs'),
    enabled,
    refetchInterval: (q) => (q.state.data?.some(jobActive) ? 1500 : 15_000),
    refetchIntervalInBackground: true,
    staleTime: 1000,
  });

/** One job, polled every second until it finishes. */
export const useJob = (id: string | undefined) =>
  useQuery({
    queryKey: keys.job(id ?? ''),
    queryFn: () => api.get<Job>(`/api/jobs/${encodeURIComponent(id!)}`),
    enabled: !!id,
    refetchInterval: (q) => (q.state.data && !jobActive(q.state.data) ? false : 1000),
    refetchIntervalInBackground: true,
  });

export const useJobResult = <T,>(id: string | undefined, enabled = true) =>
  useQuery({
    queryKey: keys.jobResult(id ?? ''),
    queryFn: () => api.get<{ job: Job; result: T }>(`/api/jobs/${encodeURIComponent(id!)}/result`),
    enabled: !!id && enabled,
    staleTime: Infinity,
  });

export function useJobMutations() {
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: keys.jobs });
  return {
    cancel: useMutation({ mutationFn: (id: string) => api.post<Job>(`/api/jobs/${encodeURIComponent(id)}/cancel`), onSuccess: refresh }),
    seen: useMutation({ mutationFn: (ids: string[]) => api.post('/api/jobs/seen', { ids }), onSuccess: refresh }),
    dismiss: useMutation({ mutationFn: (id: string) => api.del(`/api/jobs/${encodeURIComponent(id)}`), onSuccess: refresh }),
  };
}

/**
 * A query whose answer may come from a background job: the endpoint returns the data (200), or
 * the job computing it (202). While the job runs, {@code job} reports its progress; when it
 * succeeds its result replaces the pending answer in the cache, and the job is marked seen (this
 * page showed it, so the global notifier stays quiet).
 */
export function useBackgroundQuery<T>(queryKey: QueryKey, fetcher: () => Promise<T | PendingResponse>, opts: { enabled?: boolean; staleTime?: number } = {}) {
  const qc = useQueryClient();
  const query = useQuery<T | PendingResponse>({
    queryKey,
    queryFn: fetcher,
    enabled: opts.enabled ?? true,
    staleTime: opts.staleTime ?? 30_000,
    placeholderData: (prev) => prev,
    retry: false,
  });
  const pending = isPending(query.data) ? query.data.job : undefined;
  const job = useJob(pending?.id);
  const current = job.data ?? pending;
  const keyRef = useRef(queryKey);
  keyRef.current = queryKey;

  useEffect(() => (pending ? watchJob(pending.id) : undefined), [pending]);
  useEffect(() => {
    if (!current || current.status !== 'succeeded') return;
    let alive = true;
    api
      .get<{ result: T }>(`/api/jobs/${encodeURIComponent(current.id)}/result`)
      .then((r) => {
        if (!alive) return;
        qc.setQueryData(keyRef.current, r.result);
        api.post('/api/jobs/seen', { ids: [current.id] }).then(() => qc.invalidateQueries({ queryKey: keys.jobs }));
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [current?.id, current?.status, qc]);

  const data = isPending(query.data) ? undefined : (query.data as T | undefined);
  const jobError = current && (current.status === 'failed' || current.status === 'cancelled')
    ? new Error(current.status === 'cancelled' ? 'The computation was cancelled.' : `The computation failed: ${current.error ?? 'unknown error'}`)
    : null;
  return {
    data,
    job: current && jobActive(current) ? current : undefined,
    error: query.error ?? jobError,
    isLoading: query.isLoading,
    isFetching: query.isFetching || (!!current && jobActive(current)),
    /** Recompute on the server (bypassing caches); the answer may again be a job. */
    refresh: async (fetchFresh: () => Promise<T | PendingResponse>) => {
      const r = await fetchFresh();
      qc.setQueryData(queryKey, r);
    },
  };
}

// ---------------------------------------------------------------------------
// Metrics
// ---------------------------------------------------------------------------

/** Results are cached on the server for a minute; "refresh" recomputes them. */
const withRefresh = (url: string, refresh: boolean) => (refresh ? `${url}${url.includes('?') ? '&' : '?'}refresh=true` : url);

export const useMetricSources = () =>
  useQuery({ queryKey: keys.metricSources, queryFn: () => api.get<MetricSource[]>('/api/metrics/sources'), staleTime: 60_000 });

const summaryUrl = (refresh = false) => withRefresh('/api/metrics/summary', refresh);
export const useMetricSummary = () =>
  useBackgroundQuery<DirectorySummaryRow[]>(keys.metricSummary, () => api.get(summaryUrl()));
export const fetchSummary = (refresh: boolean) => api.get<DirectorySummaryRow[] | PendingResponse>(summaryUrl(refresh));

const overviewUrl = (id: string, refresh = false) => withRefresh(`/api/metrics/overview/${encodeURIComponent(id)}`, refresh);
export const useOverview = (id: string | undefined) =>
  useBackgroundQuery<OverviewResponse>(keys.overview(id ?? ''), () => api.get(overviewUrl(id!)), { enabled: !!id });
export const fetchOverview = (id: string, refresh: boolean) => api.get<OverviewResponse | PendingResponse>(overviewUrl(id, refresh));

export const useDashboards = () =>
  useQuery({ queryKey: keys.dashboards, queryFn: () => api.get<DashboardSummary[]>('/api/metrics/dashboards') });

export const useDashboard = (id: string | undefined) =>
  useQuery({
    queryKey: keys.dashboard(id ?? ''),
    queryFn: () => api.get<Dashboard>(`/api/metrics/dashboards/${encodeURIComponent(id!)}`),
    enabled: !!id,
  });

type DashboardResults = { results: Record<string, MetricOutcome> };
const dashboardUrl = (id: string, refresh = false) => withRefresh(`/api/metrics/dashboards/${encodeURIComponent(id)}/results`, refresh);
export const useDashboardResults = (id: string | undefined, enabled = true) =>
  useBackgroundQuery<DashboardResults>(keys.dashboardResults(id ?? ''), () => api.get(dashboardUrl(id!)), { enabled: !!id && enabled });
export const fetchDashboardResults = (id: string, refresh: boolean) => api.get<DashboardResults | PendingResponse>(dashboardUrl(id, refresh));

/**
 * Compute ad-hoc widgets (explorer, editor preview). If it takes long, it continues on the
 * server and {@code returnTo} is where the "ready" notification leads.
 */
export const useEvaluate = (widgets: MetricWidget[], enabled = true, returnTo?: string) =>
  useBackgroundQuery<Record<string, MetricOutcome>>(
    keys.evaluate(widgets),
    () => api.post('/api/metrics/evaluate', { widgets, returnTo }),
    { enabled: enabled && widgets.length > 0 },
  );

export interface DashboardInput {
  name: string;
  description?: string;
  shared: boolean;
  widgets: MetricWidget[];
}

export function useDashboardMutations() {
  const qc = useQueryClient();
  const invalidate = () => qc.invalidateQueries({ queryKey: ['metrics'] });
  return {
    create: useMutation({ mutationFn: (d: DashboardInput) => api.post<Dashboard>('/api/admin/dashboards', d), onSuccess: invalidate }),
    update: useMutation({
      mutationFn: ({ id, ...d }: DashboardInput & { id: string }) => api.put<Dashboard>(`/api/admin/dashboards/${encodeURIComponent(id)}`, d),
      onSuccess: invalidate,
    }),
    remove: useMutation({ mutationFn: (id: string) => api.del(`/api/admin/dashboards/${encodeURIComponent(id)}`), onSuccess: invalidate }),
  };
}

// ---------------------------------------------------------------------------
// Analysis
// ---------------------------------------------------------------------------

export const useAnalyses = () =>
  useQuery({ queryKey: keys.analyses, queryFn: () => api.get<AnalysisDefinition[]>('/api/admin/analysis') });

export const useAnalysis = (id: string | undefined) =>
  useQuery({
    queryKey: keys.analysis(id ?? ''),
    queryFn: () => api.get<AnalysisDefinition>(`/api/admin/analysis/${encodeURIComponent(id!)}`),
    enabled: !!id,
  });

/**
 * Where a result's findings are paged from: a saved analysis's latest result, or a run's job.
 * {@code version} (when it ran) keeps pages of an older run from being reused.
 */
export interface FindingsSource {
  url: string;
  version: string;
}

export const savedFindings = (id: string, version: string): FindingsSource => ({ url: `/api/admin/analysis/${encodeURIComponent(id)}/findings`, version });
export const runFindings = (jobId: string, version: string): FindingsSource => ({
  url: `/api/admin/analysis/results/${encodeURIComponent(jobId)}/findings`,
  version,
});

const findingParams = (f: FindingsFilters) => ({ q: f.q?.trim() || undefined, rule: f.rule, severity: f.severity, source: f.source, status: f.status });

/** One page of findings, filtered on the server. The previous page stays shown while the next loads. */
export const useAnalysisFindings = <T,>(source: FindingsSource | undefined, filters: FindingsFilters, page: number, pageSize: number) =>
  useQuery({
    queryKey: ['admin', 'analysis-findings', source?.url ?? '', source?.version ?? '', findingParams(filters), page, pageSize] as const,
    queryFn: () => api.get<FindingsPage<T>>(source!.url + qs({ ...findingParams(filters), page, pageSize })),
    enabled: !!source,
    placeholderData: (prev) => prev,
    staleTime: Infinity,
  });

/** Download every finding matching the filters as CSV (built on the server). */
export async function downloadFindingsCsv(source: FindingsSource, filters: FindingsFilters, filename: string) {
  const res = await fetch(`${source.url}.csv${qs(findingParams(filters))}`, { credentials: 'same-origin' });
  if (!res.ok) {
    let message = `Download failed (${res.status})`;
    try {
      message = ((await res.json()) as { error?: string }).error ?? message;
    } catch {
      // not JSON
    }
    throw new Error(message);
  }
  saveBlob(await res.blob(), filename);
}

/** The latest stored result of a saved analysis (null until it has run). */
export const useAnalysisResult = (id: string | undefined) =>
  useQuery({
    queryKey: keys.analysisResult(id ?? ''),
    queryFn: async () => (await api.get<LatestAnalysisResult | undefined>(`/api/admin/analysis/${encodeURIComponent(id!)}/result`)) ?? null,
    enabled: !!id,
    retry: false,
  });

export function useAnalysisMutations() {
  const qc = useQueryClient();
  const invalidate = () => qc.invalidateQueries({ queryKey: keys.analyses });
  return {
    create: useMutation({ mutationFn: (a: AnalysisInput) => api.post<AnalysisDefinition>('/api/admin/analysis', a), onSuccess: invalidate }),
    update: useMutation({
      mutationFn: ({ id, ...a }: AnalysisInput & { id: string }) => api.put<AnalysisDefinition>(`/api/admin/analysis/${encodeURIComponent(id)}`, a),
      onSuccess: (a) => {
        invalidate();
        qc.setQueryData(keys.analysis(a.id), a);
      },
    }),
    remove: useMutation({ mutationFn: (id: string) => api.del(`/api/admin/analysis/${encodeURIComponent(id)}`), onSuccess: invalidate }),
    /** Starts a saved analysis by id, or an unsaved draft, in the background; resolves to its job. */
    run: useMutation({
      mutationFn: (target: { id: string } | { draft: AnalysisInput }) =>
        'id' in target
          ? api.post<PendingResponse>(`/api/admin/analysis/${encodeURIComponent(target.id)}/run`)
          : api.post<PendingResponse>('/api/admin/analysis/run', target.draft),
      onSuccess: () => qc.invalidateQueries({ queryKey: keys.jobs }),
    }),
  };
}
