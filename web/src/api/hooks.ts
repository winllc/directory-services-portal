import { createContext, useContext, useEffect, useRef } from 'react';
import { useMutation, useQueries, useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query';
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
  AuditActions,
  AuditPage,
  AuditStatus,
  AuditToken,
  AuditVerification,
  ApiToken,
  ApiTokenSettings,
  GroupMembers,
  ImportResult,
  MembershipChangeResult,
  AccessDecisions,
  AccessRequest,
  AccessRequestView,
  CreatedGroup,
  GroupContainer,
  GroupDetail,
  GroupDirectory,
  GroupList,
  GroupMember,
  GroupMemberPage,
  GroupPeopleResolved,
  GroupPerson,
  GroupRequestHistory,
  GroupRequestSummary,
  NewGroupForm,
  Webhook,
  WebhookDelivery,
  WebhookInput,
  WebhookSettings,
  CustomSchema,
  DefinitionSummary,
  DirectoryDefinition,
  DirectoryEntry,
  EntryListResponse,
  LdapDirectoryListing,
  FindingsFilters,
  FindingsPage,
  FormDefinition,
  LicenseInstallResult,
  LicenseStatus,
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
  license: ['license'] as const,
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
  subjects: (q: string, type?: string, directory?: string) => ['admin', 'subjects', q, type, directory] as const,
  browse: (base: string, directory?: string) => ['admin', 'browse', base, directory] as const,
  ldapDirectories: (health: boolean) => ['admin', 'ldap-directories', health] as const,
  audit: (params: object) => ['admin', 'audit', params] as const,
  auditActions: ['admin', 'audit-actions'] as const,
  auditStatus: ['admin', 'audit-status'] as const,
  auditTokens: ['admin', 'audit-tokens'] as const,
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
  apiTokens: ['admin', 'api-tokens'] as const,
  apiTokenSettings: ['admin', 'api-token-settings'] as const,
  webhooks: ['admin', 'webhooks'] as const,
  webhookSettings: ['admin', 'webhook-settings'] as const,
  webhookDeliveries: (id: string) => ['admin', 'webhook-deliveries', id] as const,
  members: (id: string, dn: string) => ['members', id, dn] as const,
  groups: ['groups'] as const,
  jobs: ['jobs'] as const,
  job: (id: string) => ['jobs', id] as const,
  jobResult: (id: string) => ['jobs', id, 'result'] as const,
};

export const useServerInfo = () => useQuery({ queryKey: keys.info, queryFn: () => api.get<ServerInfo>('/api/info'), staleTime: 60_000 });

/** The license: mode, banners and which features may be used (re-checked now and then, as the server re-reads the file). */
export const useLicense = () =>
  useQuery({
    queryKey: keys.license,
    queryFn: () => api.get<LicenseStatus>('/api/license', { background: true }),
    staleTime: 60_000,
    refetchInterval: 5 * 60_000,
  });

/** License screen upload: installs the file, or fails with the server's reason. */
export function useLicenseUpload() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (text: string) => api.postText<LicenseInstallResult>('/api/admin/license', text),
    onSuccess: (result) => {
      qc.setQueryData(keys.license, result.license);
      // What the license unlocks may have changed: read everything again.
      qc.invalidateQueries({ predicate: (q) => q.queryKey[0] !== 'license' });
    },
  });
}

/** Directories the user can see; `enabled` false while the license doesn't include the directory editor. */
export const useDefinitions = (enabled = true) =>
  useQuery({ queryKey: keys.definitions, queryFn: () => api.get<DefinitionSummary[]>('/api/definitions'), enabled });

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

/**
 * Where the drop downs of a form get their options: the directory's own endpoint, which needs
 * access to the directory, or (`new-group`) group access's, for the owner of an organizational unit
 * filling in the form of a new group.
 */
export type OptionsSource = 'directory' | 'new-group';
export const OptionsSourceContext = createContext<OptionsSource>('directory');

const optionsUrl = (source: OptionsSource, definitionId: string, fieldId: string) =>
  source === 'new-group'
    ? `/api/groups/${encodeURIComponent(definitionId)}/group/options/${encodeURIComponent(fieldId)}`
    : `/api/directories/${encodeURIComponent(definitionId)}/options/${encodeURIComponent(fieldId)}`;

