export const TONES = {
  neutral: 'border-line-strong bg-surface-muted text-ink',
  green: 'border-mfp-green bg-mfp-green/10 text-mfp-green',
  amber: 'border-mfp-yellow bg-mfp-yellow/25 text-ink',
  blue: 'border-mfp-blue bg-mfp-blue/10 text-mfp-blue',
  red: 'border-mfp-red bg-mfp-red/10 text-mfp-red',
} as const

export type Tone = keyof typeof TONES

export function statusTone(status: string): Tone {
  switch (status) {
    case 'COMPLETED':
      return 'green'
    case 'ACTIVE':
      return 'blue'
    case 'REJECTED_FINAL':
    case 'CANCELLED':
      return 'red'
    default:
      return 'neutral'
  }
}

/** Statut du referentiel d'un type de demande : ce que voit l'administration. */
export function requestTypeTone(status: string): Tone {
  switch (status) {
    case 'PUBLISHED':
      return 'green'
    case 'DRAFT':
      return 'amber'
    case 'RETIRED':
      return 'red'
    default:
      return 'neutral'
  }
}

/** Libelle francais d'un statut de referentiel (l'enum du moteur reste en anglais). */
export function requestTypeLabel(status: string): string {
  switch (status) {
    case 'PUBLISHED':
      return 'publié'
    case 'DRAFT':
      return 'brouillon'
    case 'RETIRED':
      return 'retiré'
    default:
      return status
  }
}

