export const TONES = {
  neutral: 'border-slate-700 bg-slate-800 text-slate-200',
  green: 'border-emerald-700 bg-emerald-950 text-emerald-300',
  amber: 'border-amber-700 bg-amber-950 text-amber-300',
  blue: 'border-sky-700 bg-sky-950 text-sky-300',
  red: 'border-rose-700 bg-rose-950 text-rose-300',
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
