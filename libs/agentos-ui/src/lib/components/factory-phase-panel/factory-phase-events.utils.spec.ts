import {
  AgentFinishedEvent,
  AgentRunningEvent,
  AgentSelectedEvent,
  AnswerEvent,
  CaseEvent,
  CaseStatusEvent,
  CaseStatusEventStatusEnum,
  ErrorEvent,
  IntentionGeneratedEvent,
  MessageEvent as CaseMessageEvent,
  QuestionEvent,
  QuestionEventQuestionTypeEnum,
  ToolRequestEvent,
  ToolResponseEvent,
  WarnEvent,
} from '@whoz-oss/agentos-api-client'
import { FactoryRunPhase } from '../../services/factory-api.service'
import { extractPhaseCaseId, projectPhaseEventRows } from './factory-phase-events.utils'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const CASE_ID = 'case-abc-123'
const META = { createdAt: '2025-01-01T00:00:00.000Z', updatedAt: '2025-01-01T00:00:00.000Z' }

function makePhase(facts: Record<string, unknown> = {}): FactoryRunPhase {
  return { name: 'agent-phase', phaseKind: 'agent', status: 'pass', startedAt: null, durationMs: null, facts }
}

function makeToolRequest(overrides: Partial<ToolRequestEvent> = {}): ToolRequestEvent {
  return {
    id: 'req-1',
    caseId: CASE_ID,
    namespaceId: 'ns',
    timestamp: '2025-01-01T10:00:00.000Z',
    metadata: META,
    type: 'ToolRequestEvent',
    toolName: 'read_file',
    toolRequestId: 'tr-1',
    args: '{"path":"foo.ts"}',
    ...overrides,
  }
}

function makeToolResponse(overrides: Partial<ToolResponseEvent> = {}): ToolResponseEvent {
  return {
    id: 'res-1',
    caseId: CASE_ID,
    namespaceId: 'ns',
    timestamp: '2025-01-01T10:00:01.000Z',
    metadata: META,
    type: 'ToolResponseEvent',
    toolName: 'read_file',
    toolRequestId: 'tr-1',
    success: true,
    durationMs: 42,
    images: [],
    output: { content: 'file contents' } as Record<string, unknown>,
    toolMetadata: {},
    ...overrides,
  }
}

function makeMessage(
  role: 'USER' | 'AGENT',
  text: string,
  overrides: Partial<CaseMessageEvent> = {}
): CaseMessageEvent {
  return {
    id: `msg-${role}-${text.slice(0, 8)}`,
    caseId: CASE_ID,
    namespaceId: 'ns',
    timestamp: '2025-01-01T10:00:02.000Z',
    metadata: META,
    type: 'MessageEvent',
    actor: { id: role.toLowerCase(), role, displayName: role === 'AGENT' ? 'Frontay' : 'User' },
    content: [{ content: text } as Record<string, unknown>],
    ...overrides,
  }
}

// ---------------------------------------------------------------------------
// 1. Phase-level caseId extraction — no run-level fallback
// ---------------------------------------------------------------------------

describe('extractPhaseCaseId', () => {
  it('returns caseId from facts.caseId', () => {
    expect(extractPhaseCaseId(makePhase({ caseId: CASE_ID }))).toBe(CASE_ID)
  })

  it('returns null when facts.caseId is absent', () => {
    expect(extractPhaseCaseId(makePhase({}))).toBeNull()
  })

  it('returns null when facts.caseId is an empty string', () => {
    expect(extractPhaseCaseId(makePhase({ caseId: '' }))).toBeNull()
  })

  it('returns null when facts.caseId is not a string', () => {
    expect(extractPhaseCaseId(makePhase({ caseId: 42 }))).toBeNull()
    expect(extractPhaseCaseId(makePhase({ caseId: null }))).toBeNull()
  })

  it('does NOT use any run-level caseId field — only facts.caseId', () => {
    // A phase with no facts.caseId must return null even if the caller
    // passes a run-level caseId separately (not part of FactoryRunPhase).
    const phase = makePhase({ agentName: 'Frontay' })
    expect(extractPhaseCaseId(phase)).toBeNull()
  })

  it('handles undefined facts gracefully', () => {
    const phase = { ...makePhase(), facts: undefined as unknown as Record<string, unknown> }
    expect(extractPhaseCaseId(phase)).toBeNull()
  })
})

