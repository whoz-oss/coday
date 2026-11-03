import { AgentFinishedEvent, CaseEvent, LlmUsage } from '@whoz-oss/agentos-api-client'
// The same compact form the usage panel uses: one rule for token counts, not two.
export { formatTokenCount } from '../../pipes/token-count.pipe'

/**
 * What one agent turn cost, shown under the answer it produced.
 *
 * The figures were already travelling on [AgentFinishedEvent]: the service enriches it with
 * `usageAccumulator.total`, the usage of every LLM call of that turn. Until now the front rendered
 * that event as a collapsed "Agent finished" log line, so the only costs on screen were the run and
 * conversation totals in the usage panel — which answer "how much so far", never "how much was
 * that answer".
 */
export interface TurnMetrics {
  /** Tokens the turn reported, every call and every counter included. */
  totalTokens: number
  /** Estimated cost of this turn, or undefined when the model has no pricing configured. */
  cost?: number
  /** Conversation cost up to and including this turn, undefined as soon as one turn is unpriced. */
  cumulativeCost?: number
  /** Wall-clock time from the agent starting to it finishing. */
  durationMs?: number
  llmModel?: string
  llmProvider?: string
}

/**
 * Longest duration still read as one turn's latency: anything above is treated as unknown.
 *
 * A turn is bounded by its `AgentRunningEvent` and its `AgentFinishedEvent`, and nothing guarantees
 * the pair belongs together — a run killed without a finished event, a case resumed the next day,
 * or events arriving out of order all produce a difference that is real arithmetic and nonsense as
 * a latency. Reporting "14 h" under an answer would discredit the figures beside it.
 */
export const MAX_TURN_DURATION_MS = 6 * 60 * 60 * 1000

/** Zero on every counter: a turn that reported nothing worth showing. */
function reportsNothing(usage: LlmUsage): boolean {
  return (
    usage.totalTokens === 0 &&
    usage.inputTokens === 0 &&
    usage.outputTokens === 0 &&
    usage.cacheReadTokens === 0 &&
    usage.cacheWriteTokens === 0 &&
    !usage.estimatedCostUsd
  )
}

/**
 * The metrics of every agent turn in [events], keyed by the id of its `AgentFinishedEvent`.
 *
 * Returns nothing for a turn whose event carries no usage: usage tracking can be off
 * (`agentos.usage.enabled`), and a turn interrupted before its first LLM call reports zeros. An
 * absent entry is what lets the caller render no footer at all rather than a row of zeros that
 * would read as a free answer.
 *
 * [TurnMetrics.cumulativeCost] stops being produced as soon as one turn is unpriced, for the same
 * reason the service keeps `unknownCostCount` apart from its sums: a total that silently skipped a
 * turn is indistinguishable from a complete one.
 */
export function buildTurnMetrics(events: CaseEvent[]): Map<string, TurnMetrics> {
  const metrics = new Map<string, TurnMetrics>()
  let runningAt: number | undefined
  let cumulative: number | undefined = 0

  for (const event of events) {
    if (event.type === 'AgentRunningEvent') {
      runningAt = Date.parse(event.timestamp)
      continue
    }
    if (event.type !== 'AgentFinishedEvent') continue

    const finished = event as AgentFinishedEvent
    const startedAt = runningAt
    runningAt = undefined

    const usage = finished.llmUsage
    if (!usage || reportsNothing(usage)) continue

    const cost = usage.estimatedCostUsd
    cumulative = cumulative !== undefined && cost !== undefined ? cumulative + cost : undefined

    metrics.set(finished.id, {
      totalTokens: usage.totalTokens,
      cost,
      cumulativeCost: cumulative,
      durationMs: turnDuration(startedAt, finished.timestamp),
      llmModel: finished.llmModel,
      llmProvider: finished.llmProvider,
    })
  }

  return metrics
}

/** The pair's elapsed time, or undefined when it cannot be read as this turn's latency. */
function turnDuration(startedAt: number | undefined, finishedAt: string): number | undefined {
  if (startedAt === undefined || Number.isNaN(startedAt)) return undefined
  const end = Date.parse(finishedAt)
  if (Number.isNaN(end)) return undefined
  const elapsed = end - startedAt
  return elapsed >= 0 && elapsed <= MAX_TURN_DURATION_MS ? elapsed : undefined
}

/**
 * A duration in the shortest form that still carries the information: `820ms`, `3.4s`, `1m 40s`.
 *
 * Seconds get one decimal below a minute because that is the range a reader compares answers in,
 * and lose it above, where a tenth of a second is noise next to the minutes.
 */
export function formatTurnDuration(durationMs: number): string {
  if (durationMs < 1000) return `${Math.round(durationMs)}ms`
  if (durationMs < 60_000) return `${(durationMs / 1000).toFixed(1)}s`
  const minutes = Math.floor(durationMs / 60_000)
  const seconds = Math.round((durationMs % 60_000) / 1000)
  return seconds === 0 ? `${minutes}m` : `${minutes}m ${seconds}s`
}
