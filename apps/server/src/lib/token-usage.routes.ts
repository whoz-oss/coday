import express from 'express'
import { CodayLogger } from '@coday/model'
import { debugLog } from './log'

interface AgentUsageEntry {
  type: string
  timestamp: string
  username: string
  agent: string
  model: string
  cost?: number
  providerName?: string
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number
}

interface ModelAggregate {
  agentName: string
  providerName: string
  modelId: string
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  callCount: number
  cost: number
  tokenDataPartial: boolean
}

/**
 * Add a nullable token value into an accumulator, tracking whether any value was missing.
 * Returns the new accumulated value (null if all contributions so far are null).
 */
function addTokens(acc: number | null, incoming: number | undefined): number | null {
  if (incoming === undefined) return acc // no contribution: keep existing acc (null or sum)
  return (acc ?? 0) + incoming
}

interface SeriesEntry extends ModelAggregate {
  date: string
}

/**
 * Parse a yyyy-MM-dd query param into a Date.
 * startOfDay=true  => T00:00:00.000Z
 * startOfDay=false => T23:59:59.999Z
 * Returns undefined when the param is absent or malformed.
 */
function parseDate(value: unknown, startOfDay: boolean): Date | undefined {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return undefined
  const time = startOfDay ? '00:00:00.000Z' : '23:59:59.999Z'
  const d = new Date(`${value}T${time}`)
  return isNaN(d.getTime()) ? undefined : d
}

/**
 * Build a composite key for grouping usage entries.
 */
function groupKey(agent: string, provider: string, model: string): string {
  return `${agent}|${provider}|${model}`
}

/**
 * Register token usage reporting routes.
 *
 * GET /api/token-usage?from=yyyy-MM-dd&to=yyyy-MM-dd
 *   Returns aggregated totals per (agent, provider, model) plus a grand total.
 *
 * GET /api/token-usage/series?from=yyyy-MM-dd&to=yyyy-MM-dd
 *   Returns per-day, per-(agent, provider, model) rows.
 */
export function registerTokenUsageRoutes(
  app: express.Application,
  logger: CodayLogger,
  getUsernameFn: (req: express.Request) => string
): void {
  /**
   * GET /api/token-usage
   */
  app.get('/api/token-usage', async (req: express.Request, res: express.Response) => {
    try {
      const from = parseDate(req.query['from'], true) ?? new Date(0)
      const to = parseDate(req.query['to'], false) ?? new Date()
      const username = getUsernameFn(req)

      debugLog('TOKEN_USAGE', `GET /api/token-usage from=${from.toISOString()} to=${to.toISOString()} user=${username}`)

      const allEntries = await logger.readLogs(from, to)
      const entries: AgentUsageEntry[] = allEntries.filter(
        (e) => e.type === 'AGENT_USAGE' && (e.username === username || e.username === 'no_username')
      )

      const map = new Map<string, ModelAggregate>()

      for (const e of entries) {
        const provider = e.providerName ?? ''
        const key = groupKey(e.agent, provider, e.model)
        let agg = map.get(key)
        if (!agg) {
          agg = {
            agentName: e.agent,
            providerName: provider,
            modelId: e.model,
            promptTokens: null,
            completionTokens: null,
            totalTokens: null,
            callCount: 0,
            cost: 0,
            tokenDataPartial: false,
          }
          map.set(key, agg)
        }
        agg.promptTokens = addTokens(agg.promptTokens, e.promptTokens)
        agg.completionTokens = addTokens(agg.completionTokens, e.completionTokens)
        agg.totalTokens = addTokens(agg.totalTokens, e.totalTokens)
        agg.callCount += 1
        agg.cost += e.cost ?? 0
        if (e.promptTokens === undefined || e.completionTokens === undefined || e.totalTokens === undefined) {
          agg.tokenDataPartial = true
        }
      }

      const models = Array.from(map.values())
      const tokenDataPartial = models.some((m) => m.tokenDataPartial)

      const sumOrNull = (vals: (number | null)[]): number | null => {
        const known = vals.filter((v): v is number => v !== null)
        return known.length > 0 ? known.reduce((s, v) => s + v, 0) : null
      }

      const total = {
        agentName: 'total',
        providerName: 'all',
        modelId: 'all',
        promptTokens: sumOrNull(models.map((m) => m.promptTokens)),
        completionTokens: sumOrNull(models.map((m) => m.completionTokens)),
        totalTokens: sumOrNull(models.map((m) => m.totalTokens)),
        callCount: models.reduce((s, m) => s + m.callCount, 0),
        cost: models.reduce((s, m) => s + m.cost, 0),
        tokenDataPartial,
      }

      res.status(200).json({
        from: from.toISOString(),
        to: to.toISOString(),
        tokenDataPartial,
        models,
        total,
      })
    } catch (error) {
      console.error('Error reading token usage:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to read token usage: ${errorMessage}` })
    }
  })

  /**
   * GET /api/token-usage/series
   */
  app.get('/api/token-usage/series', async (req: express.Request, res: express.Response) => {
    try {
      const from = parseDate(req.query['from'], true) ?? new Date(0)
      const to = parseDate(req.query['to'], false) ?? new Date()
      const username = getUsernameFn(req)

      debugLog(
        'TOKEN_USAGE',
        `GET /api/token-usage/series from=${from.toISOString()} to=${to.toISOString()} user=${username}`
      )

      const allEntries = await logger.readLogs(from, to)
      const entries: AgentUsageEntry[] = allEntries.filter(
        (e) => e.type === 'AGENT_USAGE' && (e.username === username || e.username === 'no_username')
      )

      // Group by date + (agent, provider, model)
      const map = new Map<string, SeriesEntry>()

      for (const e of entries) {
        const date = e.timestamp.slice(0, 10) // yyyy-MM-dd
        const provider = e.providerName ?? ''
        const key = `${date}|${groupKey(e.agent, provider, e.model)}`
        let row = map.get(key)
        if (!row) {
          row = {
            date,
            agentName: e.agent,
            providerName: provider,
            modelId: e.model,
            promptTokens: null,
            completionTokens: null,
            totalTokens: null,
            callCount: 0,
            cost: 0,
            tokenDataPartial: false,
          }
          map.set(key, row)
        }
        row.promptTokens = addTokens(row.promptTokens, e.promptTokens)
        row.completionTokens = addTokens(row.completionTokens, e.completionTokens)
        row.totalTokens = addTokens(row.totalTokens, e.totalTokens)
        row.callCount += 1
        row.cost += e.cost ?? 0
        if (e.promptTokens === undefined || e.completionTokens === undefined || e.totalTokens === undefined) {
          row.tokenDataPartial = true
        }
      }

      // Sort by date ascending, then agentName
      const series = Array.from(map.values()).sort((a, b) => {
        const d = a.date.localeCompare(b.date)
        return d !== 0 ? d : a.agentName.localeCompare(b.agentName)
      })

      const tokenDataPartial = series.some((s) => s.tokenDataPartial)

      res.status(200).json({
        from: from.toISOString(),
        to: to.toISOString(),
        tokenDataPartial,
        points: series,
      })
    } catch (error) {
      console.error('Error reading token usage series:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to read token usage series: ${errorMessage}` })
    }
  })
}