// ---------------------------------------------------------------------------
// 2. REST request for selected phase — chronological ordering
// ---------------------------------------------------------------------------

describe('projectPhaseEventRows — chronological ordering', () => {
  it('sorts events by timestamp ascending', () => {
    const events: CaseEvent[] = [
      makeMessage('AGENT', 'later', { id: 'msg-b', timestamp: '2025-01-01T10:00:03.000Z' }),
      makeMessage('USER', 'earlier', { id: 'msg-a', timestamp: '2025-01-01T10:00:01.000Z' }),
    ]
    const rows = projectPhaseEventRows(events)
    expect(rows[0].id).toBe('msg-a')
    expect(rows[1].id).toBe('msg-b')
  })

  it('preserves order for events with the same timestamp', () => {
    const ts = '2025-01-01T10:00:00.000Z'
    const events: CaseEvent[] = [
      makeMessage('USER', 'first', { id: 'msg-1', timestamp: ts }),
      makeMessage('AGENT', 'second', { id: 'msg-2', timestamp: ts }),
    ]
    const rows = projectPhaseEventRows(events)
    // Stable sort: same-timestamp items keep their relative order
    expect(rows.map((r) => r.id)).toEqual(['msg-1', 'msg-2'])
  })
})

// ---------------------------------------------------------------------------
// 3. Loading / empty / missing-id / error states (pure util layer)
// ---------------------------------------------------------------------------

describe('projectPhaseEventRows — edge cases', () => {
  it('returns an empty array for an empty event list', () => {
    expect(projectPhaseEventRows([])).toEqual([])
  })

  it('handles events with missing/null timestamps without throwing', () => {
    const event = makeMessage('USER', 'hello', { timestamp: '' })
    expect(() => projectPhaseEventRows([event])).not.toThrow()
  })
})

// ---------------------------------------------------------------------------
// 4. Stale response guard (extractPhaseCaseId contract)
// ---------------------------------------------------------------------------

describe('stale response guard — caseId contract', () => {
  it('returns null for a phase with no caseId, signalling no fetch should occur', () => {
    // The component uses extractPhaseCaseId to decide whether to fetch.
    // A null return means "do not fetch" — prevents using a wrong caseId.
    const phase = makePhase({ agentName: 'Sway', exitCode: 0 })
    expect(extractPhaseCaseId(phase)).toBeNull()
  })

  it('returns the correct caseId for a phase that has one', () => {
    const phase = makePhase({ caseId: 'fresh-case-id', agentName: 'Sway' })
    expect(extractPhaseCaseId(phase)).toBe('fresh-case-id')
  })
})

// ---------------------------------------------------------------------------
// 5. Brief + agent response projection (MessageEvent)
// ---------------------------------------------------------------------------

describe('projectPhaseEventRows — message projection', () => {
  it('projects a USER message with correct role and text', () => {
    const rows = projectPhaseEventRows([makeMessage('USER', 'Fix the bug in auth.ts')])
    expect(rows).toHaveLength(1)
    const row = rows[0]
    expect(row.kind).toBe('message')
    if (row.kind === 'message') {
      expect(row.role).toBe('USER')
      expect(row.speaker).toBe('User')
      expect(row.text).toBe('Fix the bug in auth.ts')
    }
  })

  it('projects an AGENT message with correct role and displayName as speaker', () => {
    const rows = projectPhaseEventRows([makeMessage('AGENT', 'Done! I fixed the issue.')])
    const row = rows[0]
    if (row.kind === 'message') {
      expect(row.role).toBe('AGENT')
      expect(row.speaker).toBe('Frontay')
      expect(row.text).toBe('Done! I fixed the issue.')
    }
  })

  it('falls back to role as speaker when displayName is empty', () => {
    const msg = makeMessage('AGENT', 'Hello', {
      actor: { id: 'agent-1', role: 'AGENT', displayName: '' },
    })
    const rows = projectPhaseEventRows([msg])
    const row = rows[0]
    if (row.kind === 'message') {
      expect(row.speaker).toBe('AGENT')
    }
  })

  it('extracts text from the first content block', () => {
    const msg = makeMessage('AGENT', 'chunk1', {
      content: [{ content: 'part1' } as Record<string, unknown>, { content: 'part2' } as Record<string, unknown>],
    })
    const rows = projectPhaseEventRows([msg])
    const row = rows[0]
    if (row.kind === 'message') {
      expect(row.text).toBe('part1part2')
    }
  })
})

