import type { ReactNode } from 'react'
import type { AttachmentView } from '../api/types'
import type { FormField } from '../lib/formSchema'
import { FormFieldInput } from './FormFieldInput'

/**
 * Rendu PRESENTATIF d'un formulaire decrit par un JSON Schema : les champs et leur mise en
 * page, rien d'autre. Aucun appel au serveur, aucune donnee propre, aucune action : c'est
 * l'appelant qui fournit la saisie, les pieces jointes et son pied de page.
 *
 * Deux usages partagent donc exactement le meme rendu : le formulaire d'usager (saisie,
 * brouillon, depot et soumission) et l'apercu temps reel de l'editeur d'administration
 * (schema non encore publie). L'agent qui publie voit ainsi ce que verra l'usager, et non
 * une maquette qui divergerait du formulaire reel.
 */
export function SchemaForm({
  fields,
  values,
  onChange,
  disabled = false,
  attachmentOf,
  uploadingField = null,
  fileErrors = {},
  onFile,
  onDownload,
  downloading = false,
  children,
}: {
  fields: FormField[]
  values: Record<string, unknown>
  onChange: (name: string, value: unknown) => void
  /** Verrouille la saisie (apercu d'administration, operation en cours). */
  disabled?: boolean
  /** Piece jointe rattachee a un champ (FRM-05) ; absent : aucun depot n'est branche. */
  attachmentOf?: (name: string) => AttachmentView | null
  uploadingField?: string | null
  fileErrors?: Record<string, unknown>
  onFile?: (field: FormField, file: File) => void
  onDownload?: (attachment: AttachmentView) => void
  downloading?: boolean
  children?: ReactNode
}) {
  return (
    <div className="grid max-w-2xl gap-4">
      {fields.length === 0 && (
        <p className="text-sm text-ink-muted">Ce formulaire ne comporte aucun champ.</p>
      )}

      {fields.map((field) => (
        <FormFieldInput
          key={field.name}
          field={field}
          value={values[field.name]}
          disabled={disabled}
          onChange={(value) => onChange(field.name, value)}
          attachment={attachmentOf ? attachmentOf(field.name) : null}
          uploading={uploadingField === field.name}
          fileError={fileErrors[field.name] ?? null}
          onFile={onFile ? (file) => onFile(field, file) : undefined}
          onDownload={onDownload}
          downloading={downloading}
        />
      ))}

      {children}
    </div>
  )
}
