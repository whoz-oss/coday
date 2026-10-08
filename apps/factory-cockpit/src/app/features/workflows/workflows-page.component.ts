import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core'
import { RouterLink } from '@angular/router'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import { FactoryApiError, FactoryApiService, WorkflowDefinition } from '../../core/factory-api.service'
import { ShellState } from '../../core/shell-state'

/**
 * Defensive normalization of the `GET /api/factory/workflow-definitions`
 * payload. The registry may answer with a raw array, an `{ items }` object or a
 * nested `{ data: … }` envelope; anything unexpected degrades to `[]` (never a
 * fabricated definition).
 */
export function extractWorkflowDefinitions(payload: unknown): WorkflowDefinition[] {
  if (Array.isArray(payload)) return payload as WorkflowDefinition[]
  const obj = (typeof payload === 'object' && payload !== null ? payload : {}) as {
    items?: unknown
    data?: unknown
  }
  if (Array.isArray(obj.items)) return obj.items as WorkflowDefinition[]
  const nested = obj.data
  if (Array.isArray(nested)) return nested as WorkflowDefinition[]
  const nestedItems = (typeof nested === 'object' && nested !== null ? nested : {}) as { items?: unknown }
  return Array.isArray(nestedItems.items) ? (nestedItems.items as WorkflowDefinition[]) : []
}

/**
 * Read-only list of registered workflow definitions.
 *
 * Each card is a router link navigating to the detail page
 * (`/workflows/:type/:version`) where the full definition JSON is displayed.
 * There is no in-place expansion: the list stays flat and stable.
 */
@Component({
  selector: 'sf-workflows-page',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './workflows-page.component.html',
  styleUrl: './workflows-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WorkflowsPageComponent {
  private readonly api = inject(FactoryApiService)

  protected readonly definitions = signal<WorkflowDefinition[]>([])
  protected readonly loading = signal(false)
  protected readonly error = signal<string | null>(null)

  constructor() {
    inject(ShellState).crumbs.set([{ label: 'Workflows' }])
    this.load()
  }

  /** Load the registry of workflow definitions. */
  protected load(): void {
    this.loading.set(true)
    this.error.set(null)
    this.api.getWorkflowDefinitions().subscribe({
      next: (payload) => {
        this.definitions.set(extractWorkflowDefinitions(payload))
        this.loading.set(false)
      },
      error: (err: FactoryApiError) => {
        this.error.set(err?.message?.trim() || 'Unable to load workflow definitions.')
        this.definitions.set([])
        this.loading.set(false)
      },
    })
  }

  protected definitionKey(definition: WorkflowDefinition): string {
    return `${definition.workflowType ?? ''}@${definition.version ?? ''}`
  }

  protected definitionTitle(definition: WorkflowDefinition): string {
    const title = typeof definition['title'] === 'string' ? definition['title'].trim() : ''
    const type = typeof definition.workflowType === 'string' ? definition.workflowType.trim() : ''
    return title || type || 'Definition without type'
  }

  /** Short, display-only rendering of the definition hash. */
  protected shortHash(definition: WorkflowDefinition): string {
    const hash = typeof definition.definitionHash === 'string' ? definition.definitionHash.trim() : ''
    if (!hash) return '—'
    return hash.length > 12 ? `${hash.slice(0, 12)}…` : hash
  }

  /** Router link segments for the detail page of a definition. */
  protected detailLink(definition: WorkflowDefinition): string[] {
    // Angular's RouterLink encodes each segment automatically -- raw values only.
    return ['/workflows', String(definition.workflowType ?? ''), String(definition.version ?? '')]
  }
}
