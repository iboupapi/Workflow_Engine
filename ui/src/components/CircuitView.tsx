import { Background, Controls, ReactFlow, type Edge, type Node } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useMemo, type ReactNode } from 'react'
import { useDefinition } from '../api/hooks'
import type { DefinitionView, StepType, StepView } from '../api/types'
import { Panel, ErrorNote } from './ui'

type StepNodeData = { label: ReactNode }

const TYPE_TONE: Record<StepType, string> = {
  START: 'border-emerald-500 bg-emerald-950',
  TASK: 'border-slate-600 bg-slate-900',
  END: 'border-sky-500 bg-sky-950',
}

function StepLabel({ step }: { step: StepView }) {
  return (
    <div className={`min-w-40 rounded-md border px-3 py-2 text-left ${TYPE_TONE[step.type]}`}>
      <div className="font-mono text-xs text-slate-300">{step.key}</div>
      {step.name && <div className="text-xs text-slate-100">{step.name}</div>}
      {step.owners.length > 0 && (
        <div className="mt-1 text-[10px] tracking-wide text-slate-400 uppercase">
          {step.owners.join(', ')}
        </div>
      )}
    </div>
  )
}

function buildGraph(definition: DefinitionView): { nodes: Node<StepNodeData>[]; edges: Edge[] } {
  const outgoing = new Map<string, string[]>()
  for (const transition of definition.transitions) {
    const targets = outgoing.get(transition.source) ?? []
    targets.push(transition.target)
    outgoing.set(transition.source, targets)
  }

  const depth = new Map<string, number>()
  const starts = definition.steps.filter((step) => step.type === 'START').map((step) => step.key)
  const queue: string[] = [...starts]
  for (const key of starts) {
    depth.set(key, 0)
  }
  while (queue.length > 0) {
    const current = queue.shift() as string
    const currentDepth = depth.get(current) ?? 0
    for (const next of outgoing.get(current) ?? []) {
      if ((depth.get(next) ?? -1) < currentDepth + 1) {
        depth.set(next, currentDepth + 1)
        queue.push(next)
      }
    }
  }
  definition.steps.forEach((step, index) => {
    if (!depth.has(step.key)) {
      depth.set(step.key, Math.max(0, index))
    }
  })

  const columns = new Map<number, string[]>()
  for (const step of definition.steps) {
    const column = depth.get(step.key) ?? 0
    const keys = columns.get(column) ?? []
    keys.push(step.key)
    columns.set(column, keys)
  }

  const byKey = new Map(definition.steps.map((step) => [step.key, step]))
  const nodes: Node<StepNodeData>[] = []
  for (const [column, keys] of columns) {
    keys.forEach((key, index) => {
      const step = byKey.get(key)
      if (step) {
        nodes.push({
          id: key,
          position: { x: column * 300, y: index * 140 },
          data: { label: <StepLabel step={step} /> },
        })
      }
    })
  }

  const edges: Edge[] = definition.transitions.map((transition, index) => ({
    id: `transition-${index}`,
    source: transition.source,
    target: transition.target,
    label: transition.condition ? `${transition.action} ?` : transition.action,
    animated: transition.setsHandler,
    style:
      transition.target === 'RESPONSABLE_TRAITEMENT'
        ? { strokeDasharray: '6 4' }
        : undefined,
  }))

  return { nodes, edges }
}

/** Visualiseur lecture seule de la carte statique du circuit (etapes, roles, transitions). */
export function CircuitView({
  definitionKey,
  authenticated,
}: {
  definitionKey: string
  authenticated: boolean
}) {
  const { data, isLoading, error } = useDefinition(definitionKey, authenticated)
  const graph = useMemo(() => (data ? buildGraph(data) : null), [data])

  if (!authenticated) {
    return (
      <div className="rounded-lg border border-slate-800 bg-slate-900/40 p-6 text-sm text-slate-400">
        Choisissez un jeton de test ci-dessus pour consulter le circuit (la définition
        est protégée).
      </div>
    )
  }

  return (
    <Panel
      title={data ? `Circuit ${data.key} v${data.version}` : 'Circuit'}
      actions={
        <div className="flex gap-3 text-xs text-slate-400">
          <span>— trait plein : transition</span>
          <span>- - : rejet vers le responsable du traitement</span>
          <span className="text-emerald-400">animé : pose le responsable (setsHandler)</span>
        </div>
      }
    >
      {isLoading && <p className="text-sm text-slate-400">Chargement…</p>}
      <ErrorNote error={error} />
      {graph && data && (
        <div className="h-[620px] w-full overflow-hidden rounded-md border border-slate-800">
          <ReactFlow
            key={`${data.key}-${data.version}`}
            defaultNodes={graph.nodes}
            defaultEdges={graph.edges}
            fitView
            nodesDraggable={false}
            nodesConnectable={false}
            elementsSelectable={false}
            proOptions={{ hideAttribution: true }}
          >
            <Background />
            <Controls showInteractive={false} />
          </ReactFlow>
        </div>
      )}
    </Panel>
  )
}
