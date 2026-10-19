import { GitBranch, Inbox, PlusCircle } from 'lucide-react'
import { useEffect, useState, type ComponentType } from 'react'
import { setToken } from './api/client'
import type { DevToken } from './api/types'
import { CircuitView } from './components/CircuitView'
import { DossiersView } from './components/DossiersView'
import { NewRequestView } from './components/NewRequestView'
import { RoleBar } from './components/RoleBar'

const CIRCUIT = 'E_SERVICE_DEMANDE'

type Tab = 'dossiers' | 'circuit' | 'nouvelle'

const TABS: { id: Tab; label: string; Icon: ComponentType<{ className?: string }> }[] = [
  { id: 'dossiers', label: 'Dossiers', Icon: Inbox },
  { id: 'circuit', label: 'Circuit', Icon: GitBranch },
  { id: 'nouvelle', label: 'Nouvelle demande', Icon: PlusCircle },
]

export default function App() {
  const [selectedToken, setSelectedToken] = useState<DevToken | null>(null)
  const [tab, setTab] = useState<Tab>('dossiers')

  useEffect(() => {
    setToken(selectedToken?.token ?? null)
  }, [selectedToken])

  return (
    <div className="flex min-h-screen flex-col border-t-4 border-mfp-green bg-canvas text-ink">
      <header className="flex items-center justify-between border-b border-line bg-surface px-6 py-4">
        {/* Emplacement reserve au logo horizontal (charte : zone de protection de 5 %). */}
        <div>
          <h1 className="text-lg font-semibold text-ink">Moteur de workflow — Console</h1>
          <p className="text-xs text-ink-muted">
            Circuit {CIRCUIT} · backend http://localhost:8080 (profil dev)
          </p>
        </div>
        <span className="rounded-full border border-line-strong px-3 py-1 text-xs text-ink">
          {selectedToken ? `${selectedToken.role} — ${selectedToken.subject}` : 'aucun jeton'}
        </span>
      </header>

      <RoleBar active={selectedToken} onSelect={setSelectedToken} />

      <nav className="flex gap-1 border-b border-line bg-surface px-6">
        {TABS.map(({ id, label, Icon }) => (
          <button
            key={id}
            type="button"
            onClick={() => setTab(id)}
            className={`-mb-px flex items-center gap-2 border-b-2 px-3 py-3 text-sm ${
              tab === id
                ? 'border-mfp-green font-medium text-mfp-green'
                : 'border-transparent text-ink-muted hover:text-ink'
            }`}
          >
            <Icon className="h-4 w-4" />
            {label}
          </button>
        ))}
      </nav>

      <main className="flex-1 p-6">
        {tab === 'dossiers' && <DossiersView authenticated={selectedToken !== null} />}
        {tab === 'circuit' && (
          <CircuitView definitionKey={CIRCUIT} authenticated={selectedToken !== null} />
        )}
        {tab === 'nouvelle' && (
          <NewRequestView definitionKey={CIRCUIT} authenticated={selectedToken !== null} />
        )}
      </main>
    </div>
  )
}