// ---------------------------------------------------------------------------
// 6. Tool request/response — success/failure/duration
// ---------------------------------------------------------------------------

describe('projectPhaseEventRows — tool call correlation', () => {
  it('merges ToolRequestEvent and ToolResponseEvent into a single tool row', () => {
    const events: CaseEvent[] = [makeToolRequest(), makeToolResponse()]
    const rows = projectPhaseEventRows(events)
    expect(rows).toHaveLength(1)
    expect(rows[0].kind).toBe('tool')
  })

  it('captures toolName, success, durationMs, and outputPreview', () => {
    const events: CaseEvent[] = [makeToolRequest(), makeToolResponse()]
    const rows = projectPhaseEventRows(events)
    const row = rows[0]
    if (row.kind === 'tool') {
      expect(row.call.toolName).toBe('read_file')
      expect(row.call.success).toBe(true)
      expect(row.call.durationMs).toBe(42)
      expect(row.call.outputPreview).toBe('file contents')
    }
  })

  it('marks a failed tool call with success=false', () => {
    const events: CaseEvent[] = [makeToolRequest(), makeToolResponse({ success: false, durationMs: 100 })]
    const rows = projectPhaseEventRows(events)
    const row = rows[0]
    if (row.kind === 'tool') {
      expect(row.call.success).toBe(false)
      expect(row.call.durationMs).toBe(100)
    }
  })

  it('handles a ToolRequestEvent with no matching response (pending)', () => {
    const events: CaseEvent[] = [makeToolRequest()]
    const rows = projectPhaseEventRows(events)
    expect(rows).toHaveLength(1)
    const row = rows[0]
    if (row.kind === 'tool') {
      expect(row.call.success).toBeNull()
      expect(row.call.durationMs).toBeNull()
    }
  })

  it('handles a ToolResponseEvent with no matching request (orphan)', () => {
    const events: CaseEvent[] = [makeToolResponse()]
    const rows = projectPhaseEventRows(events)
    expect(rows).toHaveLength(1)
    expect(rows[0].kind).toBe('tool')
  })

  it('does not emit duplicate rows when request and response are both present', () => {
    const events: CaseEvent[] = [makeToolRequest(), makeToolResponse(), makeToolResponse({ id: 'res-2' })]
    const rows = projectPhaseEventRows(events)
    // Only one tool row per toolRequestId, regardless of duplicate responses
    expect(rows.filter((r) => r.kind === 'tool')).toHaveLength(1)
  })

  it('captures args from the ToolRequestEvent', () => {
    const events: CaseEvent[] = [makeToolRequest({ args: '{"file":"src/main.ts"}' })]
    const rows = projectPhaseEventRows(events)
    const row = rows[0]
    if (row.kind === 'tool') {
      expect(row.call.args).toBe('{"file":"src/main.ts"}')
    }
  })
})

// ---------------------------------------------------------------------------
// 7. Lifecycle / message / error / question events
// ---------------------------------------------------------------------------

