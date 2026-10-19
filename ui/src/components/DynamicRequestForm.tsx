import { ArrowLeft, Save, Send } from 'lucide-react'
import { useMemo, useState, type FormEvent } from 'react'
import { useRequestForm, useSaveDraft, useSubmitDraft, useSubmitRequest } from '../api/hooks'
import type { SubmissionView } from '../api/types'
import { formFields, initialValues, toFormData } from '../lib/formSchema'
import { FormFieldInput } from './FormFieldInput'
import { ErrorNote, Panel } from './ui'

/**
 * Formulaire dynamique d'un type de demande : rendu a partir du JSON Schema publie,
 * enregistrement en brouillon (REQ-03) puis soumission (REQ-01), qui cree le dossier.
 * Les erreurs de validation renvoyees par le serveur (FRM-03) sont affichees telles quelles.
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
  const [edited, setEdited] = useState<Record<string, unknown> | null>(null)
  const [currentDraftId, setCurrentDraftId] = useState<string | null>(draftId)
  const [notice, setNotice] = useState<string | null>(null)

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

  function set(name: string, value: unknown): void {
    setEdited({ ...values, [name]: value })
  }

  function formData(): Record<string, unknown> {
    return toFormData(fields, values)
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

  function onSubmit(event: FormEvent): void {
    event.preventDefault()
    setNotice(null)
    if (currentDraftId) {
      submitDraft.mutate(currentDraftId, { onSuccess: onSubmitted })
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
            {fields.length === 0 && (
              <p className="text-sm text-ink-muted">Ce formulaire ne comporte aucun champ.</p>
            )}

            {fields.map((field) => (
              <FormFieldInput
                key={field.name}
                field={field}
                value={values[field.name]}
                disabled={pending}
                onChange={(value) => set(field.name, value)}
              />
            ))}

            {notice && (
              <p className="rounded-md border border-mfp-green bg-mfp-green/10 px-3 py-2 text-sm text-mfp-green">
                {notice}
              </p>
            )}
            <ErrorNote error={error} />

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
