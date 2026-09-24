import {
  buildDelegations,
  extractToolOutputText,
  isCorrelatedDelegateTool,
  parseDelegationResults,
} from './delegation.models'

describe('delegation correlation', () => {
  const backendOutput = JSON.stringify([
    {
      agentName: 'Research',
      delegationId: 'd-1',
      toolRequestId: 't-1',
      subCaseId: 'c-1',
      success: true,
      result: '**Done**',
    },
  ])
  const response = {
    type: 'ToolResponseEvent',
    toolName: 'DELEGATE__delegate',
    toolRequestId: 't-1',
    output: backendOutput,
  } as any
  const request = { type: 'ToolRequestEvent', toolName: 'DELEGATE__delegate', toolRequestId: 't-1' } as any
  const started = {
    type: 'SubCaseStartedEvent',
    delegationId: 'd-1',
    toolRequestId: 't-1',
    subCaseId: 'c-1',
    agentName: 'Research',
    task: 'Find facts',
    resumed: false,
  } as any
  const finished = {
    type: 'SubCaseFinishedEvent',
    delegationId: 'd-1',
    toolRequestId: 't-1',
    subCaseId: 'c-1',
    agentName: 'Research',
    outcome: 'SUCCESS',
  } as any

  it('parses the raw string output format emitted by the backend', () => {
    expect(extractToolOutputText(backendOutput)).toBe(backendOutput)
    expect(parseDelegationResults(response)).toEqual([
      expect.objectContaining({ delegationId: 'd-1', toolRequestId: 't-1' }),
    ])
  })

  it('creates a delegation and hides the raw delegate card even when Started was not replayed', () => {
    const delegations = buildDelegations([request, response])
    expect(delegations).toEqual([expect.objectContaining({ delegationId: 'd-1', status: 'SUCCESS' })])
    expect(isCorrelatedDelegateTool('DELEGATE__delegate', 't-1', response, delegations)).toBe(true)
  })

  it('correlates lifecycle and parent response independently from arrival order', () => {
    expect(buildDelegations([response, finished, started])).toEqual([
      expect.objectContaining({
        delegationId: 'd-1',
        status: 'SUCCESS',
        result: expect.objectContaining({ result: '**Done**' }),
      }),
    ])
  })

  it('keeps raw fallback only for genuinely invalid output', () => {
    expect(parseDelegationResults({ output: '{invalid' } as any)).toBeNull()
  })
})