const optionsKey = (source: OptionsSource, definitionId: string, fieldId: string): QueryKey =>
  source === 'new-group' ? [...keys.groups, 'options', definitionId, fieldId] : keys.options(definitionId, fieldId);

/** A drop down's options (directory-search drop downs in list mode: up to their limit). */
export const useFieldOptions = (definitionId: string, fieldId: string, enabled: boolean) => {
  const source = useContext(OptionsSourceContext);
  return useQuery({
    queryKey: optionsKey(source, definitionId, fieldId),
    queryFn: () => api.get<OptionPage>(optionsUrl(source, definitionId, fieldId)),
    enabled,
    staleTime: 30_000,
  });
};

/** Options matching typed text, searched in the directory. Keeps the previous results while typing. */
export const useOptionSearch = (definitionId: string, fieldId: string, q: string, enabled: boolean) => {
  const source = useContext(OptionsSourceContext);
  return useQuery({
    queryKey: [...optionsKey(source, definitionId, fieldId), 'q', q],
    queryFn: () => api.get<OptionPage>(`${optionsUrl(source, definitionId, fieldId)}${qs({ q })}`),
    enabled,
    staleTime: 30_000,
    placeholderData: (prev) => prev,
  });
};

/** Labels (and descriptions) of stored values, looked up without loading every option. */
const optionLabelsQuery = (source: OptionsSource, definitionId: string, fieldId: string, values: string[]) => ({
  queryKey: [...optionsKey(source, definitionId, fieldId), 'values', [...values].sort()],
  queryFn: () => {
    const p = new URLSearchParams();
    values.forEach((v) => p.append('value', v));
    return api.get<OptionPage>(`${optionsUrl(source, definitionId, fieldId)}?${p.toString()}`);
  },
  staleTime: 60_000,
});

export const useOptionLabels = (definitionId: string, fieldId: string, values: string[], enabled: boolean) => {
  const source = useContext(OptionsSourceContext);
  return useQuery({ ...optionLabelsQuery(source, definitionId, fieldId, values), enabled: enabled && values.length > 0 });
};

/** Values per label request in useFieldLabels (keeps the query string short). */
const LABELS_PER_REQUEST = 50;

/**
 * Labels of several directory-search drop-down fields' values at once, e.g. a list page's columns:
 * one request per field (per 50 distinct values) for the whole page instead of one per row.
 * Returns the labels found per field id (undefined while loading).
 */
export function useFieldLabels(definitionId: string, wanted: { fieldId: string; values: string[] }[]): Record<string, OptionItem[] | undefined> {
  const chunks = wanted.flatMap(({ fieldId, values }) => {
    const distinct = [...new Set(values)].sort();
    const out: { fieldId: string; values: string[] }[] = [];
    for (let i = 0; i < distinct.length; i += LABELS_PER_REQUEST) out.push({ fieldId, values: distinct.slice(i, i + LABELS_PER_REQUEST) });
    return out;
  });
  const results = useQueries({
    queries: chunks.map((c) => ({ ...optionLabelsQuery('directory', definitionId, c.fieldId, c.values), enabled: !!definitionId })),
  });
  const labels: Record<string, OptionItem[] | undefined> = {};
  chunks.forEach((c, i) => {
    const found = results[i]?.data?.options;
    if (found) labels[c.fieldId] = [...(labels[c.fieldId] ?? []), ...found];
  });
  return labels;
}

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

/** Users and groups of one LDAP directory (none: the first). */
export const useSubjects = (q: string, type?: 'user' | 'group', directory?: string) =>
  useQuery({
    queryKey: keys.subjects(q, type, directory),
    queryFn: () => api.get<SubjectSearchResult[]>(`/api/admin/subjects${qs({ q, type, directory })}`),
    placeholderData: (prev) => prev,
  });

/**
 * The configured LDAP directories (administrators). `health`: also check that each answers (slower);
 * without it, for pickers.
 */
export const useLdapDirectories = (health = false) =>
  useQuery({
    queryKey: keys.ldapDirectories(health),
    queryFn: () => api.get<LdapDirectoryListing>(`/api/admin/directories${qs({ health: String(health) })}`),
    staleTime: health ? 0 : 60_000,
  });

