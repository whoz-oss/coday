import { FactoryRunPhase } from '../../services/factory-api.service'
import { PhaseEventRow } from './factory-phase-events.utils'
import {
  projectConversation,
  projectPhaseEvidence,
  projectPhaseFacts,
  projectPhaseBriefFromFacts,
  projectBriefResponseFromEvents,
} from './factory-phase-panel.models'

const phase = (facts: Record<string, unknown>): FactoryRunPhase => ({
  name: 'verify',
  phaseKind: 'code',
  status: 'pass',
  startedAt: null,
  durationMs: null,
  facts,
})

const makeMessageRow = (
  role: string,
  text: string,
  id = `msg-${role}-${Math.random()}`
): Extract<PhaseEventRow, { kind: 'message' }> => ({
  kind: 'message',
  id,
  timestamp: '2025-01-01T10:00:00.000Z',
  role,
  speaker: role === 'USER' ? 'User' : 'Frontay',
  text,
})

describe('Factory phase facts', () => {
  it('projects canonical facts into their intended sections', () => {
    expect(projectPhaseFacts(phase({ exitCode: 0, agentName: 'Frontay', custom: true }))).toEqual([
      { title: 'Outcome', entries: [{ key: 'exitCode', value: '0' }] },
      { title: 'Context', entries: [{ key: 'agentName', value: 'Frontay' }] },
      { title: 'Other recorded facts', entries: [{ key: 'custom', value: 'true' }] },
    ])
  })

  it('projects only structured recorded conversation', () => {
    expect(
      projectConversation(phase({ messages: [{ role: 'agent', content: 'Recorded update' }, { ignored: true }] }))
    ).toEqual([{ speaker: 'agent', content: 'Recorded update' }])
    expect(projectConversation(phase({ messages: 'not a conversation' }))).toEqual([])
  })

  it('renders task objects as compact key-value chips instead of JSON', () => {
    expect(projectPhaseFacts(phase({ tasks: { planned: 3, completed: 2, nested: { hidden: true } } }))).toEqual([
      { title: 'Outcome', entries: [{ key: 'tasks', value: 'Recorded value', chips: ['planned: 3', 'completed: 2'] }] },
    ])
  })

  it('separates evidence from the generic fact sections', () => {
    const current = phase({
      command: 'pnpm nx test agentos-ui',
      toolsUsed: ['nx'],
      filesModified: ['run.ts'],
      logs: 'passed',
    })

    expect(projectPhaseEvidence(current)).toEqual({
      command: 'pnpm nx test agentos-ui',
      tools: 'nx',
      files: 'run.ts',
      log: 'passed',
    })
    expect(projectPhaseFacts(current)).toEqual([])
  })
})

describe('projectPhaseBriefFromFacts', () => {
  it('extracts brief from first user entry and agent response from last agent entry', () => {
    const p = phase({
      messages: [
        { role: 'user', content: 'Fix the bug in auth.ts' },
        { role: 'agent', content: 'I will fix it.' },
        { role: 'agent', content: 'Done, applied patch.' },
      ],
    })
    const result = projectPhaseBriefFromFacts(p)
    expect(result.brief).toBe('Fix the bug in auth.ts')
    expect(result.agentResponse).toBe('Done, applied patch.')
  })

  it('returns null brief when no user entry exists', () => {
    const p = phase({ messages: [{ role: 'agent', content: 'Hello.' }] })
    expect(projectPhaseBriefFromFacts(p).brief).toBeNull()
  })

  it('returns null agentResponse when no agent entry exists', () => {
    const p = phase({ messages: [{ role: 'user', content: 'Hello.' }] })
    expect(projectPhaseBriefFromFacts(p).agentResponse).toBeNull()
  })

  it('returns both null when messages fact is absent', () => {
    const result = projectPhaseBriefFromFacts(phase({}))
    expect(result.brief).toBeNull()
    expect(result.agentResponse).toBeNull()
  })

  it('recognises assistant and human aliases', () => {
    const p = phase({
      messages: [
        { role: 'human', content: 'Brief text' },
        { role: 'assistant', content: 'Response text' },
      ],
    })
    const result = projectPhaseBriefFromFacts(p)
    expect(result.brief).toBe('Brief text')
    expect(result.agentResponse).toBe('Response text')
  })
})

describe('projectBriefResponseFromEvents', () => {
  it('returns brief from first USER message and response from last AGENT message', () => {
    const rows: PhaseEventRow[] = [
      makeMessageRow('USER', 'Fix the bug', 'msg-1'),
      makeMessageRow('AGENT', 'First response', 'msg-2'),
      makeMessageRow('AGENT', 'Final response', 'msg-3'),
    ]
    const result = projectBriefResponseFromEvents(rows)
    expect(result.brief).toBe('Fix the bug')
    expect(result.agentResponse).toBe('Final response')
  })

  it('returns null for both when rows is empty', () => {
    const result = projectBriefResponseFromEvents([])
    expect(result.brief).toBeNull()
    expect(result.agentResponse).toBeNull()
  })

  it('returns null brief when no USER message exists', () => {
    const rows: PhaseEventRow[] = [makeMessageRow('AGENT', 'Hello')]
    expect(projectBriefResponseFromEvents(rows).brief).toBeNull()
  })

  it('returns null agentResponse when no AGENT message exists', () => {
    const rows: PhaseEventRow[] = [makeMessageRow('USER', 'Hello')]
    expect(projectBriefResponseFromEvents(rows).agentResponse).toBeNull()
  })

  it('ignores non-message rows when projecting', () => {
    const toolRow: PhaseEventRow = {
      kind: 'tool',
      id: 'tool-1',
      timestamp: '2025-01-01T10:00:00.000Z',
      call: { requestId: 'r1', toolName: 'bash', args: null, success: true, durationMs: 10, outputPreview: null },
    }
    const rows: PhaseEventRow[] = [toolRow, makeMessageRow('USER', 'Brief text')]
    const result = projectBriefResponseFromEvents(rows)
    expect(result.brief).toBe('Brief text')
    expect(result.agentResponse).toBeNull()
  })
})
