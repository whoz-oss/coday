import { TestBed } from '@angular/core/testing'
import { of, Subject } from 'rxjs'
import { FactoryApiService } from './factory-api.service'
import {
  WorkflowStepEditorState,
  explicitContract,
  legacyAdoption,
  validateDraft,
  validateStructure,
} from './workflow-step-editor.state'
import { layoutDefinition } from '../features/workflows/workflow-definition-diagram.component'

describe('workflow block composition', () => {
  function setup() {
    const response = new Subject<{ workflowType: string; version: string; definitionHash: string }>()
    const api = {
      getNamespaces: jest.fn().mockReturnValue(of([{ id: 'ns', name: 'Team' }])),
      getAgents: jest.fn().mockReturnValue(of([{ name: 'Dev' }, { name: 'Architect' }])),
      createWorkflowDefinition: jest.fn().mockReturnValue(response),
    }
    TestBed.configureTestingModule({
      providers: [WorkflowStepEditorState, { provide: FactoryApiService, useValue: api }],
    })
    const state = TestBed.inject(WorkflowStepEditorState)
    state.startNew()
    return { state, api, response }
  }

  it('edits and serializes named routes mode targets and join without losing them on selection or rename', () => {
    const { state, api } = setup()
    state.metadata('workflowType', 'routing')
    state.metadata('title', 'Routing')
    state.discoverAgents('ns')
    for (let i = 0; i < 4; i++) {
      state.addBlock()
      state.setAgent(i === 0 ? 'Architect' : 'Dev')
      state.update('mission', 'Scoped work')
    }
    state.select('step-1')
    state.setRoutingMode('multiple')
    state.updateRoute(0, 'target', 'step-2')
    state.updateRoute(1, 'target', 'step-3')
    state.select('step-2')
    state.connect('step-1', true)
    state.select('step-3')
    state.connect('step-1', true)
    state.select('step-4')
    state.connect('step-2', true)
    state.connect('step-3', true)
    state.updateBlock({ join: 'step-1' })
    expect(state.validation()).toBeNull()
    state.select('step-1')
    state.updateBlock({ id: 'architect' })
    expect(state.definition()?.steps?.[3].join).toBe('architect')
    state.select('step-2')
    state.updateBlock({ id: 'frontend' })
    expect(state.definition()?.steps?.[0].decision?.routes[0].target).toBe('frontend')
    state.select('architect')
    state.setRoutingMode('single')
    expect(state.selected()?.decision?.mode).toBe('single')
    expect(layoutDefinition(state.preview()?.steps ?? []).edges.find((edge) => edge.to === 'frontend')?.label).toBe(
      'route-1 (single)'
    )
    state.save()
    const payload = api.createWorkflowDefinition.mock.calls[0][0]
    expect(JSON.parse(JSON.stringify(payload)).steps[0].decision).toEqual({
      mode: 'single',
      routes: [
        { name: 'route-1', target: 'frontend' },
        { name: 'route-2', target: 'step-3' },
      ],
    })
    expect(payload.steps[3].join).toBe('architect')
  })

  it('rejects incomplete duplicate and unsupported routing topologies before save', () => {
    const step = (id: string, dependsOn: string[] = []) => ({
      id,
      name: id,
      dependsOn,
      responsibility: { kind: 'agent', name: 'Dev' },
    })
    const decision = {
      mode: 'multiple' as const,
      routes: [
        { name: 'frontend', target: 'f' },
        { name: 'backend', target: 'b' },
      ],
    }
    const base = {
      workflowType: 'x',
      title: 'X',
      steps: [{ ...step('a'), decision }, step('f', ['a']), step('b', ['a']), { ...step('j', ['f', 'b']), join: 'a' }],
    }
    expect(validateStructure(base)).toBeNull()
    expect(validateStructure({ ...base, steps: base.steps.filter((step) => step.id !== 'j') })).toContain(
      'explicit join'
    )
    expect(validateStructure({ ...base, steps: [...base.steps, step('escape', ['f'])] })).toContain('only and directly')
    expect(
      validateStructure({
        ...base,
        steps: base.steps.map((step) =>
          step.id === 'a'
            ? { ...step, decision: { ...decision, routes: [decision.routes[0], decision.routes[0]] } }
            : step
        ),
      })
    ).toContain('unique')
  })

  it('invalidates server adoption metadata when the loaded responsibility changes', () => {
    const { state } = setup()
    state.definition.set({
      steps: [
        {
          id: 'plan',
          name: 'Plan',
          responsibility: { kind: 'agent', name: 'Dev' },
          legacyContract: { adoptable: true, summary: 'Server default', entry: [], mission: 'Plan', exit: [] },
        },
      ],
    })
    state.select('plan')
    expect(legacyAdoption(state.selected()!)).not.toBeNull()
    state.updateBlock({ responsibility: { kind: 'agent', name: 'Other' } })
    expect(legacyAdoption(state.selected()!)).toBeNull()
  })

  it('strips backend metadata on save', () => {
    const { state, api } = setup()
    state.metadata('workflowType', 'legacy')
    state.metadata('title', 'Legacy')
    state.definition.set({
      ...state.definition(),
      steps: [
        {
          id: 'plan',
          name: 'Plan',
          responsibility: { kind: 'agent', name: 'Dev' },
          dependsOn: [],
          legacyContract: {
            adoptable: true,
            summary: 'Server default',
            entry: ['Read'],
            mission: 'Plan',
            exit: ['Report'],
          },
        },
      ],
    })
    state.select('plan')
    state.adopt()
    state.save()
    expect(api.createWorkflowDefinition.mock.calls[0][0].steps[0].legacyContract).toBeUndefined()
  })

  it('starts pristine, tracks version-only edits, and clears dirty on discard', () => {
    const { state } = setup()
    expect(state.creating()).toBe(true)
    expect(state.dirty()).toBe(false)
    state.targetVersion.set('2.0.0')
    expect(state.dirty()).toBe(true)
    state.discard()
    expect(state.targetVersion()).toBe('1.0.0')
    expect(state.dirty()).toBe(false)
  })

  it('composes, connects, selects, renames and removes blocks without dangling edges', () => {
    const { state } = setup()
    state.loadDiscovery()
    state.discoverAgents('ns')
    state.metadata('workflowType', 'analysis')
    state.metadata('title', 'Analysis')
    state.addBlock()
    state.setAgent('Architect')
    state.update('mission', 'Frame request')
    state.addBlock()
    state.setAgent('Dev')
    state.update('mission', 'Analyze')
    state.connect('step-1', true)
    expect(state.selected()?.dependsOn).toEqual(['step-1'])
    expect(layoutDefinition(state.preview()?.steps ?? []).edges).toHaveLength(1)
    state.select('step-1')
    expect(state.draft()?.mission).toBe('Frame request')
    state.updateBlock({ id: 'frame' })
    expect(state.definition()?.steps?.[1]?.dependsOn).toEqual(['frame'])
    expect(state.draft()?.mission).toBe('Frame request')
    state.connect('step-2', true)
    expect(state.validation()).toContain('cycle')
    state.connect('step-2', false)
    expect(state.validation()).toBeNull()
    state.removeBlock()
    expect(state.selectedId()).toBe('step-2')
    expect(state.selected()?.dependsOn).toEqual([])
    expect(state.drafts()['frame']).toBeUndefined()
    state.discard()
    expect(state.definition()?.steps).toEqual([])
    expect(state.dirty()).toBe(false)
  })

  it('saves a human-only workflow with the exact role payload and no discovery or forced contract', () => {
    const { state, api } = setup()
    state.metadata('workflowType', 'approval')
    state.metadata('title', 'Approval')
    state.addBlock('human')
    expect(state.validation()).toContain('human role')
    state.updateBlock({ responsibility: { kind: 'human', name: 'Product owner' } })
    expect(state.validation()).toBeNull()
    expect(state.draft()).toBeNull()
    state.save()
    expect(api.createWorkflowDefinition).toHaveBeenCalledWith({
      schemaVersion: '1',
      workflowType: 'approval',
      title: 'Approval',
      version: '1.0.0',
      steps: [
        { id: 'step-1', name: 'Human step 1', responsibility: { kind: 'human', name: 'Product owner' }, dependsOn: [] },
      ],
    })
    expect(api.getNamespaces).not.toHaveBeenCalled()
    expect(api.getAgents).not.toHaveBeenCalled()
  })

  it('supports mixed steps with multiple optional dependencies, rejects self/unknown links and blocks cyclic saves', () => {
    const { state, api } = setup()
    state.metadata('workflowType', 'mixed')
    state.metadata('title', 'Mixed')
    state.discoverAgents('ns')
    state.addBlock()
    state.setAgent('Dev')
    state.update('mission', 'Analyze')
    state.addBlock('human')
    state.updateBlock({ responsibility: { kind: 'human', name: 'Product owner' } })
    state.addHumanContract()
    state.update('mission', 'Approve scope')
    state.addBlock('human')
    state.updateBlock({ responsibility: { kind: 'human', name: 'Reviewer' } })
    state.connect('step-3', true)
    state.connect('missing', true)
    expect(state.selected()?.dependsOn).toEqual([])
    state.connect('step-1', true)
    state.connect('step-2', true)
    state.connect('step-1', true)
    expect(state.selected()?.dependsOn).toEqual(['step-1', 'step-2'])
    expect(layoutDefinition(state.preview()?.steps ?? []).edges).toHaveLength(2)
    state.select('step-1')
    state.connect('step-3', true)
    expect(state.validation()).toContain('cycle')
    state.save()
    expect(api.createWorkflowDefinition).not.toHaveBeenCalled()
    state.connect('step-3', false)
    state.select('step-2')
    state.updateBlock({ id: 'approval' })
    expect(state.definition()?.steps?.[2].dependsOn).toEqual(['step-1', 'approval'])
    expect(state.draft()?.mission).toBe('Approve scope')
    state.removeBlock()
    expect(state.definition()?.steps?.[1].dependsOn).toEqual(['step-1'])
    state.select('step-3')
    state.connect('step-1', false)
    expect(state.selected()?.dependsOn).toEqual([])
    expect(state.validation()).toBeNull()
    state.save()
    expect(api.createWorkflowDefinition).toHaveBeenCalled()
  })

  it('requires agent and prompt, saves canonical contract arrays and keeps input on server failure', () => {
    const { state, api, response } = setup()
    state.metadata('workflowType', 'analysis')
    state.metadata('title', 'Analysis')
    state.addBlock()
    expect(state.validation()).toContain('select an agent')
    state.discoverAgents('ns')
    state.setAgent('fabricated')
    expect(state.selected()?.responsibility?.name).toBe('')
    state.setAgent('Dev')
    expect(state.validation()).toContain('Prompt')
    state.update('mission', 'Analyze only')
    state.update('entry', ['Read inputs'])
    state.update('exit', ['Deliver findings'])
    state.save()
    expect(api.createWorkflowDefinition).toHaveBeenCalledWith({
      schemaVersion: '1',
      workflowType: 'analysis',
      title: 'Analysis',
      version: '1.0.0',
      steps: [
        {
          id: 'step-1',
          name: 'Agent step 1',
          responsibility: { kind: 'agent', name: 'Dev' },
          dependsOn: [],
          entry: ['Read inputs'],
          mission: 'Analyze only',
          exit: ['Deliver findings'],
        },
      ],
    })
    expect(state.saving()).toBe(true)
    response.error({ message: 'Conflict', details: { path: 'version' } })
    expect(state.error()).toBe('Conflict (version)')
    expect(state.dirty()).toBe(true)
    expect(state.draft()?.mission).toBe('Analyze only')
    expect(state.editing()).toBe(true)
    expect(state.creating()).toBe(true)
  })

  it('resolves a successful new save into a pristine read-only baseline', () => {
    const { state, response } = setup()
    state.metadata('workflowType', 'analysis')
    state.metadata('title', 'Analysis')
    state.discoverAgents('ns')
    state.addBlock()
    state.setAgent('Dev')
    state.update('mission', 'Analyze')
    state.save()
    response.next({ workflowType: 'analysis', version: '1.0.0', definitionHash: 'hash' })
    response.complete()
    expect(state.editing()).toBe(false)
    expect(state.creating()).toBe(false)
    expect(state.dirty()).toBe(false)
    expect(state.definition()?.version).toBe('1.0.0')
    state.addBlock()
    state.updateBlock({ name: 'Forbidden' })
    state.update('mission', 'Forbidden')
    state.removeBlock()
    expect(state.dirty()).toBe(false)
    expect(state.definition()?.steps).toHaveLength(1)
    state.edit()
    state.update('mission', 'Changed')
    state.cancel()
    expect(state.draft()?.mission).toBe('Analyze')
    expect(state.editing()).toBe(false)
    expect(state.dirty()).toBe(false)
  })

  it('checks duplicates, missing references and preserves selection on an invalid rename', () => {
    const { state } = setup()
    state.addBlock()
    state.addBlock()
    state.updateBlock({ id: 'step-1' })
    expect(state.selectedId()).toBe('step-2')
    expect(state.error()).toContain('unique')
    const base = {
      workflowType: 'x',
      title: 'X',
      steps: [{ id: 'a', name: 'A', responsibility: { kind: 'agent', name: 'Dev' }, dependsOn: ['missing'] }],
    }
    expect(validateStructure(base)).toContain('missing predecessor')
    expect(validateStructure({ ...base, steps: [base.steps[0], base.steps[0]] })).toContain('Duplicate')
  })

  it('ignores late agent discovery from a previously selected namespace', () => {
    const { state, api } = setup()
    const old = new Subject<{ name: string }[]>()
    api.getAgents.mockReturnValueOnce(old)
    state.discoverAgents('old')
    state.discoverAgents('ns')
    old.next([{ name: 'Wrong namespace' }])
    expect(state.agents()).toEqual(['Architect', 'Dev'])
  })
})