export interface BrowseResult {
  base: string;
  children: { dn: string; name: string; objectClasses: string[] }[];
  truncated: boolean;
}

export const useBrowse = (base: string, enabled: boolean, directory?: string) =>
  useQuery({
    queryKey: keys.browse(base, directory),
    queryFn: () => api.get<BrowseResult>(`/api/admin/browse${qs({ base, directory })}`),
    enabled,
  });

export const previewSearch = (input: {
  baseDn: string;
  scope: 'base' | 'one' | 'sub';
  filter: string;
  attributes?: string[];
  /** The LDAP directory to search (none: the first). */
  directory?: string;
}) => api.post<{ entries: DirectoryEntry[]; truncated: boolean }>('/api/admin/preview', input);

/** Audit log filters (all optional). `from`/`to` are ISO instants or YYYY-MM-DD. */
export interface AuditFilters {
  q?: string;
  category?: string;
  action?: string;
  outcome?: string;
  severity?: string;
  actor?: string;
  target?: string;
  ip?: string;
  from?: string;
  to?: string;
}

export interface AuditQuery extends AuditFilters {
  page?: number;
  pageSize?: number;
}

export const useAudit = (params: AuditQuery) =>
  useQuery({
    queryKey: keys.audit(params),
    queryFn: () => api.get<AuditPage>(`/api/admin/audit${qs({ ...params })}`),
    placeholderData: (prev) => prev,
  });

export const useAuditActions = () =>
  useQuery({ queryKey: keys.auditActions, queryFn: () => api.get<AuditActions>('/api/admin/audit/actions'), staleTime: Infinity });

export const useAuditStatus = () =>
  useQuery({
    queryKey: keys.auditStatus,
    queryFn: () => api.get<AuditStatus>('/api/admin/audit/status', { background: true }),
    refetchInterval: 30_000,
  });

export const useAuditTokens = () => useQuery({ queryKey: keys.auditTokens, queryFn: () => api.get<AuditToken[]>('/api/admin/audit/tokens') });

export function useVerifyAudit() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<AuditVerification>('/api/admin/audit/verify'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin', 'audit'] }),
  });
}

export function useCreateAuditToken() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => api.post<{ token: AuditToken; secret: string }>('/api/admin/audit/tokens', { name }),
    // The answer holds the token's value: never kept in the mutation cache once the page let go of it.
    gcTime: 0,
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.auditTokens }),
  });
}

