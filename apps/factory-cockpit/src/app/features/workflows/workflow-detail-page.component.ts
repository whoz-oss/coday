import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import { ActivatedRoute, RouterLink } from '@angular/router'
import { FactoryApiError, FactoryApiService, FullWorkflowDefinition } from '../../core/factory-api.service'
import { ShellState } from '../../core/shell-state'

/**
 * Formats an arbitrary value as an indented JSON string.
 * Falls back to the raw string representation when serialization fails.
 */
export function formatJson(value: unknown): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

/**
 * Detail page for a single workflow definition.
 *
 * Loads the full definition via {@link FactoryApiService} using the
 * `workflowType` and `version` route params, then renders it as formatted
 * JSON. The "Back" button always navigates to the definitions list
 * (`/workflows`) regardless of how the page was reached (direct link or
 * in-app navigation).
 */
@Component({
  selector: 'sf-workflow-detail-page',
  imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule, RouterLink],
  templateUrl: './workflow-detail-page.component.html',
  styleUrl: './workflow-detail-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WorkflowDetailPageComponent {
  private readonly api = inject(FactoryApiService)
  private readonly route = inject(ActivatedRoute)
  private readonly shell = inject(ShellState)

  /** Absolute link back to the definitions list -- always safe, even on direct access. */
  protected readonly backLink = ['/workflows']

  protected readonly loading = signal(false)
  protected readonly error = signal<string | null>(null)
  protected readonly definition = signal<FullWorkflowDefinition | null>(null)

  protected readonly workflowType: string
  protected readonly version: string

  protected readonly formatJson = formatJson

  constructor() {
    const params = this.route.snapshot.paramMap
    this.workflowType = params.get('type') ?? ''
    this.version = params.get('version') ?? ''

    this.shell.crumbs.set([
      { label: 'Workflows', link: '/workflows' },
      { label: `${this.workflowType}@${this.version}`, mono: true },
    ])

    this.load()
  }

  private load(): void {
    if (!this.workflowType || !this.version) {
      this.error.set('Missing route parameters (type or version).')
      return
    }
    this.loading.set(true)
    this.error.set(null)
    this.api.getWorkflowDefinition(this.workflowType, this.version).subscribe({
      next: (full) => {
        this.definition.set(full)
        this.loading.set(false)
      },
      error: (err: FactoryApiError) => {
        this.error.set(err?.message?.trim() || 'Unable to load workflow definition.')
        this.loading.set(false)
      },
    })
  }
}
