import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfig, AgentConfigControllerService } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { BehaviorSubject, map, switchMap } from 'rxjs'
import { AgentConfigItemComponent } from '../agent-config-item/agent-config-item.component'

/**
 * NamespaceAgentConfigsComponent — list view for agent configs of a namespace.
 *
 * Loaded at /:namespaceId/agent-configs. Responsibilities:
 * - Load and display the list of AgentConfig via ds-entity-list
 * - Navigate back to the namespace list
 * - Navigate to the create form (/:namespaceId/agent-configs/new)
 * - Deletion with inline confirmation (delegated to AgentConfigItemComponent)
 *
 * Create and edit are handled by AgentConfigFormComponent on dedicated routes.
 */
@Component({
  selector: 'agentos-namespace-agent-configs',
  imports: [AsyncPipe, EntityListComponent, AgentConfigItemComponent],
  templateUrl: './namespace-agent-configs.component.html',
  styleUrl: './namespace-agent-configs.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceAgentConfigsComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly agentConfigController = inject(AgentConfigControllerService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Raw configs, kept for delete lookups. */
  private readonly configs$ = this.refresh$.pipe(
    switchMap(() => this.agentConfigController.listByNamespaceAgentConfig(this.namespaceId))
  )

  /** Mapped to EntityListItem[] for ds-entity-list. */
  protected readonly configItems$ = this.configs$.pipe(
    map((configs) =>
      configs.map(
        (c: AgentConfig): EntityListItem => ({
          id: c.id ?? '',
          name: c.name,
          description: c.description,
        })
      )
    )
  )

  /** Full config objects indexed by id — used to resolve itemTemplate events. */
  private configsById = new Map<string, AgentConfig>()

  constructor() {
    this.configs$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((configs: AgentConfig[]) => {
      this.configsById = new Map(configs.map((c: AgentConfig) => [c.id ?? '', c]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'agent-configs', 'new'])
  }

  protected deleteConfig(config: AgentConfig): void {
    this.agentConfigController
      .deleteAgentConfig(config.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected resolveConfig(id: string): AgentConfig | null {
    return this.configsById.get(id) ?? null
  }
}
