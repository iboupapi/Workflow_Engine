import { FilePlus2, Inbox, Send } from 'lucide-react'
import { useState } from 'react'
import { useDraft, useDrafts, useSubmitDraft } from '../api/hooks'
import type { SubmissionView } from '../api/types'
import { CatalogView } from './CatalogView'
import { AttachmentList } from './AttachmentList'
import { DynamicRequestForm } from './DynamicRequestForm'
import { Badge, ErrorNote, Panel } from './ui'

type Selection =
  | { mode: 'catalogue' }
  | { mode: 'new'; typeKey: string }
  | { mode: 'draft'; draftId: string }

/**
 * Parcours usager de la phase 3 : catalogue des types publies, formulaire dynamique,
 * brouillons reprenables et soumission creant le dossier du circuit.
 */
export function RequestsView({
  authenticated,
  onOpenInstance,
}: {
  authenticated: boolean
  onOpenInstance: (instanceId: string) => void
}) {
  const [selection, setSelection] = useState<Selection>({ mode: 'catalogue' })
  const [submitted, setSubmitted] = useState<SubmissionView | null>(null)
  const drafts = useDrafts(authenticated)

  function onSubmitted(submission: SubmissionView): void {
    setSubmitted(submission)
    setSelection({ mode: 'catalogue' })
  }

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,2fr)]">
      <div className="grid content-start gap-6">
        {selection.mode === 'catalogue' ? (
          <CatalogView
            authenticated={authenticated}
            onPick={(typeKey) => setSelection({ mode: 'new', typeKey })}
          />
        ) : (
          <Panel
            title={selection.mode === 'new' ? 'Nouvelle demande' : 'Reprise de brouillon'}
            actions={
              <button
                type="button"
                onClick={() => setSelection({ mode: 'catalogue' })}
                className="rounded-md border border-line-strong px-2 py-1 text-xs text-ink hover:bg-surface-muted"
              >
                Catalogue
              </button>
            }
          >
            {selection.mode === 'new' && (
              <DynamicRequestForm
                key={selection.typeKey}
                typeKey={selection.typeKey}
                draftId={null}
                savedValues={null}
                authenticated={authenticated}
                onBack={() => setSelection({ mode: 'catalogue' })}
                onSubmitted={onSubmitted}
              />
            )}
            {selection.mode === 'draft' && (
              <DraftForm
                draftId={selection.draftId}
                authenticated={authenticated}
                onBack={() => setSelection({ mode: 'catalogue' })}
                onSubmitted={onSubmitted}
              />
            )}
          </Panel>
        )}

        {submitted && (
          <Panel title="Demande soumise">
            <p className="text-sm text-ink">
              Votre demande a été transmise. Un dossier a été créé pour son traitement.
            </p>
            <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
              <dt className="text-ink-muted">Type</dt>
              <dd className="font-mono text-xs text-ink">{submitted.typeKey}</dd>
              <dt className="text-ink-muted">Version</dt>
              <dd className="text-ink">{submitted.version}</dd>
              <dt className="text-ink-muted">Dossier</dt>
              <dd className="font-mono text-xs text-ink">{submitted.instanceId ?? '—'}</dd>
            </dl>
            {submitted.instanceId && (
              <button
                type="button"
                onClick={() => onOpenInstance(submitted.instanceId as string)}
                className="mt-3 flex items-center gap-1.5 rounded-md bg-mfp-green px-3 py-1.5 text-sm font-medium text-white hover:bg-mfp-green/90"
              >
                <Inbox className="h-4 w-4" /> Ouvrir le dossier
              </button>
            )}
            <div className="mt-4 border-t border-line pt-4">
              <AttachmentList submissionId={submitted.id} />
            </div>
          </Panel>
        )}
      </div>

      <Panel title="Mes brouillons">
        {!authenticated ? (
          <p className="text-sm text-ink-muted">Choisissez un jeton pour voir vos brouillons.</p>
        ) : (
          <>
            {drafts.isLoading && <p className="text-sm text-ink-muted">Chargement…</p>}
            <ErrorNote error={drafts.error} />
            {drafts.data && drafts.data.length === 0 && (
              <p className="text-sm text-ink-muted">
                Aucun brouillon. Les demandes en cours de saisie apparaîtront ici.
              </p>
            )}
            <ul className="divide-y divide-line">
              {drafts.data?.map((draft) => (
                <li key={draft.id} className="flex items-center justify-between gap-3 py-3">
                  <button
                    type="button"
                    onClick={() => setSelection({ mode: 'draft', draftId: draft.id })}
                    className="flex flex-1 flex-col gap-1 text-left"
                  >
                    <span className="flex items-center gap-2 text-sm text-ink">
                      <FilePlus2 className="h-4 w-4 text-mfp-orange" />
                      {draft.typeKey}
                      <Badge tone="amber">brouillon</Badge>
                    </span>
                    <span className="text-xs text-ink-muted">
                      créé le {new Date(draft.createdAt).toLocaleString('fr-FR')} · version{' '}
                      {draft.version}
                    </span>
                  </button>
                  <SubmitDraftButton draftId={draft.id} onSubmitted={onSubmitted} />
                </li>
              ))}
            </ul>
          </>
        )}
      </Panel>
    </div>
  )
}

/** Charge la saisie d'un brouillon puis affiche le formulaire pre-rempli. */
function DraftForm({
  draftId,
  authenticated,
  onBack,
  onSubmitted,
}: {
  draftId: string
  authenticated: boolean
  onBack: () => void
  onSubmitted: (submission: SubmissionView) => void
}) {
  const draft = useDraft(draftId)

  if (draft.isLoading) {
    return <p className="text-sm text-ink-muted">Chargement du brouillon…</p>
  }
  if (draft.error) {
    return <ErrorNote error={draft.error} />
  }
  if (!draft.data) {
    return null
  }
  return (
    <DynamicRequestForm
      key={draft.data.id}
      typeKey={draft.data.typeKey}
      draftId={draft.data.id}
      savedValues={draft.data.formData}
      authenticated={authenticated}
      onBack={onBack}
      onSubmitted={onSubmitted}
    />
  )
}

/** Soumission directe d'un brouillon depuis la liste. */
function SubmitDraftButton({
  draftId,
  onSubmitted,
}: {
  draftId: string
  onSubmitted: (submission: SubmissionView) => void
}) {
  const submitDraft = useSubmitDraft()
  return (
    <button
      type="button"
      disabled={submitDraft.isPending}
      onClick={() => submitDraft.mutate(draftId, { onSuccess: onSubmitted })}
      className="flex items-center gap-1.5 rounded-md border border-mfp-green px-2 py-1 text-xs font-medium text-mfp-green hover:bg-mfp-green/10 disabled:opacity-50"
    >
      <Send className="h-3.5 w-3.5" />
      {submitDraft.isPending ? 'Envoi…' : 'Soumettre'}
    </button>
  )
}
