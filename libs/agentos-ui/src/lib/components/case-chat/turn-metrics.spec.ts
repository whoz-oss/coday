import { CaseEvent, LlmUsage } from '@whoz-oss/agentos-api-client'
import { buildTurnMetrics, formatTurnDuration, MAX_TURN_DURATION_MS } from './turn-metrics'

function usage(overrides: Partial<LlmUsage> = {}): LlmUsage {
  return {
    inputTokens: 100,
    outputTokens: 50,
    cacheReadTokens: 0,
    cacheWriteTokens: 0,
    totalTokens: 150,
    ...overrides,
  }
}

function running(timestamp: string): CaseEvent {
  return { id: `run-${timestamp}`, type: 'AgentRunningEvent', timestamp } as unknown as CaseEvent
}

function finished(id: string, timestamp: string, overrides: Record<string, unknown> = {}): CaseEvent {
  return { id, type: 'AgentFinishedEvent', timestamp, ...overrides } as unknown as CaseEvent
}

describe('buildTurnMetrics', () => {
  it('reads the usage the service already attached to the finished event', () => {
    const events = [
      running('2026-11-03T10:00:00.000Z'),
      finished('f-1', '2026-11-03T10:00:03.500Z', {
        llmUsage: usage({ estimatedCostUsd: 0.42 }),
        llmModel: 'claude-sonnet-5-5',
        llmProvider: 'anthropic',
      }),
    ]

    const metrics = buildTurnMetrics(events).get('f-1')!

    expect(metrics.totalTokens).toBe(150)
    expect(metrics.cost).toBe(0.42)
    expect(metrics.durationMs).toBe(3500)
    expect(metrics.llmModel).toBe('claude-sonnet-5-5')
  })

  it('accumulates the conversation cost turn by turn', () => {
    const events = [
      running('2026-11-03T10:00:00.000Z'),
      finished('f-1', '2026-11-03T10:00:01.000Z', { llmUsage: usage({ estimatedCostUsd: 0.1 }) }),
      running('2026-11-03T10:01:00.000Z'),
      finished('f-2', '2026-11-03T10:01:01.000Z', { llmUsage: usage({ estimatedCostUsd: 0.25 }) }),
    ]

    const metrics = buildTurnMetrics(events)

    expect(metrics.get('f-1')!.cumulativeCost).toBeCloseTo(0.1)
    expect(metrics.get('f-2')!.cumulativeCost).toBeCloseTo(0.35)
  })

  it('reports the running total as a lower bound once a turn is unpriced', () => {
    const events = [
      running('2026-11-03T10:00:00.000Z'),
      finished('f-1', '2026-11-03T10:00:01.000Z', { llmUsage: usage({ estimatedCostUsd: 0.1 }) }),
      running('2026-11-03T10:01:00.000Z'),
      finished('f-2', '2026-11-03T10:01:01.000Z', { llmUsage: usage() }),
      running('2026-11-03T10:02:00.000Z'),
      finished('f-3', '2026-11-03T10:02:01.000Z', { llmUsage: usage({ estimatedCostUsd: 0.3 }) }),
    ]

    const metrics = buildTurnMetrics(events)

    expect(metrics.get('f-1')!.cumulativeUnknownCount).toBe(0)
    expect(metrics.get('f-2')!.cost).toBeUndefined()
    // The known part is still worth showing; the count is what marks it as a floor.
    expect(metrics.get('f-3')!.cumulativeCost).toBeCloseTo(0.4)
    expect(metrics.get('f-3')!.cumulativeUnknownCount).toBe(1)
  })

  it('counts a turn whose event carried no usage, instead of dropping it from the total', () => {
    // The service builds a synthetic finished event when a run throws and never enriches it, while
    // the spend it already made is recorded. Ignoring it would make later totals look whole.
    const events = [
      running('2026-11-03T10:00:00.000Z'),
      finished('f-1', '2026-11-03T10:00:01.000Z', { llmUsage: usage({ estimatedCostUsd: 0.1 }) }),
      running('2026-11-03T10:01:00.000Z'),
      finished('f-2', '2026-11-03T10:01:01.000Z'),
      running('2026-11-03T10:02:00.000Z'),
      finished('f-3', '2026-11-03T10:02:01.000Z', { llmUsage: usage({ estimatedCostUsd: 0.3 }) }),
    ]

    const metrics = buildTurnMetrics(events)

    expect(metrics.has('f-2')).toBe(false)
    expect(metrics.get('f-3')!.cumulativeUnknownCount).toBe(1)
  })

  it('marks a turn that delegated: its own figure cannot see the sub-case spend', () => {
    const events = [
      running('2026-11-03T10:00:00.000Z'),
      { id: 's-1', type: 'SubCaseStartedEvent', timestamp: '2026-11-03T10:00:00.500Z' } as unknown as CaseEvent,
      finished('f-1', '2026-11-03T10:00:01.000Z', { llmUsage: usage({ estimatedCostUsd: 0.1 }) }),
      running('2026-11-03T10:01:00.000Z'),
      finished('f-2', '2026-11-03T10:01:01.000Z', { llmUsage: usage({ estimatedCostUsd: 0.2 }) }),
    ]

    const metrics = buildTurnMetrics(events)

    expect(metrics.get('f-1')!.costUnknownCount).toBe(1)
    // The next turn delegated nothing: its own figure is complete again.
    expect(metrics.get('f-2')!.costUnknownCount).toBe(0)
    // The conversation total stays a floor from then on.
    expect(metrics.get('f-2')!.cumulativeUnknownCount).toBe(1)
  })

  it('reports no metrics when usage tracking produced nothing', () => {
    const events = [running('2026-11-03T10:00:00.000Z'), finished('f-1', '2026-11-03T10:00:01.000Z')]

    expect(buildTurnMetrics(events).has('f-1')).toBe(false)
  })

  it('reports no metrics for a turn whose counters are all zero', () => {
    const events = [
      running('2026-11-03T10:00:00.000Z'),
      finished('f-1', '2026-11-03T10:00:01.000Z', {
        llmUsage: usage({ inputTokens: 0, outputTokens: 0, totalTokens: 0 }),
      }),
    ]

    expect(buildTurnMetrics(events).has('f-1')).toBe(false)
  })

  it('leaves the duration out when no running event opened the turn', () => {
    const events = [finished('f-1', '2026-11-03T10:00:01.000Z', { llmUsage: usage() })]

    const metrics = buildTurnMetrics(events).get('f-1')!

    expect(metrics.durationMs).toBeUndefined()
    expect(metrics.totalTokens).toBe(150)
  })

  it('refuses a duration too long to be one turn, rather than reporting hours of latency', () => {
    const events = [
      running('2026-11-03T10:00:00.000Z'),
      finished('f-1', '2026-11-04T10:00:00.000Z', { llmUsage: usage() }),
    ]

    expect(buildTurnMetrics(events).get('f-1')!.durationMs).toBeUndefined()
    expect(MAX_TURN_DURATION_MS).toBeLessThan(24 * 60 * 60 * 1000)
  })

  it('does not pair a turn with the running event of the previous one', () => {
    const events = [
      running('2026-11-03T10:00:00.000Z'),
      finished('f-1', '2026-11-03T10:00:01.000Z', { llmUsage: usage() }),
      finished('f-2', '2026-11-03T10:05:00.000Z', { llmUsage: usage() }),
    ]

    const metrics = buildTurnMetrics(events)

    expect(metrics.get('f-1')!.durationMs).toBe(1000)
    expect(metrics.get('f-2')!.durationMs).toBeUndefined()
  })
})

describe('formatTurnDuration', () => {
  it.each([
    [820, '820ms'],
    [999, '999ms'],
    [1000, '1.0s'],
    [3450, '3.5s'],
    [59_900, '59.9s'],
    [60_000, '1m'],
    [100_000, '1m 40s'],
    [3_600_000, '1h'],
    [3_900_000, '1h 5m'],
  ])('reads %sms as %s', (ms, expected) => {
    expect(formatTurnDuration(ms)).toBe(expected)
  })

  it('never prints sixty seconds inside a minute', () => {
    // The remainder of 119 500ms rounds to 60 on its own: two minutes, not "1m 60s".
    expect(formatTurnDuration(119_500)).toBe('2m')
    expect(formatTurnDuration(179_900)).toBe('3m')
  })

  it('has no gap where a value reads as both seconds and a minute', () => {
    expect(formatTurnDuration(59_400)).toBe('59.4s')
    expect(formatTurnDuration(59_950)).toBe('1m')
    expect(formatTurnDuration(60_000)).toBe('1m')
  })
})
