import { FileText, GitBranch, Inbox, LayoutDashboard, PlusCircle } from 'lucide-react'
import { useCallback, useState, type ComponentType } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { setToken } from './api/client'
import { useAdminRequestTypes } from './api/hooks'
import type { DevToken } from './api/types'
import { AdminEservicesView } from './components/admin/AdminEservicesView'
import { CircuitView } from './components/CircuitView'
import { DossiersView } from './components/DossiersView'
import { NewRequestView } from './components/NewRequestView'
import { RequestsView } from './components/RequestsView'
import { RoleBar } from './components/RoleBar'

const CIRCUIT = 'E_SERVICE_DEMANDE'

/** Roles qui instruisent les dossiers (l'administration, elle, se prouve autrement). */
const INSTRUCTION_ROLES = new Set([
  'AGENT_BUREAU',
  'AGENT_TRAITEMENT',
  'CHEF_DIVISION',
  'DLEC',
  'SG',
])

type Space = 'citoyen' | 'instruction' | 'administration'

type Tab = 'demandes' | 'dossiers' | 'circuit' | 'nouvelle' | 'eservices'

const TABS: Record<Tab, { label: string; Icon: ComponentType<{ className?: string }> }> = {
  demandes: { label: 'Mes demandes', Icon: FileText },
  dossiers: { label: 'Dossiers', Icon: Inbox },
  circuit: { label: 'Circuit', Icon: GitBranch },
  nouvelle: { label: 'Nouvelle demande (directe)', Icon: PlusCircle },
  eservices: { label: 'E-Services', Icon: LayoutDashboard },
}

/** Un espace = un metier : chacun ne montre que les onglets qui le concernent. */
const TABS_BY_SPACE: Record<Space, Tab[]> = {
  citoyen: ['demandes', 'dossiers'],
  instruction: ['dossiers', 'circuit', 'nouvelle'],
  administration: ['eservices'],
}

const SPACES: Record<Space, { label: string; hint: string }> = {
  citoyen: {
    label: 'Espace citoyen',
    hint: 'Déposer une demande à partir du catalogue des E-Services publiés.',
  },
  instruction: {
    label: 'Espace instruction',
    hint: 'Traiter les dossiers reçus dans les bannettes des rôles.',
  },
  administration: {
    label: 'Espace administration',
    hint: 'Créer, publier et versionner les E-Services proposés aux usagers.',
  },
}

export default function App() {
  const queryClient = useQueryClient()
  const [selectedToken, setSelectedToken] = useState<DevToken | null>(null)
  const [tab, setTab] = useState<Tab>('demandes')
  const [openInstance, setOpenInstance] = useState<string | null>(null)

  /*
   * Le jeton est pose AVANT le rendu qui declenche les requetes : un effet
   * s'executerait apres elles et la premiere salve partirait sans Authorization (401).
   */
  const selectToken = useCallback(
    (token: DevToken | null) => {
      setToken(token?.token ?? null)
      setSelectedToken(token)
      setOpenInstance(null)
      void queryClient.clear()
    },
    [queryClient],
  )

  /*
   * Qui administre n'est pas deduit d'un nom de role : c'est le serveur qui l'accorde au
   * SUJET du jeton (auth.admin.subjects), et ce meme endpoint sert de sonde et de donnees
   * a l'espace d'administration (meme cle de cache, une seule requete).
   */
  const adminAccess = useAdminRequestTypes(selectedToken !== null)

  const role = selectedToken?.role ?? null
  const space: Space = adminAccess.isSuccess
    ? 'administration'
    : role !== null && INSTRUCTION_ROLES.has(role)
      ? 'instruction'
      : 'citoyen'

  const tabs = TABS_BY_SPACE[space]
  // Un changement de jeton peut laisser l'onglet courant hors de l'espace : on retombe
  // alors sur le premier onglet de l'espace, sans effet de synchronisation.
  const activeTab: Tab = tabs.includes(tab) ? tab : tabs[0]

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
        <div className="flex items-center gap-3">
          <div className="text-right">
            <p className="text-xs font-semibold text-ink">{SPACES[space].label}</p>
            <p className="max-w-sm text-[11px] text-ink-muted">{SPACES[space].hint}</p>
          </div>
          <span className="rounded-full border border-line-strong px-3 py-1 text-xs text-ink">
            {selectedToken ? `${selectedToken.role} — ${selectedToken.subject}` : 'aucun jeton'}
          </span>
        </div>
      </header>

      <RoleBar active={selectedToken} onSelect={selectToken} />

      <nav className="flex gap-1 border-b border-line bg-surface px-6">
        {tabs.map((id) => {
          const { label, Icon } = TABS[id]
          return (
            <button
              key={id}
              type="button"
              onClick={() => setTab(id)}
              className={`-mb-px flex items-center gap-2 border-b-2 px-3 py-3 text-sm ${
                activeTab === id
                  ? 'border-mfp-green font-medium text-mfp-green'
                  : 'border-transparent text-ink-muted hover:text-ink'
              }`}
            >
              <Icon className="h-4 w-4" />
              {label}
            </button>
          )
        })}
      </nav>

      <main className="flex-1 p-6">
        {activeTab === 'demandes' && (
          <RequestsView
            authenticated={selectedToken !== null}
            onOpenInstance={(instanceId) => {
              setOpenInstance(instanceId)
              setTab('dossiers')
            }}
          />
        )}
        {activeTab === 'dossiers' && (
          <DossiersView
            authenticated={selectedToken !== null}
            selectedId={openInstance}
            views={space === 'citoyen' ? ['MINE'] : ['MINE', 'INBOX', 'ALL']}
          />
        )}
        {activeTab === 'circuit' && (
          <CircuitView definitionKey={CIRCUIT} authenticated={selectedToken !== null} />
        )}
        {activeTab === 'nouvelle' && (
          <NewRequestView definitionKey={CIRCUIT} authenticated={selectedToken !== null} />
        )}
        {activeTab === 'eservices' && <AdminEservicesView />}
      </main>
    </div>
  )
}
