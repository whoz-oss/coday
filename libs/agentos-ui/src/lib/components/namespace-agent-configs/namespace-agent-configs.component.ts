import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfig, AgentConfigControllerService } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { BehaviorSubject, forkJoin, map, switchMap } from 'rxjs'
import { AgentConfigItemComponent } from '../agent-config-item/agent-config-item.component'

const GROUP_NAMESPACE = 'namespace'
const GROUP_PLATFORM = 'platform'

/**
 * NamespaceAgentConfigsComponent — list view for agent configs of a namespace.
 *
 * Loaded at /:namespaceId/agent-configs. Responsibilities:
 * - Load and display namespace-level AND platform-level agent configs
 * - Merge both levels into a grouped ds-entity-list (namespace / platform)
 * - Platform-level configs are displayed read-only (no edit/delete actions)
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

  /**
   * Fetches both namespace-level and platform-level configs in parallel.
   * Platform configs are appended with a groupKey so ds-entity-list renders them
   * in a separate collapsed-by-default group.
   */
  private readonly allConfigs$ = this.refresh$.pipe(
    switchMap(() =>
      forkJoin({
        namespace: this.agentConfigController.listByNamespaceAgentConfig(this.namespaceId),
        platform: this.agentConfigController.listPlatformAgentsAgentConfig(),
      })
    )
  )

  /** Mapped to EntityListItem[] for ds-entity-list, grouped by level. */
  protected readonly configItems$ = this.allConfigs$.pipe(
    map(({ namespace, platform }) => [
      ...platform.map(
        (c: AgentConfig): EntityListItem => ({
          id: c.id ?? '',
          name: c.name,
          description: c.description,
          groupKey: GROUP_PLATFORM,
          groupLabel: 'Platform (read-only)',
        })
      ),
      ...namespace.map(
        (c: AgentConfig): EntityListItem => ({
          id: c.id ?? '',
          name: c.name,
          description: c.description,
          groupKey: GROUP_NAMESPACE,
          groupLabel: 'Namespace',
        })
      ),
    ])
  )

  /** Full config objects indexed by id — used to resolve itemTemplate events. */
  private namespaceConfigsById = new Map<string, AgentConfig>()
  private platformConfigsById = new Map<string, AgentConfig>()

  constructor() {
    this.allConfigs$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(({ namespace, platform }) => {
      this.namespaceConfigsById = new Map(namespace.map((c: AgentConfig) => [c.id ?? '', c]))
      this.platformConfigsById = new Map(platform.map((c: AgentConfig) => [c.id ?? '', c]))
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
    return this.namespaceConfigsById.get(id) ?? this.platformConfigsById.get(id) ?? null
  }

  protected isPlatformConfig(id: string): boolean {
    return this.platformConfigsById.has(id)
  }
}