describe('projectPhaseEventRows — lifecycle events', () => {
  it('projects AgentSelectedEvent', () => {
    const event: AgentSelectedEvent = {
      id: 'ev-1',
      caseId: CASE_ID,
      namespaceId: 'ns',
      timestamp: '2025-01-01T10:00:00.000Z',
      metadata: META,
      type: 'AgentSelectedEvent',
      agentId: 'ag-1',
      agentName: 'Frontay',
    }
    const rows = projectPhaseEventRows([event])
    expect(rows[0].kind).toBe('agent-selected')
    if (rows[0].kind === 'agent-selected') {
      expect(rows[0].agentName).toBe('Frontay')
    }
  })

  it('projects AgentRunningEvent with llmModel', () => {
    const event: AgentRunningEvent = {
      id: 'ev-2',
      caseId: CASE_ID,
      namespaceId: 'ns',
      timestamp: '2025-01-01T10:00:01.000Z',
      metadata: META,
      type: 'AgentRunningEvent',
      agentId: 'ag-1',
      agentName: 'Frontay',
      llmModel: 'claude-3-5-sonnet',
      llmProvider: 'anthropic',
    }
    const rows = projectPhaseEventRows([event])
    expect(rows[0].kind).toBe('agent-running')
    if (rows[0].kind === 'agent-running') {
      expect(rows[0].llmModel).toBe('claude-3-5-sonnet')
    }
  })

  it('projects AgentFinishedEvent', () => {
    const event: AgentFinishedEvent = {
      id: 'ev-3',
      caseId: CASE_ID,
      namespaceId: 'ns',
      timestamp: '2025-01-01T10:00:02.000Z',
      metadata: META,
      type: 'AgentFinishedEvent',
      agentId: 'ag-1',
      agentName: 'Frontay',
    }
    const rows = projectPhaseEventRows([event])
    expect(rows[0].kind).toBe('agent-finished')
  })

  it('projects CaseStatusEvent', () => {
    const event: CaseStatusEvent = {
      id: 'ev-4',
      caseId: CASE_ID,
      namespaceId: 'ns',
      timestamp: '2025-01-01T10:00:03.000Z',
      metadata: META,
      type: 'CaseStatusEvent',
      status: CaseStatusEventStatusEnum.RUNNING,
    }
    const rows = projectPhaseEventRows([event])
    expect(rows[0].kind).toBe('case-status')
    if (rows[0].kind === 'case-status') {
      expect(rows[0].status).toBe('RUNNING')
    }
  })

  it('projects WarnEvent', () => {
    const event: WarnEvent = {
      id: 'ev-5',
      caseId: CASE_ID,
      namespaceId: 'ns',
      timestamp: '2025-01-01T10:00:04.000Z',
      metadata: META,
      type: 'WarnEvent',
      message: 'Rate limit approaching',
    }
    const rows = projectPhaseEventRows([event])
    expect(rows[0].kind).toBe('warn')
    if (rows[0].kind === 'warn') {
      expect(rows[0].message).toBe('Rate limit approaching')
    }
  })

  it('projects ErrorEvent', () => {
    const event: ErrorEvent = {
      id: 'ev-6',
      caseId: CASE_ID,
      namespaceId: 'ns',
      timestamp: '2025-01-01T10:00:05.000Z',
      metadata: META,
      type: 'ErrorEvent',
      message: 'Timeout after 30s',
    }
    const rows = projectPhaseEventRows([event])
    expect(rows[0].kind).toBe('error')
    if (rows[0].kind === 'error') {
      expect(rows[0].message).toBe('Timeout after 30s')
    }
  })

  it('projects IntentionGeneratedEvent with isFailedIntention flag', () => {
    const event: IntentionGeneratedEvent = {
      id: 'ev-7',
      caseId: CASE_ID,
      namespaceId: 'ns',
      timestamp: '2025-01-01T10:00:06.000Z',
      metadata: META,
      type: 'IntentionGeneratedEvent',
      agentId: 'ag-1',
      toolName: 'bash',
      intention: 'Run tests',
      isFailedIntention: false,
    }
    const rows = projectPhaseEventRows([event])
    expect(rows[0].kind).toBe('intention')
    if (rows[0].kind === 'intention') {
      expect(rows[0].toolName).toBe('bash')
      expect(rows[0].intention).toBe('Run tests')
      expect(rows[0].failed).toBe(false)
    }
  })

  it('projects IntentionGeneratedEvent with isFailedIntention=true', () => {
    const event: IntentionGeneratedEvent = {
      id: 'ev-8',
      caseId: CASE_ID,
      namespaceId: 'ns',
      timestamp: '2025-01-01T10:00:07.000Z',
      metadata: META,
      type: 'IntentionGeneratedEvent',
      agentId: 'ag-1',
      toolName: 'bash',
      intention: 'Tried and failed',
      isFailedIntention: true,
    }
    const rows = projectPhaseEventRows([event])
    if (rows[0].kind === 'intention') {
      expect(rows[0].failed).toBe(true)
    }
  })

  it('projects QuestionEvent', () => {
    const event: QuestionEvent = {
      id: 'ev-9',
      caseId: CASE_ID,
      namespaceId: 'ns',
      timestamp: '2025-01-01T10:00:08.000Z',
      metadata: META,
      type: 'QuestionEvent',
      agentId: 'ag-1',
      agentName: 'Frontay',
      question: 'Should I overwrite the file?',
      questionType: QuestionEventQuestionTypeEnum.SINGLE_CHOICE,
      options: ['Yes', 'No'],
    }
    const rows = projectPhaseEventRows([event])
    expect(rows[0].kind).toBe('question')
    if (rows[0].kind === 'question') {
      expect(rows[0].question).toBe('Should I overwrite the file?')
      expect(rows[0].agentName).toBe('Frontay')
    }
  })

  it('projects AnswerEvent', () => {
    const event: AnswerEvent = {
      id: 'ev-10',
      caseId: CASE_ID,
      namespaceId: 'ns',
      timestamp: '2025-01-01T10:00:09.000Z',
      metadata: META,
      type: 'AnswerEvent',
      actor: { id: 'user-1', role: 'USER', displayName: 'User' },
      answer: 'Yes',
      questionId: 'ev-9',
    }
    const rows = projectPhaseEventRows([event])
    expect(rows[0].kind).toBe('answer')
    if (rows[0].kind === 'answer') {
      expect(rows[0].answer).toBe('Yes')
    }
  })
})

