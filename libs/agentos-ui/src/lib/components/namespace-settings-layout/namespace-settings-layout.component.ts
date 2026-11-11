import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, RouterLink, RouterOutlet } from '@angular/router'
import { IntegrationTypeControllerService, NamespaceControllerService } from '@whoz-oss/agentos-api-client'
import { NamespaceStateService } from '@whoz-oss/agentos-dataflow'
import { ShellContextService } from '../../services/shell-context.service'
import { SettingsLayoutComponent, SettingsNavigationGroup } from '../settings-layout/settings-layout.component'

@Component({
  selector: 'agentos-namespace-settings-layout',
  imports: [RouterLink, RouterOutlet, SettingsLayoutComponent],
  templateUrl: './namespace-settings-layout.component.html',
  styleUrl: './namespace-settings-layout.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceSettingsLayoutComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly namespaceApi = inject(NamespaceControllerService)
  private readonly integrationTypeApi = inject(IntegrationTypeControllerService)
  private readonly namespaceState = inject(NamespaceStateService)
  private readonly shellContext = inject(ShellContextService)
  private readonly destroyRef = inject(DestroyRef)

  protected readonly namespaceId = this.route.snapshot.paramMap.get('namespaceId') ?? ''
  protected readonly namespaceName = signal('Namespace')
  protected readonly gitAvailable = signal(false)

  protected readonly groups: readonly SettingsNavigationGroup[] = [
    { label: 'General', items: [{ label: 'Edit namespace', commands: ['edit'] }] },
    {
      label: 'Access',
      items: [
        { label: 'Members', commands: ['members'] },
        { label: 'User groups', commands: ['user-groups'] },
        { label: 'Auth settings', commands: ['auth-settings'] },
      ],
    },
    {
      label: 'Connections',
      items: [
        { label: 'Integrations', commands: ['integrations'] },
        { label: 'Git', commands: ['git'], hidden: !this.gitAvailable() },
      ],
    },
    {
      label: 'AI',
      items: [
        { label: 'AI providers', commands: ['ai-providers'] },
        { label: 'AI models', commands: ['ai-models'] },
        { label: 'Agent configs', commands: ['agent-configs'] },
        { label: 'Prompts', commands: ['prompts'] },
        { label: 'Skills', commands: ['skills'] },
      ],
    },
    {
      label: 'Automation',
      items: [
        { label: 'Schedulers', commands: ['scheduled-prompts'] },
        { label: 'Tool invoke', commands: ['tool-invoke'] },
      ],
    },
    { label: 'Monitoring', items: [{ label: 'Usage and costs', commands: ['usage'] }] },
  ]

  protected navigationGroups(): readonly SettingsNavigationGroup[] {
    return this.groups.map((group) => ({
      ...group,
      items: group.items.map((item) => (item.label === 'Git' ? { ...item, hidden: !this.gitAvailable() } : item)),
    }))
  }

  constructor() {
    this.integrationTypeApi
      .listTypesIntegrationType()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (types) => this.gitAvailable.set(types.some((type) => type.type === 'GIT')) })

    if (!this.namespaceId) return

    // Configuring a namespace makes it the active one: the topbar then points its "Namespace settings" and
    // "Back to <namespace>" links at it, wherever the user goes next.
    this.namespaceState.selectNamespace(this.namespaceId)

    this.namespaceApi
      .getByIdNamespace(this.namespaceId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (namespace) => {
          this.namespaceName.set(namespace.name)
          this.shellContext.rememberNamespaceNames([namespace])
        },
      })
  }
}