export function useRevokeAuditToken() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.del<AuditToken>(`/api/admin/audit/tokens/${encodeURIComponent(id)}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.auditTokens }),
  });
}

/** Download the events matching the filters (up to 100,000) as CSV or NDJSON. The server records the export. */
export async function downloadAuditExport(format: 'csv' | 'ndjson', filters: AuditFilters) {
  const res = await fetch(`/api/admin/audit/export${qs({ ...filters, format })}`, { credentials: 'same-origin' });
  if (!res.ok) {
    let message = `Export failed (${res.status})`;
    try {
      message = ((await res.json()) as { error?: string }).error ?? message;
    } catch {
      // not JSON
    }
    throw new Error(message);
  }
  saveBlob(await res.blob(), `audit-${new Date().toISOString().slice(0, 10)}.${format}`);
  return Number(res.headers.get('X-Audit-Count') ?? 0);
}

// ---------------------------------------------------------------------------
// Background jobs
// ---------------------------------------------------------------------------

/** The user's background computations; polled quickly while any is running. */
export const useJobs = (enabled = true) =>
  useQuery({
    queryKey: keys.jobs,
    queryFn: () => api.get<Job[]>('/api/jobs', { background: true }),
    enabled,
    refetchInterval: (q) => (q.state.data?.some(jobActive) ? 1500 : 15_000),
    refetchIntervalInBackground: true,
    staleTime: 1000,
  });

/** One job, polled every second until it finishes. */
export const useJob = (id: string | undefined) =>
  useQuery({
    queryKey: keys.job(id ?? ''),
    queryFn: () => api.get<Job>(`/api/jobs/${encodeURIComponent(id!)}`, { background: true }),
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
 *
 * <p>With {@code keep}, an answer stays as it is until {@code refresh} is called, also while no
 * page shows it: coming back to the page shows it again instead of asking the server to compute it
 * once more. For the pages with a Refresh button and a bounded number of keys (not the explorer's
 * ad-hoc requests). A pending answer is never kept: the page asks again and joins the job, or
 * starts over if the job is gone.
 */
export function useBackgroundQuery<T>(
  queryKey: QueryKey,
  fetcher: () => Promise<T | PendingResponse>,
  opts: { enabled?: boolean; staleTime?: number; keep?: boolean } = {},
) {
  const qc = useQueryClient();
  const query = useQuery<T | PendingResponse>({
    queryKey,
    queryFn: fetcher,
    enabled: opts.enabled ?? true,
    staleTime: opts.keep ? (q) => (q.state.data === undefined || isPending(q.state.data) ? 0 : Infinity) : (opts.staleTime ?? 30_000),
    ...(opts.keep ? { gcTime: Infinity } : {}),
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
    // Fetched when the job finishes, not when the user acts: background requests.
    api
      .get<{ result: T }>(`/api/jobs/${encodeURIComponent(current.id)}/result`, { background: true })
      .then((r) => {
        if (!alive) return;
        qc.setQueryData(keyRef.current, r.result);
        api.post('/api/jobs/seen', { ids: [current.id] }, { background: true }).then(() => qc.invalidateQueries({ queryKey: keys.jobs }));
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
    /** When the answer shown was received (0 while there is none). */
    updatedAt: data === undefined ? 0 : query.dataUpdatedAt,
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

/**
 * Metric results stay on the page until the user refreshes them: visiting a page again does not
 * recompute it. "refresh" recomputes on the server, bypassing its cache.
 */
const withRefresh = (url: string, refresh: boolean) => (refresh ? `${url}${url.includes('?') ? '&' : '?'}refresh=true` : url);

export const useMetricSources = () =>
  useQuery({ queryKey: keys.metricSources, queryFn: () => api.get<MetricSource[]>('/api/metrics/sources'), staleTime: 60_000 });

const summaryUrl = (refresh = false) => withRefresh('/api/metrics/summary', refresh);
export const useMetricSummary = () =>
  useBackgroundQuery<DirectorySummaryRow[]>(keys.metricSummary, () => api.get(summaryUrl()), { keep: true });
export const fetchSummary = (refresh: boolean) => api.get<DirectorySummaryRow[] | PendingResponse>(summaryUrl(refresh));

const overviewUrl = (id: string, refresh = false) => withRefresh(`/api/metrics/overview/${encodeURIComponent(id)}`, refresh);
export const useOverview = (id: string | undefined) =>
  useBackgroundQuery<OverviewResponse>(keys.overview(id ?? ''), () => api.get(overviewUrl(id!)), { enabled: !!id, keep: true });
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
  useBackgroundQuery<DashboardResults>(keys.dashboardResults(id ?? ''), () => api.get(dashboardUrl(id!)), { enabled: !!id && enabled, keep: true });
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
  // Only what the change touches: the other pages keep the results they show (see withRefresh).
  const changed = (id?: string) => {
    void qc.invalidateQueries({ queryKey: keys.dashboards });
    if (id) {
      void qc.invalidateQueries({ queryKey: keys.dashboard(id) });
      void qc.invalidateQueries({ queryKey: keys.dashboardResults(id) });
    }
  };
  return {
    create: useMutation({ mutationFn: (d: DashboardInput) => api.post<Dashboard>('/api/admin/dashboards', d), onSuccess: () => changed() }),
    update: useMutation({
      mutationFn: ({ id, ...d }: DashboardInput & { id: string }) => api.put<Dashboard>(`/api/admin/dashboards/${encodeURIComponent(id)}`, d),
      onSuccess: (_, { id }) => changed(id),
    }),
    remove: useMutation({ mutationFn: (id: string) => api.del(`/api/admin/dashboards/${encodeURIComponent(id)}`), onSuccess: (_, id) => changed(id) }),
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

// ---------------------------------------------------------------------------
// Integrations: API tokens and webhooks
// ---------------------------------------------------------------------------

export const useApiTokens = () => useQuery({ queryKey: keys.apiTokens, queryFn: () => api.get<ApiToken[]>('/api/admin/api-tokens') });

export const useApiTokenSettings = () =>
  useQuery({ queryKey: keys.apiTokenSettings, queryFn: () => api.get<ApiTokenSettings>('/api/admin/api-tokens/settings'), staleTime: 60_000 });

export interface ApiTokenRequest {
  name: string;
  scopes: string[];
  definitionIds: string[];
  expiresInDays: number;
  rateLimitPerMinute?: number;
}

export function useCreateApiToken() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: ApiTokenRequest) => api.post<{ token: ApiToken; secret: string }>('/api/admin/api-tokens', body),
    // The answer holds the token's value: never kept in the mutation cache once the page let go of it.
    gcTime: 0,
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.apiTokens }),
  });
}

export function useRevokeApiToken() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.del<ApiToken>(`/api/admin/api-tokens/${encodeURIComponent(id)}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.apiTokens }),
  });
}

