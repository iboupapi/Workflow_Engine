import { ShieldCheck, UserRound } from 'lucide-react'
import { useDevTokens } from '../api/hooks'
import type { DevToken } from '../api/types'

/**
 * Barre de selection du jeton de developpement. La console n'invente rien : elle demande
 * au backend (profil dev) un jeton par role de test et le transmet tel quel.
 */
export function RoleBar({
  active,
  onSelect,
}: {
  active: DevToken | null
  onSelect: (token: DevToken | null) => void
}) {
  const { data, isLoading, error, refetch } = useDevTokens()

  return (
    <div className="flex flex-wrap items-center gap-2 border-b border-slate-800 bg-slate-900/40 px-6 py-3">
      <span className="flex items-center gap-2 text-xs font-semibold tracking-wide text-slate-400 uppercase">
        <ShieldCheck className="h-4 w-4" /> Jeton de test
      </span>

      {isLoading && <span className="text-sm text-slate-400">Chargement…</span>}

      {error != null && (
        <>
          <span className="text-sm text-amber-400">
            Endpoint de jetons indisponible (backend en profil « dev » requis).
          </span>
          <button
            type="button"
            onClick={() => {
              void refetch()
            }}
            className="rounded-md border border-slate-700 px-2 py-1 text-xs text-slate-200 hover:bg-slate-800"
          >
            Réessayer
          </button>
        </>
      )}

      {data?.map((token) => {
        const selected = active?.role === token.role
        return (
          <button
            key={token.role}
            type="button"
            onClick={() => onSelect(token)}
            className={`flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium transition ${
              selected
                ? 'border-sky-500 bg-sky-950 text-sky-200'
                : 'border-slate-700 text-slate-300 hover:bg-slate-800'
            }`}
          >
            <UserRound className="h-3.5 w-3.5" />
            {token.role}
          </button>
        )
      })}

      {active && (
        <button
          type="button"
          onClick={() => onSelect(null)}
          className="ml-auto rounded-md border border-slate-700 px-2 py-1 text-xs text-slate-300 hover:bg-slate-800"
        >
          Se déconnecter
        </button>
      )}
    </div>
  )
}
