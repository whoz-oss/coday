import { CommandContext, CommandHandler, Interactor } from '../../model'
import { CodayServices } from '../../coday-services'
import { parseArgs } from '../parse-args'

interface WebhookStats {
  webhookName: string
  webhookUuid: string
  calls: number
  successCount: number
  errorCount: number
  projects: Set<string>
  lastUsed: Date
}

export class StatsWebhooksHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'webhooks',
      description:
        'Show webhook usage statistics sorted by number of calls. Use --from=YYYY-MM-DD and --to=YYYY-MM-DD for custom date range (default: last 7 days)',
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

      // Read logs and filter for webhook-related entries
      const logs = await this.services.logger.readLogs(from, to)
      const webhookLogs = logs.filter((log) => log.type === 'WEBHOOK' || log.type === 'WEBHOOK_ERROR')

      if (webhookLogs.length === 0) {
        const dateRange = this.formatDateRange(from, to)
        this.interactor.displayText(`📊 No webhook usage data found for ${dateRange}`)
        return context
      }

      // Aggregate stats by webhook
      const webhookStatsMap = new Map<string, WebhookStats>()

      for (const log of webhookLogs) {
        const webhookKey = log.webhookUuid || log.webhookName || 'Unknown'
        const webhookName = log.webhookName || 'Unknown'
        const webhookUuid = log.webhookUuid || 'Unknown'
        
        const existing = webhookStatsMap.get(webhookKey) || {
          webhookName,
          webhookUuid,
          calls: 0,
          successCount: 0,
          errorCount: 0,
          projects: new Set<string>(),
          lastUsed: new Date(log.timestamp)
        }

        // Update stats based on log type
        if (log.type === 'WEBHOOK') {
          existing.calls += 1
          existing.successCount += 1
          if (log.project) {
            existing.projects.add(log.project)
          }
        } else if (log.type === 'WEBHOOK_ERROR') {
          existing.calls += 1
          existing.errorCount += 1
          if (log.project) {
            existing.projects.add(log.project)
          }
        }

        // Update last used date
        const logDate = new Date(log.timestamp)
        if (logDate > existing.lastUsed) {
          existing.lastUsed = logDate
        }

        webhookStatsMap.set(webhookKey, existing)
      }

      // Convert to array for sorting
      const webhookStats = Array.from(webhookStatsMap.values())

      // Sort by number of calls (most used first)
      webhookStats.sort((a, b) => b.calls - a.calls)

      // Format output
      const output = this.formatWebhookStats(webhookStats, from, to)
      this.interactor.displayText(output)
    } catch (error) {
      this.interactor.error(`Failed to retrieve webhook statistics: ${error}`)
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

  private formatWebhookStats(stats: WebhookStats[], from: Date, to: Date): string {
    const dateRange = this.formatDateRange(from, to)
    const header = `📊 Webhook Usage Statistics (${dateRange})
===============================================

Webhook Name                 | Calls | Success | Errors | Projects | Last Used
-----------------------------|-------|---------|--------|----------|----------`

    const rows = stats.map((stat) => {
      const name = stat.webhookName.substring(0, 28).padEnd(28)
      const calls = stat.calls.toString().padStart(5)
      const success = stat.successCount.toString().padStart(7)
      const errors = stat.errorCount.toString().padStart(6)
      const projects = stat.projects.size.toString().padStart(8)
      const lastUsed = stat.lastUsed.toISOString().split('T')[0]?.padStart(10)

      return `${name} | ${calls} | ${success} | ${errors} | ${projects} | ${lastUsed}`
    })

    const footer = `
Summary:
- Total unique webhooks: ${stats.length}
- Total calls: ${stats.reduce((sum, stat) => sum + stat.calls, 0)}
- Total successful calls: ${stats.reduce((sum, stat) => sum + stat.successCount, 0)}
- Total failed calls: ${stats.reduce((sum, stat) => sum + stat.errorCount, 0)}
- Unique projects used: ${new Set(stats.flatMap(stat => Array.from(stat.projects))).size}`

    return header + '\n' + rows.join('\n') + footer
  }
}