export const useWebhooks = () => useQuery({ queryKey: keys.webhooks, queryFn: () => api.get<Webhook[]>('/api/admin/webhooks') });

export const useWebhookSettings = () =>
  useQuery({ queryKey: keys.webhookSettings, queryFn: () => api.get<WebhookSettings>('/api/admin/webhooks/settings'), staleTime: 60_000 });

export const useWebhookDeliveries = (id: string | undefined) =>
  useQuery({
    queryKey: keys.webhookDeliveries(id ?? ''),
    queryFn: () => api.get<WebhookDelivery[]>(`/api/admin/webhooks/${encodeURIComponent(id!)}/deliveries`, { background: true }),
    enabled: !!id,
    refetchInterval: (q) => (q.state.data?.some((d) => d.status === 'pending') ? 3000 : false),
  });

export function useWebhookMutations() {
  const qc = useQueryClient();
  const refresh = () => qc.invalidateQueries({ queryKey: keys.webhooks });
  const url = (id: string) => `/api/admin/webhooks/${encodeURIComponent(id)}`;
  return {
    // Answers with a signing secret are dropped from the mutation cache at once (gcTime 0; the
    // page also resets the mutation after taking the secret).
    create: useMutation({
      mutationFn: (body: WebhookInput) => api.post<{ webhook: Webhook; secret: string }>('/api/admin/webhooks', body),
      onSuccess: refresh,
      gcTime: 0,
    }),
    update: useMutation({ mutationFn: ({ id, body }: { id: string; body: WebhookInput }) => api.put<Webhook>(url(id), body), onSuccess: refresh }),
    rotate: useMutation({ mutationFn: (id: string) => api.post<{ webhook: Webhook; secret: string }>(`${url(id)}/rotate-secret`), onSuccess: refresh, gcTime: 0 }),
    remove: useMutation({ mutationFn: (id: string) => api.del(url(id)), onSuccess: refresh }),
    test: useMutation({
      mutationFn: (id: string) => api.post<{ deliveryId: string }>(`${url(id)}/test`),
      onSuccess: (_r, id) => qc.invalidateQueries({ queryKey: keys.webhookDeliveries(id) }),
    }),
  };
}

// ---------------------------------------------------------------------------
// Export, import and group members
// ---------------------------------------------------------------------------

/**
 * Downloads a file the server makes on request: a POST with the CSRF header (the export is
 * audited, so another site must not be able to start it), saved under the name from
 * Content-Disposition.
 */
async function download(url: string, fallbackName: string) {
  const res = await fetch(url, { method: 'POST', headers: { 'X-DSP-Request': '1' }, credentials: 'same-origin' });
  if (!res.ok) {
    let message = `Export failed (${res.status})`;
    try {
      message = ((await res.json()) as { error?: string }).error ?? message;
    } catch {
      // not JSON
    }
    throw new Error(message);
  }
  const disposition = res.headers.get('Content-Disposition') ?? '';
  const name = /filename="?([^";]+)"?/.exec(disposition)?.[1] ?? fallbackName;
  saveBlob(await res.blob(), name);
  return { count: Number(res.headers.get('X-DSP-Export-Count') ?? 0), truncated: res.headers.get('X-DSP-Export-Truncated') === 'true' };
}

/** Download a directory's entries matching the search (all of them without one). The server records the export. */
export const downloadEntries = (definitionId: string, format: 'csv' | 'ldif', q?: string) =>
  download(`/api/directories/${encodeURIComponent(definitionId)}/export${qs({ format, q })}`, `export.${format}`);

