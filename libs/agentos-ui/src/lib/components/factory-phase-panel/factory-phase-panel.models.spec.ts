import { FactoryRunPhase } from '../../services/factory-api.service'
import { PhaseEventRow } from './factory-phase-events.utils'
import {
  projectConversation,
  projectPhaseEvidence,
  projectPhaseFacts,
  projectPhaseBriefFromFacts,
  projectBriefResponseFromEvents,
  projectReviewOutcomes,
  projectFetchTicketInfo,
  displayValue,
  displayValueBounded,
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

describe('displayValue', () => {
  it('stringifies plain objects to JSON instead of returning Recorded value', () => {
    const result = displayValue({ reviewer: 'Archay', verdict: 'fail' })
    expect(result).toContain('Archay')
    expect(result).toContain('fail')
    expect(result).not.toBe('Recorded value')
  })

  it('returns string values directly', () => {
    expect(displayValue('hello')).toBe('hello')
  })

  it('converts numbers and booleans to string', () => {
    expect(displayValue(42)).toBe('42')
    expect(displayValue(false)).toBe('false')
  })

  it('joins arrays with comma', () => {
    expect(displayValue(['a', 'b'])).toBe('a, b')
  })
})

describe('displayValueBounded', () => {
  it('returns string values directly', () => {
    expect(displayValueBounded('hello')).toBe('hello')
  })

  it('converts numbers and booleans to string', () => {
    expect(displayValueBounded(42)).toBe('42')
    expect(displayValueBounded(false)).toBe('false')
  })

  it('joins flat arrays with comma', () => {
    expect(displayValueBounded(['a', 'b'])).toBe('a, b')
  })

  it('truncates objects exceeding 40 lines', () => {
    const big: Record<string, string> = {}
    for (let i = 0; i < 50; i++) big[`key${i}`] = `value${i}`
    const result = displayValueBounded(big)
    const lines = result.split('\n')
    // 40 content lines + 1 truncation message line
    expect(lines.length).toBe(41)
    expect(result).toContain('more lines')
  })

  it('does not truncate objects fitting within 40 lines', () => {
    const result = displayValueBounded({ a: 1, b: 2 })
    expect(result).not.toContain('more lines')
  })
})

describe('Factory phase facts', () => {
  it('projects canonical facts into their intended sections', () => {
    expect(projectPhaseFacts(phase({ exitCode: 0, agentName: 'Frontay', custom: true }))).toEqual([
      { title: 'Outcome', entries: [{ key: 'exitCode', value: '0' }] },
      { title: 'Context', entries: [{ key: 'agentName', value: 'Frontay' }] },
      { title: 'Other recorded facts', entries: [{ key: 'custom', value: 'true' }] },
    ])
  })

  it('excludes outcomes from Other recorded facts', () => {
    const sections = projectPhaseFacts(
      phase({
        outcomes: [{ reviewerName: 'Archay', verdict: 'PASS', hasCritical: false, summary: null }],
        custom: 'kept',
      })
    )
    const otherSection = sections.find((s) => s.title === 'Other recorded facts')
    expect(otherSection?.entries.find((e) => e.key === 'outcomes')).toBeUndefined()
    // non-outcomes keys still appear
    expect(otherSection?.entries.find((e) => e.key === 'custom')).toBeDefined()
  })

  it('renders nested objects in Other recorded facts as bounded preformatted JSON (not a huge single line)', () => {
    const sections = projectPhaseFacts(phase({ nestedObj: { foo: 'bar', baz: 42 } }))
    const otherSection = sections.find((s) => s.title === 'Other recorded facts')
    const entry = otherSection?.entries.find((e) => e.key === 'nestedObj')
    // Should be formatted JSON (multi-line) rather than a single-line string
    expect(entry?.value).toContain('\n')
    expect(entry?.value).toContain('foo')
    expect(entry?.value).toContain('bar')
  })

  it('projects only structured recorded conversation', () => {
    expect(
      projectConversation(phase({ messages: [{ role: 'agent', content: 'Recorded update' }, { ignored: true }] }))
    ).toEqual([{ speaker: 'agent', content: 'Recorded update' }])
    expect(projectConversation(phase({ messages: 'not a conversation' }))).toEqual([])
  })

  it('renders task objects as compact key-value chips instead of JSON', () => {
    const sections = projectPhaseFacts(phase({ tasks: { planned: 3, completed: 2, nested: { hidden: true } } }))
    expect(sections).toHaveLength(1)
    expect(sections[0]!.title).toBe('Outcome')
    const entry = sections[0]!.entries[0]!
    expect(entry.key).toBe('tasks')
    // chips are extracted for non-object leaf values
    expect(entry.chips).toEqual(['planned: 3', 'completed: 2'])
    // value is now a JSON stringification (not 'Recorded value') since displayValue
    // stringifies objects; the chips are the primary display mechanism for tasks
    expect(typeof entry.value).toBe('string')
    expect(entry.value.length).toBeGreaterThan(0)
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

// ---------------------------------------------------------------------------
// projectFetchTicketInfo
// ---------------------------------------------------------------------------

describe('projectFetchTicketInfo', () => {
  it('returns null when ticketId is absent from facts', () => {
    expect(projectFetchTicketInfo(phase({}))).toBeNull()
  })

  it('returns null when ticketId is not a string', () => {
    expect(projectFetchTicketInfo(phase({ ticketId: 42 }))).toBeNull()
    expect(projectFetchTicketInfo(phase({ ticketId: null }))).toBeNull()
  })

  it('returns null when ticketId is an empty string', () => {
    expect(projectFetchTicketInfo(phase({ ticketId: '' }))).toBeNull()
  })

  it('returns a FetchTicketInfo with the ticketId when present', () => {
    const result = projectFetchTicketInfo(phase({ ticketId: 'PROJ-1234' }))
    expect(result).not.toBeNull()
    expect(result!.ticketId).toBe('PROJ-1234')
  })

  it('maps all metadata facts when fully populated', () => {
    const result = projectFetchTicketInfo(
      phase({
        ticketId: 'DEV-99',
        summary: 'Fix login timeout',
        fieldCount: 12,
        commentCount: 5,
        commentsIncluded: 3,
        commentsTruncated: true,
      })
    )
    expect(result).toEqual({
      ticketId: 'DEV-99',
      summary: 'Fix login timeout',
      fieldCount: 12,
      commentCount: 5,
      commentsIncluded: 3,
      commentsTruncated: true,
    })
  })

  it('sets optional fields to null when absent', () => {
    const result = projectFetchTicketInfo(phase({ ticketId: 'PROJ-1' }))
    expect(result!.summary).toBeNull()
    expect(result!.fieldCount).toBeNull()
    expect(result!.commentCount).toBeNull()
    expect(result!.commentsIncluded).toBeNull()
    expect(result!.commentsTruncated).toBeNull()
  })

  it('sets summary to null when it is not a string', () => {
    const result = projectFetchTicketInfo(phase({ ticketId: 'PROJ-1', summary: 42 }))
    expect(result!.summary).toBeNull()
  })

  it('sets numeric fields to null when they are not numbers', () => {
    const result = projectFetchTicketInfo(phase({ ticketId: 'PROJ-1', fieldCount: 'many', commentCount: null }))
    expect(result!.fieldCount).toBeNull()
    expect(result!.commentCount).toBeNull()
  })

  it('sets commentsTruncated to null when it is not a boolean', () => {
    const result = projectFetchTicketInfo(phase({ ticketId: 'PROJ-1', commentsTruncated: 'yes' }))
    expect(result!.commentsTruncated).toBeNull()
  })

  it('fetch-ticket facts are excluded from generic projectPhaseFacts sections', () => {
    const sections = projectPhaseFacts(
      phase({
        ticketId: 'PROJ-1234',
        summary: 'Fix login',
        fieldCount: 10,
        commentCount: 3,
        commentsIncluded: 3,
        commentsTruncated: false,
      })
    )
    const allEntries = sections.flatMap((s) => s.entries)
    for (const key of ['ticketId', 'summary', 'fieldCount', 'commentCount', 'commentsIncluded', 'commentsTruncated']) {
      expect(allEntries.find((e) => e.key === key)).toBeUndefined()
    }
  })

  it('other facts co-existing with fetch-ticket facts are still rendered', () => {
    const sections = projectPhaseFacts(
      phase({
        ticketId: 'PROJ-1',
        summary: 'Something',
        exitCode: 0,
        customFact: 'hello',
      })
    )
    const allEntries = sections.flatMap((s) => s.entries)
    expect(allEntries.find((e) => e.key === 'exitCode')).toBeDefined()
    expect(allEntries.find((e) => e.key === 'customFact')).toBeDefined()
  })
})

// ---------------------------------------------------------------------------
// projectReviewOutcomes
// ---------------------------------------------------------------------------

describe('projectReviewOutcomes', () => {
  // Valid outcomes
  describe('valid array', () => {
    it('returns kind:valid for a well-formed outcomes array', () => {
      const p = phase({
        outcomes: [
          { reviewerName: 'Archay', verdict: 'PASS', hasCritical: false, summary: 'All good.' },
          { reviewerName: 'Sway', verdict: 'FAIL', hasCritical: true, summary: 'Found issues.' },
        ],
      })
      const result = projectReviewOutcomes(p)
      expect(result.kind).toBe('valid')
      if (result.kind !== 'valid') return
      expect(result.outcomes).toHaveLength(2)
    })

    it('maps reviewerName, verdict (uppercased), hasCritical, and summary', () => {
      const p = phase({
        outcomes: [{ reviewerName: 'Archay', verdict: 'pass', hasCritical: false, summary: 'Looks good.' }],
      })
      const result = projectReviewOutcomes(p)
      if (result.kind !== 'valid') throw new Error('Expected valid')
      const outcome = result.outcomes[0]!
      expect(outcome.reviewerName).toBe('Archay')
      expect(outcome.verdict).toBe('PASS')
      expect(outcome.hasCritical).toBe(false)
      expect(outcome.summary).toBe('Looks good.')
    })

    it('captures caseId when present', () => {
      const p = phase({
        outcomes: [{ reviewerName: 'Archay', verdict: 'PASS', hasCritical: false, caseId: 'c-abc-123' }],
      })
      const result = projectReviewOutcomes(p)
      if (result.kind !== 'valid') throw new Error('Expected valid')
      expect(result.outcomes[0]!.caseId).toBe('c-abc-123')
    })

    it('sets caseId to null when absent', () => {
      const p = phase({
        outcomes: [{ reviewerName: 'Archay', verdict: 'PASS', hasCritical: false }],
      })
      const result = projectReviewOutcomes(p)
      if (result.kind !== 'valid') throw new Error('Expected valid')
      expect(result.outcomes[0]!.caseId).toBeNull()
    })

    it('sets summary to null when absent', () => {
      const p = phase({
        outcomes: [{ reviewerName: 'Archay', verdict: 'PASS', hasCritical: false }],
      })
      const result = projectReviewOutcomes(p)
      if (result.kind !== 'valid') throw new Error('Expected valid')
      expect(result.outcomes[0]!.summary).toBeNull()
    })

    it('defaults verdict to SKIP when absent', () => {
      const p = phase({ outcomes: [{ reviewerName: 'Archay' }] })
      const result = projectReviewOutcomes(p)
      if (result.kind !== 'valid') throw new Error('Expected valid')
      expect(result.outcomes[0]!.verdict).toBe('SKIP')
    })

    it('accepts reviewer alias field', () => {
      const p = phase({
        outcomes: [{ reviewer: 'Sway', verdict: 'FAIL', hasCritical: false }],
      })
      const result = projectReviewOutcomes(p)
      if (result.kind !== 'valid') throw new Error('Expected valid')
      expect(result.outcomes[0]!.reviewerName).toBe('Sway')
    })

    it('accepts status alias for verdict', () => {
      const p = phase({
        outcomes: [{ reviewerName: 'Archay', status: 'pass', hasCritical: false }],
      })
      const result = projectReviewOutcomes(p)
      if (result.kind !== 'valid') throw new Error('Expected valid')
      expect(result.outcomes[0]!.verdict).toBe('PASS')
    })

    it('collects unrecognised fields into extra', () => {
      const p = phase({
        outcomes: [
          { reviewerName: 'Archay', verdict: 'PASS', hasCritical: false, errorCode: 'E001', customField: 'value' },
        ],
      })
      const result = projectReviewOutcomes(p)
      if (result.kind !== 'valid') throw new Error('Expected valid')
      expect(result.outcomes[0]!.extra).toContain('errorCode')
      expect(result.outcomes[0]!.extra).toContain('customField')
    })

    it('sets extra to null when no unrecognised fields exist', () => {
      const p = phase({
        outcomes: [{ reviewerName: 'Archay', verdict: 'PASS', hasCritical: false }],
      })
      const result = projectReviewOutcomes(p)
      if (result.kind !== 'valid') throw new Error('Expected valid')
      expect(result.outcomes[0]!.extra).toBeNull()
    })
  })

  // Absent / empty
  describe('absent or empty', () => {
    it('returns kind:absent when outcomes key is not in facts', () => {
      expect(projectReviewOutcomes(phase({}))).toEqual({ kind: 'absent' })
    })

    it('returns kind:absent when outcomes is explicitly null', () => {
      expect(projectReviewOutcomes(phase({ outcomes: null }))).toEqual({ kind: 'absent' })
    })

    it('returns kind:empty when outcomes is an empty array', () => {
      expect(projectReviewOutcomes(phase({ outcomes: [] }))).toEqual({ kind: 'empty' })
    })
  })

  // Malformed
  describe('malformed', () => {
    it('returns kind:malformed when outcomes is a plain object (not array)', () => {
      const result = projectReviewOutcomes(phase({ outcomes: { reviewer: 'Archay' } }))
      expect(result.kind).toBe('malformed')
    })

    it('returns kind:malformed when outcomes is a string', () => {
      const result = projectReviewOutcomes(phase({ outcomes: 'unexpected-string' }))
      expect(result.kind).toBe('malformed')
    })

    it('returns kind:malformed when array items have no recognisable reviewer field', () => {
      const result = projectReviewOutcomes(phase({ outcomes: [{ foo: 'bar' }, { baz: 42 }] }))
      expect(result.kind).toBe('malformed')
      if (result.kind !== 'malformed') return
      expect(result.raw).toContain('foo')
    })

    it('includes a non-empty raw string in malformed result', () => {
      const result = projectReviewOutcomes(phase({ outcomes: 'surprise' }))
      if (result.kind !== 'malformed') throw new Error('Expected malformed')
      expect(result.raw.length).toBeGreaterThan(0)
    })
  })

  // No interference with generic facts
  describe('isolation from projectPhaseFacts', () => {
    it('does not produce a "Recorded value" entry for valid outcomes', () => {
      const sections = projectPhaseFacts(
        phase({ outcomes: [{ reviewerName: 'Archay', verdict: 'PASS', hasCritical: false }] })
      )
      const allEntries = sections.flatMap((s) => s.entries)
      const outcomesEntry = allEntries.find((e) => e.key === 'outcomes')
      expect(outcomesEntry).toBeUndefined()
    })

    it('does not produce any outcomes entry in facts sections even when outcomes is malformed', () => {
      const sections = projectPhaseFacts(phase({ outcomes: 'bad-value', other: 'kept' }))
      const allEntries = sections.flatMap((s) => s.entries)
      expect(allEntries.find((e) => e.key === 'outcomes')).toBeUndefined()
      // other keys are still present
      expect(allEntries.find((e) => e.key === 'other')).toBeDefined()
    })
  })
})
