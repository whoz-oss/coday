import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core'
import { RouterLink } from '@angular/router'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import { WorkflowDefinition } from '../../core/factory-api.service'
import { ShellState } from '../../core/shell-state'
import { WorkflowDefinitionsStateService } from '../../core/workflow-definitions-state.service'

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
  providers: [WorkflowDefinitionsStateService],
})
export class WorkflowsPageComponent {
  private readonly state = inject(WorkflowDefinitionsStateService)

  protected readonly definitions = this.state.definitions
  protected readonly loading = this.state.loading
  protected readonly error = computed(
    () => this.state.error()?.message?.trim() || (this.state.error() ? 'Unable to load workflow definitions.' : null)
  )

  constructor() {
    inject(ShellState).crumbs.set([{ label: 'Workflows' }])
    this.load()
  }

  /** Load the registry of workflow definitions. */
  protected load(): void {
    this.state.load()
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
