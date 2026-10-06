import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MatCardModule } from '@angular/material/card'
import { MatIconModule } from '@angular/material/icon'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import {
  FactoryApiError,
  FactoryApiService,
  FullWorkflowDefinition,
  FullWorkflowStep,
  WorkflowDefinition,
} from '../../core/factory-api.service'
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
 * Read-only browser of the registered workflow definitions.
 *
 * Directly consumes {@link FactoryApiService} (never `FactoryStore`): the list
 * is loaded once on init, and clicking a card lazily loads and renders the full
 * definition (steps, responsibility and dependencies). Every failure is
 * surfaced as a friendly banner instead of crashing the page.
 */
@Component({
  selector: 'sf-workflows-page',
  imports: [MatButtonModule, MatCardModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './workflows-page.component.html',
  styleUrl: './workflows-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WorkflowsPageComponent {
  private readonly api = inject(FactoryApiService)

  protected readonly definitions = signal<WorkflowDefinition[]>([])
  protected readonly loading = signal(false)
  protected readonly error = signal<string | null>(null)

  protected readonly selectedKey = signal<string | null>(null)
  protected readonly detailsMap = signal<Record<string, FullWorkflowDefinition>>({})
  protected readonly detailLoadingMap = signal<Record<string, boolean>>({})
  protected readonly detailErrorMap = signal<Record<string, string | null>>({})

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
        this.error.set(err?.message?.trim() || 'Impossible de charger les définitions de workflow.')
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
    return title || type || 'Définition sans type'
  }

  /** Short, display-only rendering of the definition hash. */
  protected shortHash(definition: WorkflowDefinition): string {
    const hash = typeof definition.definitionHash === 'string' ? definition.definitionHash.trim() : ''
    if (!hash) return '—'
    return hash.length > 12 ? `${hash.slice(0, 12)}…` : hash
  }

  protected isSelected(definition: WorkflowDefinition): boolean {
    return this.selectedKey() === this.definitionKey(definition)
  }

  /**
   * Toggle the selected card and lazily fetch its full definition. A definition
   * is only requested when its detail is not already cached or loading.
   */
  protected selectDefinition(definition: WorkflowDefinition): void {
    const key = this.definitionKey(definition)
    if (this.selectedKey() === key) {
      this.selectedKey.set(null)
      return
    }
    this.selectedKey.set(key)
    if (this.detailsMap()[key] || this.detailLoadingMap()[key]) return

    const workflowType = String(definition.workflowType ?? '').trim()
    const version = String(definition.version ?? '').trim()
    if (!workflowType || !version) {
      this.detailErrorMap.update((map) => ({ ...map, [key]: 'Définition incomplète : type ou version manquant.' }))
      return
    }

    this.detailErrorMap.update((map) => ({ ...map, [key]: null }))
    this.detailLoadingMap.update((map) => ({ ...map, [key]: true }))
    this.api.getWorkflowDefinition(workflowType, version).subscribe({
      next: (full) => {
        this.detailsMap.update((map) => ({ ...map, [key]: full }))
        this.detailLoadingMap.update((map) => ({ ...map, [key]: false }))
      },
      error: (err: FactoryApiError) => {
        this.detailErrorMap.update((map) => ({
          ...map,
          [key]: err?.message?.trim() || 'Impossible de charger la définition complète.',
        }))
        this.detailLoadingMap.update((map) => ({ ...map, [key]: false }))
      },
    })
  }

  protected stepsOf(definition: FullWorkflowDefinition): FullWorkflowStep[] {
    return Array.isArray(definition.steps) ? definition.steps : []
  }
}
