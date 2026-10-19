import { ArrowLeft, ArrowRight, Check, Rocket } from 'lucide-react'
import { useMemo, useState } from 'react'
import {
  useAdminRequestType,
  useCreateFormSchema,
  useCreateRequestType,
  useDefinition,
  useDeployedWorkflows,
  usePublishRequestType,
} from '../../api/hooks'
import type { AdminRequestTypeView } from '../../api/types'
import {
  parseSchemaText,
  previewFields,
  SCHEMA_PLACEHOLDER,
  UI_SCHEMA_PLACEHOLDER,
} from '../../lib/adminSchema'
import { initialValues } from '../../lib/formSchema'
import { requestTypeLabel, requestTypeTone } from '../../lib/status'
import { SchemaForm } from '../SchemaForm'
import { Badge, ErrorNote, Panel } from '../ui'

const INPUT =
  'rounded-md border border-line-strong bg-surface p-2 text-sm text-ink focus:border-mfp-green focus:outline-none disabled:opacity-60'
const BUTTON =
  'flex items-center gap-1.5 rounded-md border border-line-strong px-3 py-1.5 text-sm text-ink hover:bg-surface-muted disabled:opacity-50'

interface Metadata {
  key: string
  label: string
  category: string
}

/**
 * Editeur d'E-Service en trois etapes : metadonnees du type de demande, circuit qui
 * traitera les demandes, puis formulaire (JSON Schema) avec apercu temps reel.
 *
 * Rien n'est publie avant le dernier ecran, et rien n'est invente : le referentiel est
 * immuable (declencheur V13), donc publier cree toujours une NOUVELLE version. Reprendre un
 * type existant ne modifie donc rien : cela prepare la version suivante, et les dossiers
 * deja ouverts restent interpretables avec la version qui les a vus naitre (FRM-04).
 */