export const downloadMembers = (definitionId: string, dn: string, format: 'csv' | 'ldif', attribute?: string) =>
  download(`/api/directories/${encodeURIComponent(definitionId)}/members/export?${dnParam(dn)}${attribute ? `&attribute=${encodeURIComponent(attribute)}` : ''}&format=${format}`,
    `members.${format}`);

/** Download a group's members (name, login, e-mail, DN, end date) as CSV, for the group's owners. The server records the export. */
export const downloadGroupMembers = (definitionId: string, dn: string) =>
  download(`/api/groups/${encodeURIComponent(definitionId)}/members/export?${dnParam(dn)}`, 'members.csv');

/** Failed polls in a row (a replica restarting, a proxy's 502) jobResult rides out before giving up. */
const JOB_POLL_FAILURES = 10;

/**
 * Waits for a background job's result (polling every `pause` ms, once a second). A failed poll is
 * tried again; after JOB_POLL_FAILURES in a row it gives up saying the import may still be running,
 * never that it failed. Marking the job seen is bookkeeping and never fails the import.
 */
export async function jobResult<T>(response: T | PendingResponse, pause = 1000): Promise<T> {
  if (!isPending(response)) return response;
  const id = response.job.id;
  const stop = watchJob(id);
  const wait = () => new Promise((r) => setTimeout(r, pause));
  try {
    let failures = 0;
    for (;;) {
      let job: Job;
      let done: { result: T } | undefined;
      try {
        job = await api.get<Job>(`/api/jobs/${encodeURIComponent(id)}`, { background: true });
        if (job.status === 'succeeded') done = await api.get<{ result: T }>(`/api/jobs/${encodeURIComponent(id)}/result`, { background: true });
        failures = 0;
      } catch {
        if (++failures >= JOB_POLL_FAILURES) {
          throw new Error('Lost contact with the server while the import ran: it may still be running, or have finished. Check Jobs for its result before importing again.');
        }
        await wait();
        continue;
      }
      if (done) {
        api.post('/api/jobs/seen', { ids: [id] }, { background: true }).catch(() => undefined);
        return done.result;
      }
      if (!jobActive(job)) throw new Error(job.status === 'cancelled' ? 'The import was cancelled.' : `The import failed: ${job.error ?? 'unknown error'}`);
      await wait();
    }
  } finally {
    stop();
  }
}

export interface ImportRequest {
  definitionId: string;
  format: 'csv' | 'ldif';
  mode: 'upsert' | 'create' | 'update';
  dryRun: boolean;
  text: string;
}

export function useImportEntries() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ definitionId, format, mode, dryRun, text }: ImportRequest) =>
      jobResult(
        await api.postText<ImportResult | PendingResponse>(
          `/api/directories/${encodeURIComponent(definitionId)}/import${qs({ format, mode, dryRun: dryRun ? 'true' : undefined })}`,
          text,
          format === 'csv' ? 'text/csv; charset=utf-8' : undefined,
        ),
      ),
    onSuccess: (r, v) => {
      if (!r.dryRun) {
        qc.invalidateQueries({ queryKey: ['entries', v.definitionId] });
        qc.invalidateQueries({ queryKey: ['entry', v.definitionId] });
      }
    },
  });
}

export const useGroupMembers = (definitionId: string | undefined, dn: string, enabled = true) =>
  useQuery({
    queryKey: keys.members(definitionId ?? '', dn),
    queryFn: () => api.get<GroupMembers>(`/api/directories/${encodeURIComponent(definitionId!)}/members?${dnParam(dn)}`),
    enabled: !!definitionId && enabled,
    retry: false,
  });

export function useChangeMembers(definitionId: string, dn: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { attribute?: string; add: string[]; remove: string[] }) =>
      api.post<MembershipChangeResult>(`/api/directories/${encodeURIComponent(definitionId)}/members?${dnParam(dn)}`, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.members(definitionId, dn) });
      qc.invalidateQueries({ queryKey: ['entry', definitionId] });
    },
  });
}

// ---------------------------------------------------------------------------
// Self-service group access
// ---------------------------------------------------------------------------

