import express from 'express'
import { CodayLogger } from '@coday/model'
import { debugLog } from './log'

interface AgentUsageEntry {
  type: string
  timestamp: string
  username: string
  agent: string
  model: string
  cost: number
  providerName?: string
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number
}

interface ModelAggregate {
  modelName: string
  providerName: string
  modelId: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  callCount: number
  cost: number
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
            modelName: e.agent,
            providerName: provider,
            modelId: e.model,
            promptTokens: 0,
            completionTokens: 0,
            totalTokens: 0,
            callCount: 0,
            cost: 0,
          }
          map.set(key, agg)
        }
        agg.promptTokens += e.promptTokens ?? 0
        agg.completionTokens += e.completionTokens ?? 0
        agg.totalTokens += e.totalTokens ?? 0
        agg.callCount += 1
        agg.cost += e.cost ?? 0
      }

      const models = Array.from(map.values())

      const total = {
        modelName: 'total',
        providerName: 'all',
        modelId: 'all',
        promptTokens: models.reduce((s, m) => s + m.promptTokens, 0),
        completionTokens: models.reduce((s, m) => s + m.completionTokens, 0),
        totalTokens: models.reduce((s, m) => s + m.totalTokens, 0),
        callCount: models.reduce((s, m) => s + m.callCount, 0),
        cost: models.reduce((s, m) => s + m.cost, 0),
      }

      res.status(200).json({
        from: from.toISOString(),
        to: to.toISOString(),
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
            modelName: e.agent,
            providerName: provider,
            modelId: e.model,
            promptTokens: 0,
            completionTokens: 0,
            totalTokens: 0,
            callCount: 0,
            cost: 0,
          }
          map.set(key, row)
        }
        row.promptTokens += e.promptTokens ?? 0
        row.completionTokens += e.completionTokens ?? 0
        row.totalTokens += e.totalTokens ?? 0
        row.callCount += 1
        row.cost += e.cost ?? 0
      }

      // Sort by date ascending, then modelName
      const series = Array.from(map.values()).sort((a, b) => {
        const d = a.date.localeCompare(b.date)
        return d !== 0 ? d : a.modelName.localeCompare(b.modelName)
      })

      res.status(200).json(series)
    } catch (error) {
      console.error('Error reading token usage series:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      res.status(500).json({ error: `Failed to read token usage series: ${errorMessage}` })
    }
  })
}
