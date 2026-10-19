import { Ban, FileCog, Plus } from 'lucide-react'
import { useState } from 'react'
import { useAdminRequestTypes } from '../../api/hooks'
import type { AdminRequestTypeView } from '../../api/types'
import { ApiError } from '../../api/client'
import { requestTypeLabel, requestTypeTone } from '../../lib/status'
import { Badge, ErrorNote, Panel } from '../ui'
import { EserviceEditor } from './EserviceEditor'

type Selection = { mode: 'list' } | { mode: 'new' } | { mode: 'edit'; typeId: string }

/**
 * Espace d'administration : catalogue des E-Services (tous statuts) et editeur.
 *
 * Cet ecran ne se contente pas d'afficher les jetons de test : c'est le serveur qui decide
 * qui administre, en comparant le SUJET du jeton a la liste des administrateurs nommes
 * (`auth.admin.subjects`). Un role ne suffit donc pas, et un refus doit etre dit tel quel
 * plutot que masque derriere un bouton inoperant.
 */
export function AdminEservicesView() {
  const types = useAdminRequestTypes()
  const [selection, setSelection] = useState<Selection>({ mode: 'list' })

  if (selection.mode === 'new') {
    return (
      <EserviceEditor
        typeId={null}
        onClose={() => setSelection({ mode: 'list' })}
        onPublished={() => undefined}
      />
    )
  }

  if (selection.mode === 'edit') {
    return (
      <EserviceEditor
        typeId={selection.typeId}
        onClose={() => setSelection({ mode: 'list' })}
        onPublished={() => undefined}
      />
    )
  }

  const data = types.data ?? []
  const counts = {
    published: data.filter((type) => type.status === 'PUBLISHED').length,
    draft: data.filter((type) => type.status === 'DRAFT').length,
  }

  return (
    <div className="grid gap-6">
      <Panel
        title="E-Services"
        actions={
          <button
            type="button"
            onClick={() => setSelection({ mode: 'new' })}
            className="flex items-center gap-1.5 rounded-md bg-mfp-green px-3 py-1.5 text-xs font-medium text-white hover:bg-mfp-green/90"
          >
            <Plus className="h-3.5 w-3.5" /> Nouvel E-Service
          </button>
        }
      >
        {types.isLoading && <p className="text-sm text-ink-muted">Chargement du référentiel…</p>}

        {types.error != null &&
          (types.error instanceof ApiError && types.error.status === 403 ? (
            <div className="flex items-start gap-2 rounded-md border border-mfp-red bg-mfp-red/10 px-3 py-2 text-sm text-mfp-red">
              <Ban className="mt-0.5 h-4 w-4 shrink-0" />
              <p>
                Le jeton courant n’administre pas : l’administration est réservée aux sujets
                déclarés côté serveur (<span className="font-mono">auth.admin.subjects</span>).
                Un rôle ne suffit pas à ouvrir cet espace.
              </p>
            </div>
          ) : (
            <ErrorNote error={types.error} />
          ))}

        {types.data && (
          <>
            <p className="mb-3 text-xs text-ink-muted">
              {data.length} type{data.length > 1 ? 's' : ''} de demande · {counts.published}{' '}
              publié{counts.published > 1 ? 's' : ''} · {counts.draft} brouillon
              {counts.draft > 1 ? 's' : ''}
            </p>

            {data.length === 0 && (
              <p className="text-sm text-ink-muted">
                Aucun type de demande au référentiel. « Nouvel E-Service » en crée un premier,
                qui n’apparaîtra chez les usagers qu’une fois publié.
              </p>
            )}

            {data.length > 0 && (
              <table className="w-full text-left text-sm">
                <thead className="text-xs tracking-wide text-ink-muted uppercase">
                  <tr className="border-b border-line">
                    <th className="py-2 pr-4">Clé</th>
                    <th className="py-2 pr-4">Libellé</th>
                    <th className="py-2 pr-4">Catégorie</th>
                    <th className="py-2 pr-4">Statut</th>
                    <th className="py-2 pr-4">Version</th>
                    <th className="py-2 pr-4">Circuit</th>
                    <th className="py-2">Publié le</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {data.map((type) => (
                    <TypeRow
                      key={type.id}
                      type={type}
                      onOpen={() => setSelection({ mode: 'edit', typeId: type.id })}
                    />
                  ))}
                </tbody>
              </table>
            )}
          </>
        )}
      </Panel>

      <Panel title="Publier un E-Service">
        <ol className="grid gap-2 text-sm text-ink">
          <li className="flex gap-2">
            <FileCog className="mt-0.5 h-4 w-4 shrink-0 text-mfp-green" />
            <span>
              <strong>Métadonnées</strong> : la clé du type de demande (identité stable, utilisée
              dans les URL) et le libellé présenté à l’usager.
            </span>
          </li>
          <li className="flex gap-2">
            <FileCog className="mt-0.5 h-4 w-4 shrink-0 text-mfp-green" />
            <span>
              <strong>Circuit</strong> : le circuit qui traitera les demandes, et la liaison
              éventuelle entre les champs du formulaire et ses variables déclarées.
            </span>
          </li>
          <li className="flex gap-2">
            <FileCog className="mt-0.5 h-4 w-4 shrink-0 text-mfp-green" />
            <span>
              <strong>Formulaire</strong> : le JSON Schema des champs, avec l’aperçu du rendu réel.
              Publier crée une version : une correction passe par une nouvelle publication, les
              dossiers ouverts restant interprétés avec la version qui les a vus naître.
            </span>
          </li>
        </ol>
      </Panel>
    </div>
  )
}

/** Ligne du référentiel : tout y est repris du serveur, rien n'est déduit du libellé. */
function TypeRow({ type, onOpen }: { type: AdminRequestTypeView; onOpen: () => void }) {
  return (
    <tr className="align-top">
      <td className="py-3 pr-4">
        <button
          type="button"
          onClick={onOpen}
          className="font-mono text-xs text-mfp-green underline-offset-2 hover:underline"
        >
          {type.key}
        </button>
      </td>
      <td className="py-3 pr-4 text-ink">{type.label}</td>
      <td className="py-3 pr-4 text-ink-muted">{type.category ?? '—'}</td>
      <td className="py-3 pr-4">
        <Badge tone={requestTypeTone(type.status)}>{requestTypeLabel(type.status)}</Badge>
      </td>
      <td className="py-3 pr-4 text-ink">{type.version ?? '—'}</td>
      <td className="py-3 pr-4 font-mono text-xs text-ink">{type.definitionKey ?? '—'}</td>
      <td className="py-3 text-ink-muted">
        {type.publishedAt ? new Date(type.publishedAt).toLocaleString('fr-FR') : '—'}
      </td>
    </tr>
  )
}
