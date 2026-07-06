import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { AgentConfig, AgentConfigControllerService } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { BehaviorSubject, map, switchMap } from 'rxjs'
import { AgentConfigItemComponent } from '../agent-config-item/agent-config-item.component'

/**
 * PlatformAgentConfigsComponent — list view for platform-level agent configs.
 *
 * Loaded at /agentos/admin/agent-configs. Accessible to super-admins only
 * (backend enforces via 403; frontend shows the link only when user.isAdmin).
 *
 * Platform configs have no namespaceId — they are shared across all namespaces.
 * The integrations section is not available at this level (namespace-scoped resource).
 */
@Component({
  selector: 'agentos-platform-agent-configs',
  imports: [AsyncPipe, EntityListComponent, AgentConfigItemComponent],
  templateUrl: './platform-agent-configs.component.html',
  styleUrl: './platform-agent-configs.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformAgentConfigsComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly agentConfigController = inject(AgentConfigControllerService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Raw platform configs, kept for delete lookups. */
  private readonly configs$ = this.refresh$.pipe(
    switchMap(() => this.agentConfigController.listPlatformAgentsAgentConfig(true))
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
    this.configs$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((configs) => {
      this.configsById = new Map(configs.map((c: AgentConfig) => [c.id ?? '', c]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'admin'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', 'admin', 'agent-configs', 'new'])
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
