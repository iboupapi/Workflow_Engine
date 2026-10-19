import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiGet, apiPost } from './client'
import type {
  ActionOption,
  DefinitionView,
  DevToken,
  DraftView,
  FormDataRequest,
  HistoryEntry,
  InstanceView,
  RequestFormView,
  RequestTypeView,
  SpringPage,
  StartWorkflowRequest,
  SubmissionView,
  TransitionRequest,
  WorkflowInstance,
} from './types'

/** Jetons de developpement (profil dev uniquement). */
export function useDevTokens(direction?: string, bureau?: string) {
  return useQuery({
    queryKey: ['dev-tokens', direction ?? '', bureau ?? ''],
    queryFn: () => apiGet<DevToken[]>(`/api/dev/tokens${search({ direction, bureau })}`),
  })
}

export function useInstances(view: InstanceView, enabled = true) {
  return useQuery({
    queryKey: ['instances', view],
    enabled,
    queryFn: () =>
      apiGet<SpringPage<WorkflowInstance>>(`/api/instances?view=${view}&size=50`),
  })
}

export function useInstance(id: string | null) {
  return useQuery({
    queryKey: ['instance', id],
    enabled: id !== null,
    queryFn: () => apiGet<WorkflowInstance>(`/api/instances/${id}`),
  })
}

export function useActions(id: string | null) {
  return useQuery({
    queryKey: ['actions', id],
    enabled: id !== null,
    queryFn: () => apiGet<ActionOption[]>(`/api/instances/${id}/actions`),
  })
}

export function useHistory(id: string | null) {
  return useQuery({
    queryKey: ['history', id],
    enabled: id !== null,
    queryFn: () => apiGet<HistoryEntry[]>(`/api/instances/${id}/history`),
  })
}

export function useDefinition(key: string, enabled = true) {
  return useQuery({
    queryKey: ['definition', key],
    enabled: enabled && key.length > 0,
    queryFn: () => apiGet<DefinitionView>(`/api/workflows/${key}`),
  })
}

export function useTransition(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: TransitionRequest) =>
      apiPost<WorkflowInstance>(`/api/instances/${id}/transition`, input),
    onSuccess: () => {
      invalidate(queryClient)
    },
  })
}

export function useStartWorkflow(definitionKey: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: StartWorkflowRequest) =>
      apiPost<WorkflowInstance>(`/api/workflows/${definitionKey}/start`, input),
    onSuccess: () => {
      invalidate(queryClient)
    },
  })
}

/* --------------------------------------------------------------------------------------
 * Phase 3 — demandes d'usagers (REQ-01, REQ-03).
 * ------------------------------------------------------------------------------------ */

/** Catalogue des types de demande publies. */
export function useRequestTypes(enabled = true) {
  return useQuery({
    queryKey: ['request-types'],
    enabled,
    queryFn: () => apiGet<RequestTypeView[]>('/api/request-types'),
  })
}

/** Formulaire de la version publiee en vigueur d'un type de demande. */
export function useRequestForm(typeKey: string | null) {
  return useQuery({
    queryKey: ['request-form', typeKey],
    enabled: typeKey !== null && typeKey.length > 0,
    queryFn: () => apiGet<RequestFormView>(`/api/request-types/${typeKey}/form`),
  })
}

/** Brouillons du demandeur authentifie. */
export function useDrafts(enabled = true) {
  return useQuery({
    queryKey: ['request-drafts'],
    enabled,
    queryFn: () => apiGet<SubmissionView[]>('/api/requests/drafts'),
  })
}

/** Brouillon complet (saisie enregistree) pour reprendre le formulaire. */
export function useDraft(draftId: string | null) {
  return useQuery({
    queryKey: ['request-draft', draftId],
    enabled: draftId !== null,
    queryFn: () => apiGet<DraftView>(`/api/requests/drafts/${draftId}`),
  })
}

export function useSubmitRequest() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: FormDataRequest) =>
      apiPost<SubmissionView>('/api/requests/submit', input),
    onSuccess: () => {
      invalidate(queryClient)
      void queryClient.invalidateQueries({ queryKey: ['request-drafts'] })
    },
  })
}

export function useSaveDraft() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: FormDataRequest) =>
      apiPost<SubmissionView>('/api/requests/drafts', input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['request-drafts'] })
    },
  })
}

export function useSubmitDraft() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (draftId: string) =>
      apiPost<SubmissionView>(`/api/requests/drafts/${draftId}/submit`, {}),
    onSuccess: () => {
      invalidate(queryClient)
      void queryClient.invalidateQueries({ queryKey: ['request-drafts'] })
    },
  })
}

function invalidate(queryClient: ReturnType<typeof useQueryClient>): void {
  void queryClient.invalidateQueries({ queryKey: ['instances'] })
  void queryClient.invalidateQueries({ queryKey: ['instance'] })
  void queryClient.invalidateQueries({ queryKey: ['actions'] })
  void queryClient.invalidateQueries({ queryKey: ['history'] })
}

function search(params: Record<string, string | undefined>): string {
  const entries = Object.entries(params).filter(
    (entry): entry is [string, string] => entry[1] !== undefined && entry[1] !== '',
  )
  if (entries.length === 0) {
    return ''
  }
  const query = entries.map(([key, value]) => `${key}=${encodeURIComponent(value)}`).join('&')
  return `?${query}`
}
