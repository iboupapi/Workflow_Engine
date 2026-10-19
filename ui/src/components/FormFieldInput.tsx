import type { AttachmentView } from '../api/types'
import type { FormField } from '../lib/formSchema'
import { FileFieldInput } from './FileFieldInput'

/**
 * Saisie d'un champ du formulaire dynamique, selon le widget deduit du JSON Schema.
 * Les champs pieces jointes delegent au depot de fichier (FRM-05) : leur valeur n'est pas
 * saisie mais renseignee par l'identifiant de la piece jointe stockee.
 */
export function FormFieldInput({
  field,
  value,
  disabled,
  onChange,
  attachment = null,
  uploading = false,
  fileError = null,
  onFile,
  onDownload,
  downloading = false,
}: {
  field: FormField
  value: unknown
  disabled: boolean
  onChange: (value: unknown) => void
  attachment?: AttachmentView | null
  uploading?: boolean
  fileError?: unknown
  onFile?: (file: File) => void
  onDownload?: (attachment: AttachmentView) => void
  downloading?: boolean
}) {
  const inputClass =
    'rounded-md border border-line-strong bg-surface p-2 text-sm text-ink focus:border-mfp-green focus:outline-none disabled:opacity-60'
  const label = (
    <span className="flex flex-wrap items-baseline gap-2 text-ink-muted">
      {field.label}
      {field.required && <span className="text-xs text-mfp-red">obligatoire</span>}
      {field.property.format && (
        <span className="text-[11px] tracking-wide text-ink-muted uppercase">
          {field.property.format}
        </span>
      )}
    </span>
  )

  if (field.widget === 'file') {
    return (
      <FileFieldInput
        field={field}
        attachment={attachment}
        uploading={uploading}
        disabled={disabled}
        error={fileError}
        onFile={onFile ?? (() => undefined)}
        onDownload={onDownload ?? (() => undefined)}
        downloading={downloading}
      />
    )
  }

  if (field.widget === 'checkbox') {
    return (
      <label className="grid gap-1 text-sm">
        <span className="flex items-center gap-2">
          <input
            type="checkbox"
            checked={value === true}
            disabled={disabled}
            onChange={(event) => onChange(event.target.checked)}
            className="h-4 w-4 accent-mfp-green"
          />
          <span className="text-ink">{field.label}</span>
          {field.required && <span className="text-xs text-mfp-red">obligatoire</span>}
        </span>
        {field.help && <span className="text-xs text-ink-muted">{field.help}</span>}
      </label>
    )
  }

  if (field.widget === 'select') {
    return (
      <label className="grid gap-1 text-sm">
        {label}
        <select
          value={typeof value === 'string' ? value : ''}
          disabled={disabled}
          onChange={(event) => onChange(event.target.value)}
          className={inputClass}
        >
          <option value="">—</option>
          {field.options.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        {field.help && <span className="text-xs text-ink-muted">{field.help}</span>}
      </label>
    )
  }

  if (field.widget === 'multiselect') {
    const selected = Array.isArray(value) ? value.map(String) : []
    return (
      <fieldset className="grid gap-1 text-sm">
        <legend className="text-ink-muted">{field.label}</legend>
        <div className="flex flex-wrap gap-3">
          {field.options.map((option) => (
            <label key={option.value} className="flex items-center gap-1.5 text-ink">
              <input
                type="checkbox"
                checked={selected.includes(option.value)}
                disabled={disabled}
                onChange={(event) =>
                  onChange(
                    event.target.checked
                      ? [...selected, option.value]
                      : selected.filter((candidate) => candidate !== option.value),
                  )
                }
                className="h-4 w-4 accent-mfp-green"
              />
              {option.label}
            </label>
          ))}
        </div>
        {field.help && <span className="text-xs text-ink-muted">{field.help}</span>}
      </fieldset>
    )
  }

  if (field.widget === 'textarea') {
    return (
      <label className="grid gap-1 text-sm">
        {label}
        <textarea
          value={typeof value === 'string' ? value : ''}
          disabled={disabled}
          onChange={(event) => onChange(event.target.value)}
          rows={4}
          className={inputClass}
        />
        {field.help && <span className="text-xs text-ink-muted">{field.help}</span>}
      </label>
    )
  }

  const inputType =
    field.widget === 'number'
      ? 'number'
      : field.widget === 'date'
        ? 'date'
        : field.widget === 'email'
          ? 'email'
          : 'text'

  return (
    <label className="grid gap-1 text-sm">
      {label}
      <input
        type={inputType}
        value={typeof value === 'string' || typeof value === 'number' ? String(value) : ''}
        disabled={disabled}
        min={field.property.minimum}
        max={field.property.maximum}
        pattern={field.property.pattern}
        onChange={(event) => onChange(event.target.value)}
        className={inputClass}
      />
      {field.help && <span className="text-xs text-ink-muted">{field.help}</span>}
    </label>
  )
}
