import { WorkflowProjectionV2 } from '../../services/factory-workflow-projection.model'
import { buildBlueprintLayout } from './factory-temporal-lanes.models'

const projection = (steps: WorkflowProjectionV2['steps']): WorkflowProjectionV2 => ({
  schemaVersion: '2',
  workflowId: 'w',
  workflowType: 'test',
  title: 'Test',
  status: 'running',
  steps,
})
const step = (
  id: string,
  dependsOn: string[] = [],
  kind: 'human' | 'agent' | 'code' = 'agent'
): WorkflowProjectionV2['steps'][number] => ({
  id,
  name: id,
  status: 'pending',
  description: '',
  dependsOn,
  responsibility: { kind, name: `${kind} actor` },
})

describe('buildBlueprintLayout', () => {
  it('assigns deterministic dependency depths and preserves source order for parallel siblings', () => {
    const layout = buildBlueprintLayout(projection([step('root'), step('second', ['root']), step('first', ['root'])]))
    expect(layout.nodes.map(({ id, column }) => [id, column])).toEqual([
      ['root', 0],
      ['second', 1],
      ['first', 1],
    ])
  })

  it('creates cross-lane dependency edges', () => {
    const layout = buildBlueprintLayout(
      projection([step('human', [], 'human'), step('agent', ['human'], 'agent'), step('code', ['agent'], 'code')])
    )
    expect(layout.edges.map(({ from, to }) => `${from}->${to}`)).toEqual(['human->agent', 'agent->code'])
    expect(layout.edges[0].y1).not.toBe(layout.edges[0].y2)
  })

  it('falls back defensively when a cycle reaches the renderer', () => {
    const layout = buildBlueprintLayout(projection([step('a', ['b']), step('b', ['a'])]))
    expect(layout.nodes).toHaveLength(2)
    expect(layout.columnCount).toBeGreaterThan(0)
  })

  it('uses causal mode for incomplete timing rather than fabricating a scale', () => {
    const layout = buildBlueprintLayout(projection([step('a')]), {
      complete: false,
      incompleteReasons: ['legacy'],
      totalElapsedMs: 0,
      activeMs: 0,
      waitingHumanMs: 0,
      blockedMs: 0,
      transitionCount: 0,
      currentStatus: null,
      currentStatusSince: null,
      steps: [],
    })
    expect(layout.mode).toBe('causal')
    expect(layout.axis).toBeUndefined()
  })
})
