import { FileText, RefreshCw } from 'lucide-react'
import { useRequestTypes } from '../api/hooks'
import { ErrorNote, Panel } from './ui'

/** Catalogue des types de demande publies (REQ-01). */
export function CatalogView({
  authenticated,
  onPick,
}: {
  authenticated: boolean
  onPick: (typeKey: string) => void
}) {
  const types = useRequestTypes(authenticated)

  if (!authenticated) {
    return (
      <div className="rounded-lg border border-line bg-surface-muted p-6 text-sm text-ink-muted">
        Choisissez un jeton ci-dessus pour consulter le catalogue des demandes.
      </div>
    )
  }

  return (
    <Panel
      title="Catalogue des demandes"
      actions={
        <button
          type="button"
          onClick={() => {
            void types.refetch()
          }}
          className="flex items-center gap-1.5 rounded-md border border-line-strong px-2 py-1 text-xs text-ink hover:bg-surface-muted"
        >
          <RefreshCw className="h-3.5 w-3.5" /> Rafraîchir
        </button>
      }
    >
      <p className="mb-4 text-sm text-ink-muted">
        Types de demande publiés par l’administration. Sélectionnez-en un pour le remplir.
      </p>

      {types.isLoading && <p className="text-sm text-ink-muted">Chargement du catalogue…</p>}
      <ErrorNote error={types.error} />

      {types.data && types.data.length === 0 && (
        <p className="rounded-md border border-mfp-yellow bg-mfp-yellow/25 px-3 py-2 text-sm text-ink">
          Aucun type de demande n’est publié. Publiez un type et sa version pour qu’il apparaisse
          ici.
        </p>
      )}

      <ul className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        {types.data?.map((type) => (
          <li key={type.id}>
            <button
              type="button"
              onClick={() => onPick(type.key)}
              className="flex h-full w-full flex-col gap-2 rounded-lg border border-line bg-surface p-4 text-left transition hover:border-mfp-green hover:bg-mfp-green/5"
            >
              <span className="flex items-start justify-between gap-2">
                <span className="flex items-center gap-2 text-sm font-semibold text-ink">
                  <FileText className="h-4 w-4 text-mfp-green" />
                  {type.label}
                </span>
                {type.category && (
                  <span className="rounded-full border border-line-strong px-2 py-0.5 text-[11px] text-ink-muted">
                    {type.category}
                  </span>
                )}
              </span>
              <span className="font-mono text-xs text-ink-muted">{type.key}</span>
            </button>
          </li>
        ))}
      </ul>
    </Panel>
  )
}