describe('step instruction editing', () => {
  it('retains ordered multiline action values without flattening them into lines', () => {
    const step = { id: 'x', name: 'X', entry: ['First\ncontinued', 'Second'], mission: 'Do work', exit: [] }
    const draft = explicitContract(step)
    expect(draft?.entry).toEqual(['First\ncontinued', 'Second'])
    draft?.entry.push('Third')
    expect(step.entry).toHaveLength(2)
  })

  it('copies downstream legacy handoff obligations on explicit adoption', () => {
    const draft = legacyAdoption({
      id: 'work',
      name: 'Work',
      responsibility: { kind: 'agent', name: 'Dev' },
      dependsOn: ['plan'],
      legacyContract: {
        adoptable: true,
        summary: 'Backend contract',
        mission: 'Work',
        entry: ['Consume structured dependency inputs'],
        exit: ['Preserve run briefs unchanged; never rewrite or merge independent framings'],
      },
    })
    expect(draft?.entry.join('\n')).toContain('structured dependency inputs')
    expect(draft?.exit.join('\n')).toContain('unchanged')
    expect(draft?.exit.join('\n')).toContain('never rewrite or merge independent framings')
  })

  it('does not invent legacy obligations when backend metadata is absent', () => {
    expect(legacyAdoption({ id: 'x', name: 'Work', responsibility: { kind: 'agent', name: 'Dev' } })).toBeNull()
  })

  it('does not treat partial contracts as explicit or auto-adopt non-agent execution', () => {
    expect(explicitContract({ id: 'x', name: 'X', mission: 'Partial' })).toBeNull()
    expect(legacyAdoption({ id: 'x', name: 'X', responsibility: { kind: 'human' } })).toBeNull()
  })

  it('matches server prose bounds, allows empty action arrays and rejects blank actions', () => {
    expect(validateDraft({ entry: [], mission: 'Work', exit: [] })).toBeNull()
    expect(validateDraft({ entry: [' '], mission: 'Work', exit: [] })).toContain('Pre-actions')
    expect(validateDraft({ entry: [], mission: 'x'.repeat(16001), exit: [] })).toContain('Prompt')
    expect(validateDraft({ entry: [], mission: 'Work', exit: ['x'.repeat(4001)] })).toContain('Post-actions')
    expect(validateDraft({ entry: Array(51).fill('x'), mission: 'Work', exit: [] })).toContain('Pre-actions')
  })
})

describe('workflow definition diagram', () => {
  it('represents branching and merging dependencies even when steps are not topologically ordered', () => {
    const steps = [
      { id: 'end', name: 'End', dependsOn: ['left', 'right'] },
      { id: 'left', name: 'Left', dependsOn: ['start'] },
      { id: 'start', name: 'Start', dependsOn: [] },
      { id: 'right', name: 'Right', dependsOn: ['start'] },
    ]
    const layout = layoutDefinition(steps)
    expect(layout.edges.map((edge) => `${edge.from}:${edge.to}`)).toEqual([
      'left:end',
      'right:end',
      'start:left',
      'start:right',
    ])
    const nodes = new Map(layout.nodes.map((node) => [node.step.id, node]))
    expect(nodes.get('end')?.x).toBeGreaterThan(nodes.get('left')?.x ?? 0)
    expect(nodes.get('left')?.x).toBe(nodes.get('right')?.x)
    expect(nodes.get('left')?.y).not.toBe(nodes.get('right')?.y)
    expect(steps[0].dependsOn).toEqual(['left', 'right'])
  })
})
