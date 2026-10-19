import { ArrowLeft, Save, Send } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import {
  useAttachments,
  useDownloadAttachment,
  useRequestForm,
  useSaveDraft,
  useSubmitDraft,
  useSubmitRequest,
  useUploadAttachment,
} from '../api/hooks'
import type { AttachmentView, SubmissionView } from '../api/types'
import { ATTACHMENT_LABELS } from '../lib/attachments'
import { formFields, initialValues, toFormData, type FormField } from '../lib/formSchema'
import { AttachmentList } from './AttachmentList'
import { SchemaForm } from './SchemaForm'
import { ErrorNote, Panel } from './ui'

/**
 * Formulaire dynamique d'un type de demande : rendu a partir du JSON Schema publie,
 * enregistrement en brouillon (REQ-03) puis soumission (REQ-01), qui cree le dossier.
 * Les erreurs de validation renvoyees par le serveur (FRM-03) sont affichees telles quelles.
 *
 * Les champs pieces jointes (FRM-05) ne se saisissent pas : le depot exige un brouillon,
 * qui est donc cree a la volee si l'usager depose un fichier avant tout enregistrement.
 */
export function DynamicRequestForm({
  typeKey,
  draftId,
  savedValues,
  authenticated,
  onBack,
  onSubmitted,
}: {
  typeKey: string
  draftId: string | null
  savedValues: Record<string, unknown> | null
  authenticated: boolean
  onBack: () => void
  onSubmitted: (submission: SubmissionView) => void
}) {
  const form = useRequestForm(typeKey)
  const submit = useSubmitRequest()
  const saveDraft = useSaveDraft()
  const submitDraft = useSubmitDraft()
  const upload = useUploadAttachment()
  const download = useDownloadAttachment()
  const [edited, setEdited] = useState<Record<string, unknown> | null>(null)
  const [currentDraftId, setCurrentDraftId] = useState<string | null>(draftId)
  const [notice, setNotice] = useState<string | null>(null)
  const [formError, setFormError] = useState<unknown>(null)
  const [fileErrors, setFileErrors] = useState<Record<string, unknown>>({})
  const [uploadingField, setUploadingField] = useState<string | null>(null)

  const fields = useMemo(
    () => formFields(form.data?.schema, form.data?.uiSchema ?? null),
    [form.data],
  )

  /*
   * Aucun effet de synchronisation : tant que l'usager n'a rien modifie, la saisie est
   * derivee du schema (valeurs par defaut) et du brouillon repris ; la premiere frappe
   * fige l'etat edite.
   */
  const values = edited ?? initialValues(form.data?.schema, savedValues)

  /*
   * Verite des pieces jointes : c'est le serveur qui detient le verdict et le rattachement
   * au champ, la saisie ne conserve que l'identifiant renvoye par le depot.
   */
  const attachments = useAttachments(currentDraftId)
  const byField = useMemo(() => {
    const map: Record<string, AttachmentView> = {}
    for (const attachment of attachments.data ?? []) {
      map[attachment.fieldPath] = attachment
    }
    return map
  }, [attachments.data])
  const labels = useMemo(
    () => Object.fromEntries(fields.map((field) => [field.name, field.label])),
    [fields],
  )

  function set(name: string, value: unknown): void {
    setEdited({ ...values, [name]: value })
  }

  function formData(): Record<string, unknown> {
    return toFormData(fields, values)
  }

  function missingFiles(): FormField[] {
    return fields.filter(
      (field) => field.widget === 'file' && field.required && !byField[field.name],
    )
  }

  function onSaveDraft(): void {
    setNotice(null)
    saveDraft.mutate(
      { typeKey, formData: formData() },
      {
        onSuccess: (draft) => {
          setCurrentDraftId(draft.id)
          setNotice('Brouillon enregistré. Vous pouvez reprendre cette demande plus tard.')
        },
      },
    )
  }

  /**
   * Depot d'une piece jointe : un identifiant de brouillon est indispensable puisque le
   * rattachement se fait sur la saisie (FRM-05), on en cree donc un au besoin a partir de
   * la saisie courante. Le fichier n'entre pas dans les donnees du formulaire : c'est la
   * piece jointe rattachee au champ qui fait foi, et le serveur seul en rend le verdict.
   */
  async function onFile(field: FormField, file: File): Promise<void> {
    setNotice(null)
    setFormError(null)
    setFileErrors((errors) => ({ ...errors, [field.name]: null }))
    setUploadingField(field.name)
    try {
      const target = currentDraftId ?? (await saveDraft.mutateAsync({ typeKey, formData: formData() })).id
      if (!currentDraftId) {
        setCurrentDraftId(target)
      }
      const uploaded = await upload.mutateAsync({ submissionId: target, fieldPath: field.name, file })
      setNotice(
        `« ${uploaded.filename} » déposée (${ATTACHMENT_LABELS[uploaded.scanStatus]}). ${
          uploaded.scanStatus === 'CLEAN'
            ? 'Le contenu est disponible au téléchargement.'
            : 'Le contenu n’est pas téléchargeable.'
        }`,
      )
    } catch (cause) {
      setFileErrors((errors) => ({ ...errors, [field.name]: cause }))
    } finally {
      setUploadingField(null)
    }
  }

  function onSubmit(event: FormEvent): void {
    event.preventDefault()
    setNotice(null)
    setFormError(null)
    const missing = missingFiles()
    if (missing.length > 0) {
      setFormError(
        new Error(
          `Pièce jointe manquante : ${missing.map((field) => field.label).join(', ')}. Déposez le fichier avant de soumettre la demande.`,
        ),
      )
      return
    }
    if (currentDraftId) {
      /*
       * C'est la saisie ENREGISTRÉE qui est soumise : on la met donc à jour juste avant, pour
       * que les valeurs saisies depuis le dernier enregistrement soient prises en compte.
       * Un brouillon par demandeur et par type de demande : la mise à jour conserve la même
       * saisie, et donc les pièces jointes qui y sont rattachées.
       */
      saveDraft.mutate(
        { typeKey, formData: formData() },
        { onSuccess: (draft) => submitDraft.mutate(draft.id, { onSuccess: onSubmitted }) },
      )
      return
    }
    submit.mutate({ typeKey, formData: formData() }, { onSuccess: onSubmitted })
  }

  if (!authenticated) {
    return (
      <div className="rounded-lg border border-line bg-surface-muted p-6 text-sm text-ink-muted">
        Choisissez un jeton pour remplir une demande.
      </div>
    )
  }

  const pending = submit.isPending || saveDraft.isPending || submitDraft.isPending
  const error = submit.error ?? saveDraft.error ?? submitDraft.error

  return (
    <Panel
      title={form.data ? form.data.label : `Demande ${typeKey}`}
      actions={
        <button
          type="button"
          onClick={onBack}
          className="flex items-center gap-1.5 rounded-md border border-line-strong px-2 py-1 text-xs text-ink hover:bg-surface-muted"
        >
          <ArrowLeft className="h-3.5 w-3.5" /> Retour
        </button>
      }
    >
      {form.isLoading && <p className="text-sm text-ink-muted">Chargement du formulaire…</p>}
      <ErrorNote error={form.error} />

      {form.data && (
        <>
          <p className="mb-4 text-xs text-ink-muted">
            <span className="font-mono">{form.data.typeKey}</span> · version {form.data.version}
            {currentDraftId ? ' · reprise d’un brouillon' : ''}
          </p>

          <form onSubmit={onSubmit} className="grid max-w-2xl gap-4">
            <SchemaForm
              fields={fields}
              values={values}
              onChange={set}
              disabled={pending}
              attachmentOf={(name) => byField[name] ?? null}
              uploadingField={uploadingField}
              fileErrors={fileErrors}
              onFile={(field, file) => void onFile(field, file)}
              onDownload={download.mutate}
              downloading={download.isPending}
            />

            {notice && (
              <p className="rounded-md border border-mfp-green bg-mfp-green/10 px-3 py-2 text-sm text-mfp-green">
                {notice}
              </p>
            )}
            <ErrorNote error={formError} />
            <ErrorNote error={error} />

            {fields.some((field) => field.widget === 'file') && !currentDraftId && (
              <p className="rounded-md border border-line bg-surface-muted px-3 py-2 text-xs text-ink-muted">
                Le dépôt d’une pièce jointe enregistre d’abord un brouillon, qui porte la
                pièce jointe jusqu’à la soumission de la demande.
              </p>
            )}

            {currentDraftId && (
              <div className="border-t border-line pt-4">
                <AttachmentList submissionId={currentDraftId} labels={labels} />
              </div>
            )}

            <div className="flex flex-wrap gap-2 border-t border-line pt-4">
              <button
                type="submit"
                disabled={pending}
                className="flex items-center gap-1.5 rounded-md bg-mfp-green px-4 py-2 text-sm font-medium text-white hover:bg-mfp-green/90 disabled:opacity-50"
              >
                <Send className="h-4 w-4" />
                {submit.isPending || submitDraft.isPending ? 'Envoi…' : 'Soumettre la demande'}
              </button>
              <button
                type="button"
                onClick={onSaveDraft}
                disabled={pending}
                className="flex items-center gap-1.5 rounded-md border border-line-strong px-4 py-2 text-sm text-ink hover:bg-surface-muted disabled:opacity-50"
              >
                <Save className="h-4 w-4" />
                {saveDraft.isPending ? 'Enregistrement…' : 'Enregistrer en brouillon'}
              </button>
            </div>
          </form>
        </>
      )}
    </Panel>
  )
}
