import type { ReactNode } from 'react'
import { TONES, type Tone } from '../lib/status'

export function Panel({
  title,
  actions,
  children,
}: {
  title: string
  actions?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="rounded-lg border border-line bg-surface shadow-sm">
      <header className="flex items-center justify-between border-b border-line px-4 py-3">
        <h2 className="text-sm font-semibold text-ink">{title}</h2>
        {actions}
      </header>
      <div className="p-4">{children}</div>
    </section>
  )
}

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: Tone }) {
  return (
    <span className={`rounded-full border px-2 py-0.5 text-xs font-medium ${TONES[tone]}`}>
      {children}
    </span>
  )
}

export function ErrorNote({ error }: { error: unknown }) {
  if (!error) {
    return null
  }
  const message = error instanceof Error ? error.message : String(error)
  return (
    <p className="rounded-md border border-mfp-red bg-mfp-red/10 px-3 py-2 text-sm text-mfp-red">
      {message}
    </p>
  )
}
