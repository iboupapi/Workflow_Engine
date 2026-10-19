import { useState } from 'react'
import { useInstances } from '../api/hooks'
import type { InstanceView } from '../api/types'
import { statusTone } from '../lib/status'
import { InstanceDetail } from './InstanceDetail'
import { Badge, ErrorNote, Panel } from './ui'

const VIEWS: { id: InstanceView; label: string; hint: string }[] = [
  { id: 'MINE', label: 'MINE', hint: 'Mes dossiers (initiateur ou imputé)' },
  { id: 'INBOX', label: 'INBOX', hint: 'Boîte de réception de mes rôles' },
  { id: 'ALL', label: 'ALL', hint: 'Vue complète — réservée aux administrateurs / auditeurs' },
]

/**
 * Liste des dossiers (bannettes MINE / INBOX / ALL) et detail du dossier selectionne.
 *
 * `views` restreint les bannettes offertes : l'espace citoyen ne montre que les dossiers
 * dont l'usager est l'initiateur, les autres bannettes n'ayant de sens que pour
 * l'instruction. `selectedId` permet d'ouvrir directement un dossier (apres soumission).
 */
export function DossiersView({
  authenticated,
  views: available = ['MINE', 'INBOX', 'ALL'],
  selectedId = null,
}: {
  authenticated: boolean
  views?: InstanceView[]
  selectedId?: string | null
}) {
  const views = VIEWS.filter((candidate) => available.includes(candidate.id))
  const [wanted, setWanted] = useState<InstanceView | null>(null)
  const [picked, setPicked] = useState<string | null>(null)
  // Un changement d'espace peut retirer la bannette affichee (citoyen : MINE seul) : on
  // retombe alors sur la premiere bannette offerte, sans effet de synchronisation.
  const view: InstanceView =
    wanted !== null && available.includes(wanted) ? wanted : (available[0] ?? 'MINE')
  const { data, isLoading, error } = useInstances(view, authenticated)
  // Le dossier ouvert est celui choisi dans la liste, sinon celui designe par l'appelant :
  // aucun effet n'est necessaire, la valeur derivee suffit.
  const selected = picked ?? selectedId

  if (!authenticated) {
    return (
      <div className="rounded-lg border border-line bg-surface-muted p-6 text-sm text-ink-muted">
        Choisissez un jeton de test ci-dessus pour consulter les dossiers.
      </div>
    )
  }

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
      <Panel
        title="Dossiers"
        actions={
          <div className="flex gap-1">
            {views.map((candidate) => (
              <button
                key={candidate.id}
                type="button"
                title={candidate.hint}
                onClick={() => setWanted(candidate.id)}
                className={`rounded-md border px-2 py-1 text-xs ${
                  view === candidate.id
                    ? 'border-mfp-green bg-mfp-green/10 font-medium text-mfp-green'
                    : 'border-line-strong text-ink hover:bg-surface-muted'
                }`}
              >
                {candidate.label}
              </button>
            ))}
          </div>
        }
      >
        {isLoading && <p className="text-sm text-ink-muted">Chargement…</p>}
        <ErrorNote error={error} />
        <ul className="divide-y divide-line">
          {data?.content.map((instance) => (
            <li key={instance.id}>
              <button
                type="button"
                onClick={() => setPicked(instance.id)}
                className={`flex w-full flex-col gap-1 px-2 py-3 text-left hover:bg-surface-muted ${
                  selected === instance.id ? 'bg-mfp-green/5' : ''
                }`}
              >
                <span className="flex items-center justify-between gap-2">
                  <span className="font-mono text-xs text-ink-muted">
                    {instance.id.slice(0, 8)}
                  </span>
                  <Badge tone={statusTone(instance.status)}>{instance.status}</Badge>
                </span>
                <span className="text-sm text-ink">
                  {instance.definitionKey} · étape{' '}
                  <span className="font-medium">{instance.currentStepKey}</span>
                </span>
                <span className="text-xs text-ink-muted">
                  initiateur {instance.initiatorId ?? '—'}
                  {instance.assigneeId ? ` · imputé ${instance.assigneeId}` : ''}
                </span>
              </button>
            </li>
          ))}
        </ul>
        {data && data.content.length === 0 && (
          <p className="text-sm text-ink-muted">Aucun dossier dans cette vue.</p>
        )}
      </Panel>

      {selected ? (
        <InstanceDetail id={selected} />
      ) : (
        <Panel title="Détail">
          <p className="text-sm text-ink-muted">Sélectionnez un dossier.</p>
        </Panel>
      )}
    </div>
  )
}
