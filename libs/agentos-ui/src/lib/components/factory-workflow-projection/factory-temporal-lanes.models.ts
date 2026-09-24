import {
  WorkflowProjectionStatus,
  WorkflowProjectionV2,
  WorkflowTimingDto,
} from '../../services/factory-workflow-projection.model'

export const BLUEPRINT_LANES = ['human', 'agent', 'code'] as const
export type BlueprintLane = (typeof BLUEPRINT_LANES)[number]
export type BlueprintMode = 'causal' | 'temporal'

export interface BlueprintNode {
  id: string
  name: string
  actorKind: BlueprintLane
  actorName?: string
  status: WorkflowProjectionStatus
  dependencies: string[]
  successors: string[]
  sourceIndex: number
  column: number
  row: number
  x: number
  width: number
}
export interface BlueprintEdge {
  from: string
  to: string
  x1: number
  y1: number
  x2: number
  y2: number
}
export interface BlueprintLayout {
  mode: BlueprintMode
  nodes: BlueprintNode[]
  edges: BlueprintEdge[]
  columnCount: number
  axis?: { start: string; end: string; markerLabel: 'Present' | 'Observed end' }
}

const parse = (value?: string | null): number | undefined => {
  if (!value) return undefined
  const result = Date.parse(value)
  return Number.isFinite(result) ? result : undefined
}

/** Pure, cycle-defensive DAG layout. Source order is the stable sibling tie-breaker. */
export function buildBlueprintLayout(projection: WorkflowProjectionV2, timing?: WorkflowTimingDto): BlueprintLayout {
  const steps = projection.steps
  const ids = new Set(steps.map((step) => step.id))
  const depth = new Map<string, number>()
  const visiting = new Set<string>()
  const getDepth = (id: string): number => {
    if (depth.has(id)) return depth.get(id)!
    if (visiting.has(id)) return 0
    visiting.add(id)
    const step = steps.find((candidate) => candidate.id === id)
    const value = step
      ? step.dependsOn
          .filter((dependency) => ids.has(dependency))
          .reduce((max, dependency) => Math.max(max, getDepth(dependency) + 1), 0)
      : 0
    visiting.delete(id)
    depth.set(id, value)
    return value
  }
  steps.forEach((step) => getDepth(step.id))

  const timingById = new Map((timing?.steps ?? []).map((step) => [step.stepId, step]))
  const observedEnd = parse(timing?.observedAt ?? timing?.lastActivityAt ?? timing?.lastCompletedAt)
  const starts = steps.map((step) => parse(timingById.get(step.id)?.firstStartedAt))
  const ends = steps.map(
    (step) =>
      parse(timingById.get(step.id)?.lastCompletedAt ?? timingById.get(step.id)?.firstCompletedAt) ??
      (timingById.get(step.id)?.currentStatusSince ? observedEnd : undefined)
  )
  const temporalStart = parse(timing?.startedAt ?? timing?.firstStartedAt ?? timing?.createdAt)
  const temporal =
    !!timing?.complete &&
    temporalStart !== undefined &&
    observedEnd !== undefined &&
    observedEnd > temporalStart &&
    starts.every((start) => start !== undefined) &&
    ends.every((end, index) => end !== undefined && end >= starts[index]!)
  const maxDepth = Math.max(0, ...steps.map((step) => depth.get(step.id) ?? 0))
  const span = temporal ? observedEnd! - temporalStart! : 1
  const successors = new Map<string, string[]>()
  steps.forEach((step) =>
    step.dependsOn
      .filter((id) => ids.has(id))
      .forEach((id) => successors.set(id, [...(successors.get(id) ?? []), step.id]))
  )
  const nodes: BlueprintNode[] = steps.map((step, sourceIndex) => {
    const column = depth.get(step.id) ?? 0
    const x = temporal
      ? ((starts[sourceIndex]! - temporalStart!) / span) * 100
      : ((column + 0.5) / (maxDepth + 1)) * 100
    const width = temporal
      ? Math.max(1.5, ((ends[sourceIndex]! - starts[sourceIndex]!) / span) * 100)
      : Math.min(16, 72 / Math.max(1, maxDepth + 1))
    return {
      id: step.id,
      name: step.name,
      actorKind: step.responsibility.kind,
      actorName: step.responsibility.name,
      status: step.status,
      dependencies: step.dependsOn,
      successors: successors.get(step.id) ?? [],
      sourceIndex,
      column,
      row: BLUEPRINT_LANES.indexOf(step.responsibility.kind),
      x,
      width,
    }
  })
  const byId = new Map(nodes.map((node) => [node.id, node]))
  const edges = nodes.flatMap((to) =>
    to.dependencies
      .map((id) => byId.get(id))
      .filter((from): from is BlueprintNode => !!from)
      .map((from) => ({
        from: from.id,
        to: to.id,
        x1: Math.min(99, from.x + from.width / 2),
        y1: 16.67 + from.row * 33.33,
        x2: Math.max(1, to.x - to.width / 2),
        y2: 16.67 + to.row * 33.33,
      }))
  )
  return {
    mode: temporal ? 'temporal' : 'causal',
    nodes,
    edges,
    columnCount: maxDepth + 1,
    axis: temporal
      ? {
          start: new Date(temporalStart!).toISOString(),
          end: new Date(observedEnd!).toISOString(),
          markerLabel: projection.status === 'running' ? 'Present' : 'Observed end',
        }
      : undefined,
  }
}
