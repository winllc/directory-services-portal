import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  AttributeTypeDef,
  CustomSchema,
  DefinitionSummary,
  DirectoryDefinition,
  DirectoryEntry,
  EntryListResponse,
  FormDefinition,
  ObjectClassDef,
  OptionItem,
  PermissionGrant,
  SchemaSnapshot,
  SelfEntryResult,
  ServerInfo,
  SubjectSearchResult,
} from '@dsp/shared';
import { api, dnParam, qs } from './client';

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

export const useFieldOptions = (definitionId: string, fieldId: string, enabled: boolean) =>
  useQuery({
    queryKey: keys.options(definitionId, fieldId),
    queryFn: () =>
      api.get<OptionItem[]>(`/api/directories/${encodeURIComponent(definitionId)}/options/${encodeURIComponent(fieldId)}`),
    enabled,
    staleTime: 30_000,
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
