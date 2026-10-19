import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiDownload, apiGet, apiPost, apiUpload } from './client'
import type {
  ActionOption,
  AdminRequestTypeDetailView,
  AdminRequestTypeView,
  AttachmentView,
  CreateFormSchemaRequest,
  CreateRequestTypeRequest,
  DefinitionView,
  DeployedWorkflowView,
  DevToken,
  DraftView,
  FormDataRequest,
  FormSchemaView,
  HistoryEntry,
  InstanceView,
  PublishRequestTypeVersionRequest,
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

/* --------------------------------------------------------------------------------------
 * Pieces jointes (FRM-05) : le contenu vit hors base, seules les metadonnees circulent.
 * ------------------------------------------------------------------------------------ */

/** Pieces jointes deposees sur une saisie (brouillon ou demande soumise). */
export function useAttachments(submissionId: string | null) {
  return useQuery({
    queryKey: ['request-attachments', submissionId],
    enabled: submissionId !== null && submissionId.length > 0,
    queryFn: () =>
      apiGet<AttachmentView[]>(`/api/requests/submissions/${submissionId}/attachments`),
  })
}

/** Pieces jointes d'un dossier (fiche dossier) : memes regles de visibilite que lui. */
export function useInstanceAttachments(instanceId: string | null) {
  return useQuery({
    queryKey: ['instance-attachments', instanceId],
    enabled: instanceId !== null && instanceId.length > 0,
    queryFn: () => apiGet<AttachmentView[]>(`/api/instances/${instanceId}/attachments`),
  })
}

/** Depot d'une piece jointe sur un brouillon : le serveur controle type, taille et virus. */
export function useUploadAttachment() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({
      submissionId,
      fieldPath,
      file,
    }: {
      submissionId: string
      fieldPath: string
      file: File
    }) => {
      const form = new FormData()
      form.append('fieldPath', fieldPath)
      form.append('file', file)
      return apiUpload<AttachmentView>(
        `/api/requests/submissions/${submissionId}/attachments`,
        form,
      )
    },
    onSuccess: (_attachment, variables) => {
      void queryClient.invalidateQueries({
        queryKey: ['request-attachments', variables.submissionId],
      })
    },
  })
}

/** Telechargement d'une piece jointe autorisee par le controle sanitaire. */
export function useDownloadAttachment() {
  return useMutation({
    mutationFn: (attachment: AttachmentView) =>
      apiDownload(`/api/requests/attachments/${attachment.id}`, attachment.filename),
  })
}

/* --------------------------------------------------------------------------------------
 * Espace d'administration des E-Services (REQ-01, REQ-02, FRM-01).
 *
 * Ces endpoints exigent un administrateur NOMME (sujet du jeton declare cote serveur) :
 * un 403 n'est pas une erreur de saisie, l'ecran doit le dire tel quel.
 * ------------------------------------------------------------------------------------ */

/** Referentiel complet, tous statuts : tableau de bord de l'administration. */
export function useAdminRequestTypes(enabled = true) {
  return useQuery({
    queryKey: ['admin-request-types'],
    enabled,
    queryFn: () => apiGet<AdminRequestTypeView[]>('/api/admin/request-types'),
  })
}

/** Type de demande rouvert dans l'editeur (version en vigueur et formulaire). */
export function useAdminRequestType(requestTypeId: string | null) {
  return useQuery({
    queryKey: ['admin-request-type', requestTypeId],
    enabled: requestTypeId !== null,
    queryFn: () => apiGet<AdminRequestTypeDetailView>(`/api/admin/request-types/${requestTypeId}`),
  })
}

/** Circuits deployes : ce qui peut etre rattache a un type de demande. */
export function useDeployedWorkflows(enabled = true) {
  return useQuery({
    queryKey: ['admin-workflows'],
    enabled,
    queryFn: () => apiGet<DeployedWorkflowView[]>('/api/admin/workflows'),
  })
}

/** Creation d'un type de demande (brouillon, non encore propose a l'usager). */
export function useCreateRequestType() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: CreateRequestTypeRequest) =>
      apiPost<AdminRequestTypeView>('/api/admin/request-types', input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin-request-types'] })
    },
  })
}

/** Enregistrement d'un schema de formulaire : renvoie l'identifiant a publier. */
export function useCreateFormSchema() {
  return useMutation({
    mutationFn: (input: CreateFormSchemaRequest) =>
      apiPost<FormSchemaView>('/api/admin/form-schemas', input),
  })
}

/**
 * Publication : associe type, formulaire et circuit. Le type apparait alors dans le
 * catalogue usager — les deux caches sont donc rafraichis.
 */
export function usePublishRequestType() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: PublishRequestTypeVersionRequest) =>
      apiPost<AdminRequestTypeView>('/api/admin/request-type-versions/publish', input),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin-request-types'] })
      void queryClient.invalidateQueries({ queryKey: ['admin-request-type'] })
      void queryClient.invalidateQueries({ queryKey: ['request-types'] })
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
