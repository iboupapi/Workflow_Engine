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