const groupUrl = (definitionId: string, path: string, dn: string) => `/api/groups/${encodeURIComponent(definitionId)}/${path}?${dnParam(dn)}`;

/** The directories whose groups the user can look up (none: the Groups page isn't offered). */
export const useGroupDirectories = (enabled = true) =>
  useQuery({
    queryKey: [...keys.groups, 'directories'],
    queryFn: () => api.get<GroupDirectory[]>('/api/groups/directories', { background: true }),
    enabled,
    staleTime: 60_000,
  });

export const useGroupSearch = (q: string, definition: string) =>
  useQuery({
    queryKey: [...keys.groups, 'search', q, definition],
    queryFn: () => api.get<GroupList>(`/api/groups${qs({ q, definition })}`),
    placeholderData: (prev) => prev,
  });

export const useMyGroups = () => useQuery({ queryKey: [...keys.groups, 'mine'], queryFn: () => api.get<GroupList>('/api/groups/mine') });

export const useGroup = (definitionId: string | undefined, dn: string | null) =>
  useQuery({
    queryKey: [...keys.groups, 'group', definitionId, dn],
    queryFn: () => api.get<GroupDetail>(groupUrl(definitionId!, 'group', dn!)),
    enabled: !!definitionId && !!dn,
    retry: false,
  });

export const useGroupAccessMembers = (definitionId: string, dn: string, q: string, page: number, enabled: boolean) =>
  useQuery({
    queryKey: [...keys.groups, 'members', definitionId, dn, q, page],
    queryFn: () => api.get<GroupMemberPage>(`${groupUrl(definitionId, 'members', dn)}&${qs({ q, page }).slice(1)}`),
    enabled,
    placeholderData: (prev) => prev,
  });

export const useGroupPeople = (definitionId: string, dn: string, q: string, enabled: boolean) =>
  useQuery({
    queryKey: [...keys.groups, 'people', definitionId, dn, q],
    queryFn: () => api.get<GroupPerson[]>(`${groupUrl(definitionId, 'people', dn)}&${qs({ q }).slice(1)}`),
    enabled,
  });

export const useGroupRequestsFor = (definitionId: string, dn: string, enabled: boolean) =>
  useQuery({
    queryKey: [...keys.groups, 'group-requests', definitionId, dn],
    queryFn: () => api.get<AccessRequestView[]>(groupUrl(definitionId, 'group/requests', dn)),
    enabled,
  });

/** The organizational units the user owns, where they may create groups (needs the directory editor's license). */
export const useGroupContainers = (enabled: boolean) =>
  useQuery({
    queryKey: [...keys.groups, 'containers'],
    queryFn: () => api.get<GroupContainer[]>('/api/groups/containers'),
    enabled,
    staleTime: 30_000,
  });

/** The form of a new group in a directory, for those who own one of its organizational units. */
export const useNewGroupForm = (definitionId: string | undefined) =>
  useQuery({
    queryKey: [...keys.groups, 'new-group-form', definitionId],
    queryFn: () => api.get<NewGroupForm>(`/api/groups/${encodeURIComponent(definitionId!)}/group/form`),
    enabled: !!definitionId,
  });

/** A page of the group's past requests, for those who manage it. */
export const useGroupHistory = (definitionId: string, dn: string, page: number, enabled: boolean) =>
  useQuery({
    queryKey: [...keys.groups, 'group-history', definitionId, dn, page],
    queryFn: () => api.get<GroupRequestHistory>(`${groupUrl(definitionId, 'group/history', dn)}&${qs({ page }).slice(1)}`),
    enabled,
    placeholderData: (prev) => prev,
  });

/** `mine`: the user's requests; `review`: open ones they may decide; `decided`: the ones they decided. */
export const useAccessRequests = (scope: 'mine' | 'review' | 'decided') =>
  useQuery({ queryKey: [...keys.groups, 'requests', scope], queryFn: () => api.get<AccessRequestView[]>(`/api/groups/requests?scope=${scope}`) });

/** Counts for the navigation (re-checked every minute). */
export const useGroupRequestSummary = (enabled: boolean) =>
  useQuery({
    queryKey: [...keys.groups, 'summary'],
    queryFn: () => api.get<GroupRequestSummary>('/api/groups/requests/summary', { background: true }),
    enabled,
    refetchInterval: 60_000,
  });