// ---------------------------------------------------------------------------
// 8. Unknown event generic fallback
// ---------------------------------------------------------------------------

describe('projectPhaseEventRows — unknown event fallback', () => {
  it('produces an unknown row for an unrecognised event type', () => {
    const event = {
      id: 'ev-unknown',
      caseId: CASE_ID,
      namespaceId: 'ns',
      timestamp: '2025-01-01T10:00:00.000Z',
      metadata: META,
      type: 'FutureEventType',
      someNewField: 'interesting data',
    } as unknown as CaseEvent
    const rows = projectPhaseEventRows([event])
    expect(rows).toHaveLength(1)
    expect(rows[0].kind).toBe('unknown')
    if (rows[0].kind === 'unknown') {
      expect(rows[0].type).toBe('FutureEventType')
      // Payload must include the extra field but not the base fields
      expect(rows[0].payload).toContain('someNewField')
      expect(rows[0].payload).not.toContain('"id":')
      expect(rows[0].payload).not.toContain('"caseId":')
    }
  })

  it('truncates a very large payload to 300 chars with ellipsis', () => {
    const bigData = 'x'.repeat(1000)
    const event = {
      id: 'ev-big',
      caseId: CASE_ID,
      namespaceId: 'ns',
      timestamp: '2025-01-01T10:00:00.000Z',
      metadata: META,
      type: 'BigEvent',
      bigField: bigData,
    } as unknown as CaseEvent
    const rows = projectPhaseEventRows([event])
    if (rows[0].kind === 'unknown') {
      expect(rows[0].payload.length).toBeLessThanOrEqual(300)
      expect(rows[0].payload.endsWith('…')).toBe(true)
    }
  })

  it('exposes timestamp and type for unknown events', () => {
    const event = {
      id: 'ev-u2',
      caseId: CASE_ID,
      namespaceId: 'ns',
      timestamp: '2025-01-01T12:34:56.000Z',
      metadata: META,
      type: 'AnotherFutureEvent',
    } as unknown as CaseEvent
    const rows = projectPhaseEventRows([event])
    if (rows[0].kind === 'unknown') {
      expect(rows[0].timestamp).toBe('2025-01-01T12:34:56.000Z')
      expect(rows[0].type).toBe('AnotherFutureEvent')
    }
  })
})

// ---------------------------------------------------------------------------
// 9. Cleanup / live refresh behaviour (contract-level)
// ---------------------------------------------------------------------------

describe('extractPhaseCaseId — live refresh contract', () => {
  it('returns null for a non-agent phase (no caseId in facts), triggering no-case-id state', () => {
    // Non-agent phases (e.g. lint, build) don't have a caseId in facts.
    const phase = makePhase({ exitCode: 0, command: 'pnpm nx lint agentos-ui' })
    expect(extractPhaseCaseId(phase)).toBeNull()
  })

  it('returns caseId for an agent phase, enabling REST load and optional polling', () => {
    const phase = makePhase({ caseId: 'live-case-id', agentName: 'Sway' })
    expect(extractPhaseCaseId(phase)).toBe('live-case-id')
  })

  it('returns different caseIds for different phases (no cross-contamination)', () => {
    const phaseA = makePhase({ caseId: 'case-a' })
    const phaseB = makePhase({ caseId: 'case-b' })
    expect(extractPhaseCaseId(phaseA)).toBe('case-a')
    expect(extractPhaseCaseId(phaseB)).toBe('case-b')
  })
})
