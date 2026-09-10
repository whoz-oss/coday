import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core'
import { FactoryForgeOracleResult, FactoryForgeRun } from '../../services/factory-api.service'
import { FactoryForgeStateService } from '../../services/factory-forge-state.service'

@Component({
  selector: 'agentos-factory-forge-runs',
  templateUrl: './factory-forge-runs.component.html',
  styleUrl: './factory-forge-runs.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FactoryForgeRunsComponent implements OnInit {
  protected readonly state = inject(FactoryForgeStateService)

  ngOnInit(): void {
    this.state.load()
  }

  protected refresh(): void {
    this.state.load()
  }

  protected trackRun(_: number, run: FactoryForgeRun): string {
    return run.runId
  }

  protected trackStory(_: number, story: FactoryForgeRun['stories'][number]): string {
    return story.runId
  }

  protected trackOracle(_: number, oracle: FactoryForgeOracleResult): string {
    return `${oracle.name}-${oracle.commandHash ?? ''}`
  }

  protected field(value: unknown): string {
    if (value == null || value === '') return '—'
    if (typeof value === 'object') {
      const structured = value as { path?: unknown; sha256?: unknown; status?: unknown; code?: unknown }
      return (
        [structured.status, structured.code, structured.path, structured.sha256]
          .filter(Boolean)
          .map(String)
          .join(' · ') || '—'
      )
    }
    return String(value)
  }

  protected list(value: unknown): string {
    return Array.isArray(value) && value.length > 0 ? value.map(String).join(', ') : '—'
  }

  protected date(value: string): string {
    return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
  }

  protected duration(value: number | undefined): string {
    if (value == null) return '—'
    return value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(1)} s`
  }
}