export function EserviceEditor({
  typeId,
  onClose,
  onPublished,
}: {
  /** Type a reprendre, ou null pour un nouveau type de demande. */
  typeId: string | null
  onClose: () => void
  onPublished: (view: AdminRequestTypeView) => void
}) {
  const detail = useAdminRequestType(typeId)
  const circuits = useDeployedWorkflows()
  const createType = useCreateRequestType()
  const createSchema = useCreateFormSchema()
  const publishVersion = usePublishRequestType()

  const [step, setStep] = useState(1)
  const [createdId, setCreatedId] = useState<string | null>(null)
  const [metadata, setMetadata] = useState<Metadata | null>(null)
  const [circuit, setCircuit] = useState<string | null>(null)
  const [mapping, setMapping] = useState<Record<string, string> | null>(null)
  const [jsonText, setJsonText] = useState<string | null>(null)
  const [uiText, setUiText] = useState<string | null>(null)
  const [previewEdited, setPreviewEdited] = useState<Record<string, unknown> | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [result, setResult] = useState<AdminRequestTypeView | null>(null)
  const [pending, setPending] = useState(false)

  const existing = detail.data ?? null
  const activeId = createdId ?? typeId

  /*
   * Aucun effet de synchronisation : tant que l'agent n'a rien saisi, chaque valeur est
   * derivee du type repris (chargement serveur) ; la premiere frappe fige l'etat edite.
   */
  const shownMetadata: Metadata =
    metadata ??
    (existing
      ? { key: existing.key, label: existing.label, category: existing.category ?? '' }
      : { key: '', label: '', category: '' })

  const shownCircuit = circuit ?? existing?.definitionKey ?? ''
  const variables = useDefinition(shownCircuit, shownCircuit !== '').data?.variables ?? []
  const effectiveMapping: Record<string, string> =
    mapping ??
    (existing && existing.definitionKey === shownCircuit ? (existing.fieldMapping ?? {}) : {})

  const shownJson =
    jsonText ?? (existing?.jsonSchema ? JSON.stringify(existing.jsonSchema, null, 2) : '')
  const shownUi = uiText ?? (existing?.uiSchema ? JSON.stringify(existing.uiSchema, null, 2) : '')

  const parsed = useMemo(() => parseSchemaText(shownJson, shownUi), [shownJson, shownUi])
  const fields = useMemo(() => previewFields(parsed), [parsed])
  const previewValues = previewEdited ?? initialValues(parsed.schema ?? undefined, null)

  function createDraft(): void {
    setError(null)
    if (activeId) {
      setStep(2)
      return
    }
    createType.mutate(
      {
        typeKey: shownMetadata.key.trim(),
        label: shownMetadata.label.trim(),
        category: shownMetadata.category.trim(),
      },
      {
        onSuccess: (view) => {
          setCreatedId(view.id)
          setStep(2)
        },
        onError: setError,
      },
    )
  }

  async function publish(): Promise<void> {
    setError(null)
    if (!activeId) {
      setError(new Error('Créez d’abord le brouillon du type de demande.'))
      return
    }
    if (parsed.error !== null || parsed.schema === null) {
      setError(new Error(parsed.error ?? 'Le JSON Schema n’est pas exploitable.'))
      return
    }
    if (shownCircuit === '') {
      setError(new Error('Choisissez le circuit qui traitera les demandes.'))
      return
    }
    setPending(true)
    try {
      const schema = await createSchema.mutateAsync({
        schemaJson: parsed.schema,
        uiSchema: parsed.uiSchema,
      })
      const view = await publishVersion.mutateAsync({
        requestTypeId: activeId,
        formSchemaId: schema.id,
        workflowDefinitionKey: shownCircuit,
        // Une liaison vide n'est pas transmise : le moteur n'enregistre alors aucune
        // correspondance entre champs du formulaire et variables du circuit.
        fieldMapping: Object.keys(effectiveMapping).length > 0 ? effectiveMapping : undefined,
      })
      setResult(view)
      onPublished(view)
    } catch (cause) {
      setError(cause)
    } finally {
      setPending(false)
    }
  }

  if (result) {
    return (
      <Panel
        title="E-Service publié"
        actions={
          <button type="button" onClick={onClose} className={BUTTON}>
            <ArrowLeft className="h-4 w-4" /> Retour à la liste
          </button>
        }
      >
        <p className="text-sm text-ink">
          <span className="font-mono">{result.key}</span> est désormais proposé aux usagers,
          dans sa version {result.version}.
        </p>
        <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-ink-muted">Circuit</dt>
          <dd className="font-mono text-xs text-ink">{result.definitionKey ?? '—'}</dd>
          <dt className="text-ink-muted">Publiée le</dt>
          <dd className="text-ink">
            {result.publishedAt ? new Date(result.publishedAt).toLocaleString('fr-FR') : '—'}
          </dd>
          <dt className="text-ink-muted">Statut</dt>
          <dd>
            <Badge tone={requestTypeTone(result.status)}>{requestTypeLabel(result.status)}</Badge>
          </dd>
        </dl>
      </Panel>
    )
  }

  const busy = pending || createType.isPending || createSchema.isPending || publishVersion.isPending

  return (
    <Panel
      title={existing ? `Nouvelle version de ${existing.key}` : 'Nouvel E-Service'}
      actions={
        <button type="button" onClick={onClose} className={BUTTON}>
          <ArrowLeft className="h-4 w-4" /> Retour à la liste
        </button>
      }
    >
      {typeId !== null && (
        <>
          {detail.isLoading && <p className="text-sm text-ink-muted">Chargement du type…</p>}
          <ErrorNote error={detail.error} />
        </>
      )}

      <ol className="mb-5 flex flex-wrap gap-2 text-xs">
        {['Métadonnées', 'Circuit', 'Formulaire'].map((label, index) => {
          const number = index + 1
          const active = step === number
          const done = step > number
          return (
            <li
              key={label}
              className={`flex items-center gap-1.5 rounded-full border px-3 py-1 ${
                active
                  ? 'border-mfp-green bg-mfp-green/10 font-medium text-mfp-green'
                  : done
                    ? 'border-line-strong bg-surface-muted text-ink'
                    : 'border-line text-ink-muted'
              }`}
            >
              {done ? <Check className="h-3.5 w-3.5" /> : <span>{number}</span>}
              {label}
            </li>
          )
        })}
      </ol>

      {step === 1 && (
        <div className="grid max-w-xl gap-4">
          {existing ? (
            <p className="rounded-md border border-line bg-surface-muted px-3 py-2 text-xs text-ink-muted">
              La clé <span className="font-mono">{existing.key}</span> est l’identité stable du
              type : elle ne change pas d’une version à l’autre. Le libellé et la catégorie non
              plus, faute d’endpoint de modification — les corriger suppose de créer un autre
              type.
            </p>
          ) : (
            <p className="text-xs text-ink-muted">
              Le type de demande est d’abord créé en brouillon : il n’apparaît dans le catalogue
              des usagers qu’une fois une version publiée.
            </p>
          )}

          <label className="grid gap-1 text-sm">
            <span className="text-ink-muted">Clé (majuscules, chiffres et soulignés)</span>
            <input
              type="text"
              value={shownMetadata.key}
              disabled={existing !== null || busy}
              placeholder="DEMANDE_MEDAILLE"
              onChange={(event) =>
                setMetadata({ ...shownMetadata, key: event.target.value.toUpperCase() })
              }
              className={`${INPUT} font-mono`}
            />
          </label>

          <label className="grid gap-1 text-sm">
            <span className="text-ink-muted">Libellé présenté à l’usager</span>
            <input
              type="text"
              value={shownMetadata.label}
              disabled={existing !== null || busy}
              onChange={(event) => setMetadata({ ...shownMetadata, label: event.target.value })}
              className={INPUT}
            />
          </label>

          <label className="grid gap-1 text-sm">
            <span className="text-ink-muted">Catégorie (facultative)</span>
            <input
              type="text"
              value={shownMetadata.category}
              disabled={existing !== null || busy}
              onChange={(event) => setMetadata({ ...shownMetadata, category: event.target.value })}
              className={INPUT}
            />
          </label>

          <ErrorNote error={error} />

          <div className="flex gap-2 border-t border-line pt-4">
            <button
              type="button"
              disabled={busy || shownMetadata.key.trim() === '' || shownMetadata.label.trim() === ''}
              onClick={createDraft}
              className="flex items-center gap-1.5 rounded-md bg-mfp-green px-4 py-2 text-sm font-medium text-white hover:bg-mfp-green/90 disabled:opacity-50"
            >
              {existing ? 'Continuer' : 'Créer le brouillon et continuer'}
              <ArrowRight className="h-4 w-4" />
            </button>
          </div>
        </div>
      )}

      {step === 2 && (
        <div className="grid max-w-2xl gap-4">
          <label className="grid gap-1 text-sm">
            <span className="text-ink-muted">Circuit qui traitera les demandes</span>
            <select
              value={shownCircuit}
              onChange={(event) => {
                const chosen = event.target.value
                setCircuit(chosen)
                // Changer de circuit invalide les liaisons : les variables ne sont pas les
                // mêmes d'une définition à l'autre.
                setMapping({})
              }}
              className={INPUT}
            >
              <option value="">-</option>
              {circuits.data?.map((workflow) => (
                <option key={workflow.key} value={workflow.key}>
                  {workflow.key} v{workflow.version} — {workflow.name}
                </option>
              ))}
            </select>
          </label>
          <ErrorNote error={circuits.error} />

          {shownCircuit === '' && (
            <p className="text-xs text-ink-muted">
              Choisissez un circuit pour préparer la liaison des champs du formulaire.
            </p>
          )}

          {shownCircuit !== '' && (
            <div className="grid gap-2">
              <p className="text-xs text-ink-muted">
                Liaison des champs (REQ-02) : elle indique au moteur quel champ du formulaire
                alimente quelle variable déclarée par le circuit. Facultative — un formulaire
                peut n’alimenter aucune variable.
              </p>
              {variables.length === 0 ? (
                <p className="rounded-md border border-mfp-yellow bg-mfp-yellow/25 px-3 py-2 text-xs text-ink">
                  Ce circuit ne déclare aucune variable : aucune liaison n’est possible. Publier
                  un formulaire avec des liaisons serait refusé.
                </p>
              ) : (
                <ul className="divide-y divide-line rounded-md border border-line">
                  {fields.length === 0 && (
                    <li className="px-3 py-2 text-xs text-ink-muted">
                      Les champs du formulaire apparaîtront ici dès que le JSON Schema sera
                      exploitable (étape 3).
                    </li>
                  )}
                  {fields.map((field) => (
                    <li
                      key={field.name}
                      className="flex flex-wrap items-center justify-between gap-3 px-3 py-2 text-sm"
                    >
                      <span className="flex items-baseline gap-2">
                        <span className="font-mono text-xs text-ink">{field.name}</span>
                        <span className="text-ink-muted">{field.label}</span>
                      </span>
                      <select
                        value={effectiveMapping[field.name] ?? ''}
                        onChange={(event) => {
                          const target = event.target.value
                          const next = { ...effectiveMapping }
                          if (target === '') {
                            delete next[field.name]
                          } else {
                            next[field.name] = target
                          }
                          setMapping(next)
                        }}
                        className={`${INPUT} font-mono text-xs`}
                      >
                        <option value="">— non lié —</option>
                        {variables.map((variable) => (
                          <option key={variable.key} value={variable.key}>
                            {variable.key} ({variable.type ?? 'type libre'})
                          </option>
                        ))}
                      </select>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}

          <ErrorNote error={error} />

          <div className="flex gap-2 border-t border-line pt-4">
            <button type="button" onClick={() => setStep(1)} className={BUTTON}>
              <ArrowLeft className="h-4 w-4" /> Précédent
            </button>
            <button
              type="button"
              disabled={shownCircuit === ''}
              onClick={() => setStep(3)}
              className="flex items-center gap-1.5 rounded-md bg-mfp-green px-4 py-2 text-sm font-medium text-white hover:bg-mfp-green/90 disabled:opacity-50"
            >
              Continuer <ArrowRight className="h-4 w-4" />
            </button>
          </div>
        </div>
      )}

      {step === 3 && (
        <div className="grid gap-6 lg:grid-cols-2">
          <div className="grid content-start gap-4">
            <label className="grid gap-1 text-sm">
              <span className="text-ink-muted">JSON Schema 2020-12 du formulaire</span>
              <textarea
                value={shownJson}
                disabled={busy}
                rows={14}
                spellCheck={false}
                placeholder={SCHEMA_PLACEHOLDER}
                onChange={(event) => setJsonText(event.target.value)}
                className={`${INPUT} font-mono text-xs`}
              />
            </label>

            <label className="grid gap-1 text-sm">
              <span className="text-ink-muted">UI Schema (ordre et libellés, facultatif)</span>
              <textarea
                value={shownUi}
                disabled={busy}
                rows={4}
                spellCheck={false}
                placeholder={UI_SCHEMA_PLACEHOLDER}
                onChange={(event) => setUiText(event.target.value)}
                className={`${INPUT} font-mono text-xs`}
              />
            </label>

            <ErrorNote error={parsed.error ? new Error(parsed.error) : null} />
            <ErrorNote error={error} />

            <div className="flex gap-2 border-t border-line pt-4">
              <button type="button" onClick={() => setStep(2)} disabled={busy} className={BUTTON}>
                <ArrowLeft className="h-4 w-4" /> Précédent
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={() => void publish()}
                className="flex items-center gap-1.5 rounded-md bg-mfp-green px-4 py-2 text-sm font-medium text-white hover:bg-mfp-green/90 disabled:opacity-50"
              >
                <Rocket className="h-4 w-4" />
                {pending ? 'Publication…' : existing ? 'Publier la nouvelle version' : 'Publier'}
              </button>
            </div>

            {existing && (
              <p className="text-xs text-ink-muted">
                Publier créera la version {(existing.version ?? 0) + 1} ; la version{' '}
                {existing.version ?? '—'} reste inchangée et continue d’interpréter les dossiers
                déjà ouverts.
              </p>
            )}
          </div>

          <div className="grid content-start gap-2">
            <span className="text-xs font-semibold tracking-wide text-ink-muted uppercase">
              Aperçu — ce que verra l’usager
            </span>
            <div className="rounded-lg border border-line bg-surface p-4">
              {parsed.error !== null ? (
                <p className="text-sm text-ink-muted">
                  L’aperçu apparaîtra dès que le JSON Schema sera exploitable.
                </p>
              ) : (
                <SchemaForm
                  fields={fields}
                  values={previewValues}
                  disabled
                  onChange={(name, value) =>
                    setPreviewEdited({ ...previewValues, [name]: value })
                  }
                />
              )}
            </div>
            <p className="text-xs text-ink-muted">
              Même rendu que le formulaire réel : l’aperçu est produit par le composant qui sert
              la saisie des usagers, pas par une maquette à part.
            </p>
          </div>
        </div>
      )}
    </Panel>
  )
}
