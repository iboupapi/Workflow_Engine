import { Download, Loader2 } from 'lucide-react'
import { useAttachments, useDownloadAttachment, useInstanceAttachments } from '../api/hooks'
import { ATTACHMENT_LABELS, ATTACHMENT_TONES, formatBytes, isDownloadable } from '../lib/attachments'
import { Badge, ErrorNote } from './ui'

/**
 * Pieces jointes d'une saisie ou d'un dossier : metadonnees, verdict du controle sanitaire
 * et telechargement des seuls contenus autorises (FRM-05). Le contenu n'etant accessible
 * qu'aux personnes autorisees a voir le dossier, un refus se presente comme un 404.
 *
 * Deux sources : la saisie (brouillon repris ou demande soumise) et le dossier, pour la
 * fiche dossier — les deux suivent les memes regles de visibilite.
 */
export function AttachmentList({
  submissionId,
  instanceId,
  labels = {},
}: {
  submissionId?: string
  instanceId?: string
  labels?: Record<string, string>
}) {
  const bySubmission = useAttachments(submissionId ?? null)
  const byInstance = useInstanceAttachments(instanceId ?? null)
  const attachments = instanceId ? byInstance : bySubmission
  const download = useDownloadAttachment()
  const rows = attachments.data ?? []

  return (
    <div className="grid gap-2">
      <div className="flex items-center justify-between">
        <h3 className="text-xs font-semibold tracking-wide text-ink-muted uppercase">
          Pièces jointes ({rows.length})
        </h3>
        {attachments.isFetching && <Loader2 className="h-3.5 w-3.5 animate-spin text-ink-muted" />}
      </div>

      <ErrorNote error={attachments.error ?? download.error} />

      {attachments.isLoading && <p className="text-sm text-ink-muted">Chargement des pièces jointes…</p>}

      {!attachments.isLoading && rows.length === 0 && (
        <p className="text-sm text-ink-muted">
          {instanceId ? 'Aucune pièce jointe pour ce dossier.' : 'Aucune pièce jointe pour cette demande.'}
        </p>
      )}

      {rows.map((attachment) => (
        <div
          key={attachment.id}
          className="flex flex-wrap items-center gap-3 rounded-md border border-line bg-surface-muted px-3 py-2 text-sm"
        >
          <div className="grid min-w-48 flex-1 gap-0.5">
            <span className="font-mono text-xs break-all text-ink">{attachment.filename}</span>
            <span className="text-xs text-ink-muted">
              {labels[attachment.fieldPath] ?? attachment.fieldPath} · {attachment.mimeType} ·{' '}
              {formatBytes(attachment.sizeBytes)}
            </span>
            <span className="font-mono text-[11px] break-all text-ink-muted" title="Empreinte SHA-256">
              sha256 {attachment.sha256}
            </span>
          </div>
          <Badge tone={ATTACHMENT_TONES[attachment.scanStatus]}>
            {ATTACHMENT_LABELS[attachment.scanStatus]}
          </Badge>
          <button
            type="button"
            disabled={!isDownloadable(attachment) || download.isPending}
            title={
              isDownloadable(attachment)
                ? 'Télécharger la pièce jointe'
                : 'Téléchargement impossible : le contrôle sanitaire n’a pas autorisé ce contenu'
            }
            onClick={() => download.mutate(attachment)}
            className="flex items-center gap-1.5 rounded-md border border-line-strong bg-surface px-2 py-1 text-xs text-ink hover:bg-surface-muted disabled:opacity-50"
          >
            <Download className="h-3.5 w-3.5" /> Télécharger
          </button>
        </div>
      ))}
    </div>
  )
}
