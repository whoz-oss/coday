import { CommandContext, CommandHandler, parseArgs } from '@coday/handler'
import { Interactor } from '@coday/model/interactor'
import { CodayServices } from '@coday/coday-services'

interface AgentStats {
  agent: string
  calls: number
  totalCost: number
  avgCost: number
}

export class StatsAgentsHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'agents',
      description:
        'Show agent usage statistics sorted by number of calls. Use --from=YYYY-MM-DD and --to=YYYY-MM-DD for custom date range (default: last 7 days)',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    try {
      // Parse date arguments
      const subCommand = this.getSubCommand(command)
      const args = parseArgs(subCommand, [
        { key: 'from', alias: 'f' },
        { key: 'to', alias: 't' },
      ])

      // Set date range
      let to: Date
      let from: Date

      // Parse TO date first
      if (args.to && typeof args.to === 'string') {
        const parsedTo = this.parseDate(args.to)
        if (!parsedTo) {
          this.interactor.error('Invalid --to date format. Please use YYYY-MM-DD')
          return context
        }
        to = parsedTo
        // Set to end of day
        to.setHours(23, 59, 59, 999)
      } else {
        // Default: end of today
        to = new Date()
        to.setHours(23, 59, 59, 999)
      }

      // Parse FROM date
      if (args.from && typeof args.from === 'string') {
        const parsedFrom = this.parseDate(args.from)
        if (!parsedFrom) {
          this.interactor.error('Invalid --from date format. Please use YYYY-MM-DD')
          return context
        }
        from = parsedFrom
        // Set to start of day
        from.setHours(0, 0, 0, 0)
      } else {
        // Default: 7 days before the TO date (at start of day)
        from = new Date(to)
        from.setDate(from.getDate() - 7)
        from.setHours(0, 0, 0, 0)
      }

      // Validate date range
      if (from > to) {
        this.interactor.error('Invalid date range: --from date must be before --to date')
        return context
      }

      const logs = (await this.services.logger.readLogs(from, to)).filter(
        (log) => !log.type || log.type === 'AGENT_USAGE'
      )

      if (logs.length === 0) {
        const dateRange = this.formatDateRange(from, to)
        this.interactor.displayText(`📊 No usage data found for ${dateRange}`)
        return context
      }

      // Aggregate stats by agent
      const agentStatsMap = new Map<string, { calls: number; totalCost: number }>()

      for (const log of logs) {
        const existing = agentStatsMap.get(log.agent) || { calls: 0, totalCost: 0 }
        agentStatsMap.set(log.agent, {
          calls: existing.calls + 1,
          totalCost: existing.totalCost + log.cost,
        })
      }

      // Convert to array and calculate averages
      const agentStats: AgentStats[] = Array.from(agentStatsMap.entries()).map(([agent, stats]) => ({
        agent,
        calls: stats.calls,
        totalCost: stats.totalCost,
        avgCost: stats.totalCost / stats.calls,
      }))

      // Sort by number of calls (most used first)
      agentStats.sort((a, b) => b.calls - a.calls)

      // Format output
      const output = this.formatAgentStats(agentStats, from, to)
      this.interactor.displayText(output)
    } catch (error) {
      this.interactor.error(`Failed to retrieve agent statistics: ${error}`)
    }

    return context
  }

  private parseDate(dateStr: string): Date | null {
    const match = dateStr.match(/^(\d{4})-(\d{2})-(\d{2})$/)
    if (!match) return null

    const date = new Date(dateStr)
    // Check if date is valid
    if (isNaN(date.getTime())) return null

    return date
  }

  private formatDateRange(from: Date, to: Date): string {
    const fromStr = from.toISOString().split('T')[0]
    const toStr = to.toISOString().split('T')[0]
    return `${fromStr} to ${toStr}`
  }

  private formatAgentStats(stats: AgentStats[], from: Date, to: Date): string {
    const dateRange = this.formatDateRange(from, to)
    const header = `📊 Agent Usage Statistics (${dateRange})
=====================================

Agent      | Calls | Total Cost | Avg Cost
-----------|-------|------------|----------`

    const rows = stats.map((stat) => {
      const agent = stat.agent.padEnd(10)
      const calls = stat.calls.toString().padStart(5)
      const totalCost = `$${stat.totalCost.toFixed(2)}`.padStart(10)
      const avgCost = `$${stat.avgCost.toFixed(3)}`.padStart(8)

      return `${agent} | ${calls} | ${totalCost} | ${avgCost}`
    })

    return header + '\n' + rows.join('\n')
  }
}
