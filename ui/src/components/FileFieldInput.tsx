import { Download, FileText, Loader2, Paperclip, Upload } from 'lucide-react'
import { useRef, useState, type DragEvent } from 'react'
import type { AttachmentView } from '../api/types'
import { ATTACHMENT_LABELS, ATTACHMENT_TONES, formatBytes, isDownloadable } from '../lib/attachments'
import type { FormField } from '../lib/formSchema'
import { Badge, ErrorNote } from './ui'

/*
 * Extensions proposees au selecteur de fichiers : elles refletent la liste positive du
 * serveur, qui reste seul juge du type reel (l'en-tete declare par le navigateur n'est
 * qu'une intention). Un contenu refuse n'est jamais conserve.
 */
const ACCEPT = '.pdf,.png,.jpg,.jpeg,.gif,.zip,.txt'

/**
 * Depot d'une piece jointe (FRM-05) : glisser-deposer ou selecteur, avec le verdict du
 * controle sanitaire affiche des la reponse du serveur (analyse en cours, verifiee,
 * rejetee). Un contenu rejete n'est ni stocke ni telechargeable.
 */
export function FileFieldInput({
  field,
  attachment,
  uploading,
  disabled,
  error,
  onFile,
  onDownload,
  downloading,
}: {
  field: FormField
  attachment: AttachmentView | null
  uploading: boolean
  disabled: boolean
  error: unknown
  onFile: (file: File) => void
  onDownload: (attachment: AttachmentView) => void
  downloading: boolean
}) {
  const input = useRef<HTMLInputElement>(null)
  const [hovering, setHovering] = useState(false)

  function dropped(event: DragEvent<HTMLDivElement>): void {
    event.preventDefault()
    setHovering(false)
    const file = event.dataTransfer.files.item(0)
    if (file) {
      onFile(file)
    }
  }

  return (
    <div className="grid gap-2 text-sm">
      <span className="flex flex-wrap items-baseline gap-2 text-ink-muted">
        {field.label}
        {field.required && <span className="text-xs text-mfp-red">obligatoire</span>}
        <span className="text-[11px] tracking-wide text-ink-muted uppercase">pièce jointe</span>
      </span>
      {field.help && <span className="text-xs text-ink-muted">{field.help}</span>}

      <div
        onDragOver={(event) => {
          event.preventDefault()
          setHovering(true)
        }}
        onDragLeave={() => setHovering(false)}
        onDrop={dropped}
        className={`flex flex-wrap items-center gap-3 rounded-md border border-dashed px-3 py-3 transition-colors ${
          hovering ? 'border-mfp-green bg-mfp-green/10' : 'border-line-strong bg-surface-muted'
        }`}
      >
        <input
          ref={input}
          type="file"
          accept={ACCEPT}
          disabled={disabled}
          className="hidden"
          onChange={(event) => {
            const file = event.target.files?.item(0)
            if (file) {
              onFile(file)
            }
            event.target.value = ''
          }}
        />
        <button
          type="button"
          disabled={disabled}
          onClick={() => input.current?.click()}
          className="flex items-center gap-1.5 rounded-md border border-line-strong bg-surface px-3 py-1.5 text-xs text-ink hover:bg-surface-muted disabled:opacity-60"
        >
          {uploading ? (
            <Loader2 className="h-3.5 w-3.5 animate-spin" />
          ) : (
            <Upload className="h-3.5 w-3.5" />
          )}
          {uploading ? 'Dépôt en cours…' : attachment ? 'Remplacer le fichier' : 'Choisir un fichier'}
        </button>
        <span className="text-xs text-ink-muted">
          ou déposez le fichier ici · PDF, PNG, JPEG, GIF, ZIP ou texte
        </span>
      </div>

      {attachment && (
        <div className="flex flex-wrap items-center gap-3 rounded-md border border-line bg-surface px-3 py-2">
          <FileText className="h-4 w-4 shrink-0 text-ink-muted" />
          <span className="font-mono text-xs break-all text-ink">{attachment.filename}</span>
          <span className="text-xs text-ink-muted">{formatBytes(attachment.sizeBytes)}</span>
          <Badge tone={ATTACHMENT_TONES[attachment.scanStatus]}>
            {ATTACHMENT_LABELS[attachment.scanStatus]}
          </Badge>
          <button
            type="button"
            disabled={!isDownloadable(attachment) || downloading}
            title={
              isDownloadable(attachment)
                ? 'Télécharger la pièce jointe'
                : 'Téléchargement impossible : le contrôle sanitaire n’a pas autorisé ce contenu'
            }
            onClick={() => onDownload(attachment)}
            className="flex items-center gap-1.5 rounded-md border border-line-strong px-2 py-1 text-xs text-ink hover:bg-surface-muted disabled:opacity-50"
          >
            <Download className="h-3.5 w-3.5" /> Télécharger
          </button>
        </div>
      )}

      {!attachment && field.required && (
        <span className="flex items-center gap-1.5 text-xs text-ink-muted">
          <Paperclip className="h-3.5 w-3.5" /> Aucun fichier déposé pour ce champ.
        </span>
      )}

      <ErrorNote error={error} />
    </div>
  )
}