export function useGroupMutations() {
  const qc = useQueryClient();
  const refresh = () => {
    qc.invalidateQueries({ queryKey: keys.groups });
    // Entry pages of the group show its members too.
    qc.invalidateQueries({ queryKey: ['entry'] });
  };
  // A group that is new or gone: the directory's own lists show it too.
  const listsChanged = () => {
    refresh();
    qc.invalidateQueries({ queryKey: ['entries'] });
  };
  return {
    /** Creates a group directly in an organizational unit the user owns, from the directory's form. */
    create: useMutation({
      mutationFn: (v: { definitionId: string; container: string; values: Values }) =>
        api.post<CreatedGroup>(`/api/groups/${encodeURIComponent(v.definitionId)}/group`, { container: v.container, values: v.values }),
      onSuccess: listsChanged,
    }),
    /** Deletes a group, for an owner of the organizational unit it is directly in. */
    remove: useMutation({
      mutationFn: (v: { definitionId: string; dn: string }) => api.del(groupUrl(v.definitionId, 'group', v.dn)),
      onSuccess: listsChanged,
    }),
    request: useMutation({
      mutationFn: (v: { definitionId: string; dn: string; reason?: string; until?: string }) =>
        api.post<AccessRequest>(groupUrl(v.definitionId, 'requests', v.dn), { reason: v.reason || undefined, until: v.until || undefined }),
      onSuccess: refresh,
    }),
    cancel: useMutation({ mutationFn: (id: string) => api.post<AccessRequest>(`/api/groups/requests/${encodeURIComponent(id)}/cancel`), onSuccess: refresh }),
    decide: useMutation({
      mutationFn: (v: { id: string; approve: boolean; note?: string; until?: string }) =>
        api.post<AccessRequest>(`/api/groups/requests/${encodeURIComponent(v.id)}/${v.approve ? 'approve' : 'reject'}`, {
          note: v.note || undefined,
          until: (v.approve && v.until) || undefined,
        }),
      onSuccess: refresh,
    }),
    join: useMutation({
      mutationFn: (v: { definitionId: string; dn: string }) => api.post<MembershipChangeResult>(groupUrl(v.definitionId, 'join', v.dn)),
      onSuccess: refresh,
    }),
    /** Approves or rejects several requests with one note; the answer says what was decided and what was not. */
    decideMany: useMutation({
      mutationFn: (v: { ids: string[]; approve: boolean; note?: string }) =>
        api.post<AccessDecisions>('/api/groups/requests/decide', { ids: v.ids, approve: v.approve, note: v.note || undefined }),
      onSuccess: refresh,
    }),
    leave: useMutation({
      mutationFn: (v: { definitionId: string; dn: string }) => api.post<MembershipChangeResult>(groupUrl(v.definitionId, 'leave', v.dn)),
      onSuccess: refresh,
    }),
    owners: useMutation({
      mutationFn: (v: { definitionId: string; dn: string; add: string[]; remove: string[] }) =>
        api.post<MembershipChangeResult>(groupUrl(v.definitionId, 'owners', v.dn), { add: v.add, remove: v.remove }),
      onSuccess: refresh,
    }),
    members: useMutation({
      mutationFn: (v: { definitionId: string; dn: string; add: string[]; remove: string[]; until?: string }) =>
        api.post<MembershipChangeResult>(groupUrl(v.definitionId, 'members', v.dn), { add: v.add, remove: v.remove, until: v.until || undefined }),
      onSuccess: refresh,
    }),
    /** Looks up a pasted list of people (logins, e-mail addresses or DNs) before adding them. */
    resolvePeople: useMutation({
      mutationFn: (v: { definitionId: string; dn: string; lines: string[] }) =>
        api.post<GroupPeopleResolved>(groupUrl(v.definitionId, 'people/resolve', v.dn), { lines: v.lines }),
    }),
    /** Sets, changes or (without `until`) clears a member's end date. */
    memberUntil: useMutation({
      mutationFn: (v: { definitionId: string; dn: string; member: string; until?: string }) =>
        api.post<GroupMember>(groupUrl(v.definitionId, 'members/until', v.dn), { member: v.member, until: v.until || undefined }),
      onSuccess: refresh,
    }),
  };
}
