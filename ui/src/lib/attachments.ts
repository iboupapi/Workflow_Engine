import type { AttachmentScanStatus, AttachmentView } from '../api/types'
import type { Tone } from './status'

/** Couleur du verdict de controle sanitaire (FRM-05). */
export const ATTACHMENT_TONES: Record<AttachmentScanStatus, Tone> = {
  PENDING: 'amber',
  CLEAN: 'green',
  INFECTED: 'red',
  ERROR: 'red',
}

export const ATTACHMENT_LABELS: Record<AttachmentScanStatus, string> = {
  PENDING: 'analyse en cours',
  CLEAN: 'vérifiée',
  INFECTED: 'rejetée (menace détectée)',
  ERROR: 'analyse indisponible',
}

/** Une piece jointe n'est telechargeable que si le controle sanitaire l'a autorisee. */
export function isDownloadable(attachment: AttachmentView): boolean {
  return attachment.scanStatus === 'CLEAN'
}

/** Taille lisible (`2,4 Mio`) sans dependance externe. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} o`
  }
  const units = ['Kio', 'Mio', 'Gio']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value.toFixed(value < 10 ? 1 : 0).replace('.', ',')} ${units[unit]}`
}
