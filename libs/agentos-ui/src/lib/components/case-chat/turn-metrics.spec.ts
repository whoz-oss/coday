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

  it('stops the running total at the first unpriced turn instead of skipping it silently', () => {
    const events = [
      running('2026-11-03T10:00:00.000Z'),
      finished('f-1', '2026-11-03T10:00:01.000Z', { llmUsage: usage({ estimatedCostUsd: 0.1 }) }),
      running('2026-11-03T10:01:00.000Z'),
      finished('f-2', '2026-11-03T10:01:01.000Z', { llmUsage: usage() }),
      running('2026-11-03T10:02:00.000Z'),
      finished('f-3', '2026-11-03T10:02:01.000Z', { llmUsage: usage({ estimatedCostUsd: 0.3 }) }),
    ]

    const metrics = buildTurnMetrics(events)

    expect(metrics.get('f-1')!.cumulativeCost).toBeCloseTo(0.1)
    expect(metrics.get('f-2')!.cumulativeCost).toBeUndefined()
    expect(metrics.get('f-2')!.cost).toBeUndefined()
    // Once a turn is unknown, no later total can claim to be complete.
    expect(metrics.get('f-3')!.cumulativeCost).toBeUndefined()
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
    [3_600_000, '60m'],
  ])('reads %sms as %s', (ms, expected) => {
    expect(formatTurnDuration(ms)).toBe(expected)
  })
})
