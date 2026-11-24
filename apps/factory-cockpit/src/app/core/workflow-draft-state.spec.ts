import {
  WORKFLOW_DRAFT_STATE_KEY,
  readWorkflowDraft,
  workflowDraftNavigationState,
} from './workflow-draft-state'

describe('workflow draft navigation state', () => {
  const definition = { workflowType: 'derived', version: '1.0.0', steps: [{ id: 'a', name: 'A' }] }

  it('round-trips a draft through the navigation state', () => {
    const state = workflowDraftNavigationState({ workflowId: 'run-1', namespaceId: 'ns-1', definition })
    expect(Object.keys(state)).toEqual([WORKFLOW_DRAFT_STATE_KEY])
    expect(readWorkflowDraft(state)).toBe(definition)
  })

  it('accepts a draft whose steps array is empty', () => {
    expect(readWorkflowDraft({ workflowDraft: { definition: { steps: [] } } })).toEqual({ steps: [] })
  })

  it.each([
    ['no state', undefined],
    ['a null state', null],
    ['a non-object state', 'nope'],
    ['an unrelated state', { navigationId: 3 }],
    ['a draft that is not an object', { workflowDraft: 'nope' }],
    ['a null draft', { workflowDraft: null }],
    ['a draft without definition', { workflowDraft: { workflowId: 'run-1' } }],
    ['a definition that is not an object', { workflowDraft: { definition: 'nope' } }],
    ['a definition that is an array', { workflowDraft: { definition: [] } }],
    ['a definition without steps', { workflowDraft: { definition: { workflowType: 'x' } } }],
    ['steps that are not an array', { workflowDraft: { definition: { steps: 'nope' } } }],
  ])('ignores %s', (_label, state) => {
    expect(readWorkflowDraft(state)).toBeUndefined()
  })
})
