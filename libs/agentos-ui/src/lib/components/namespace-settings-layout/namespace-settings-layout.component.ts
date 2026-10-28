import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router'
import { IntegrationTypeControllerService, NamespaceControllerService } from '@whoz-oss/agentos-api-client'

interface NamespaceSettingsItem {
  readonly label: string
  readonly path: string
}

interface NamespaceSettingsGroup {
  readonly label: string
  readonly items: readonly NamespaceSettingsItem[]
}

@Component({
  selector: 'agentos-namespace-settings-layout',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './namespace-settings-layout.component.html',
  styleUrl: './namespace-settings-layout.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceSettingsLayoutComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly namespaceApi = inject(NamespaceControllerService)
  private readonly integrationTypeApi = inject(IntegrationTypeControllerService)
  private readonly destroyRef = inject(DestroyRef)

  protected readonly namespaceId = this.route.snapshot.paramMap.get('namespaceId') ?? ''
  protected readonly namespaceName = signal('Namespace')
  protected readonly gitAvailable = signal(false)

  protected readonly groups: readonly NamespaceSettingsGroup[] = [
    {
      label: 'General',
      items: [{ label: 'Edit namespace', path: 'edit' }],
    },
    {
      label: 'Access',
      items: [
        { label: 'Members', path: 'members' },
        { label: 'User groups', path: 'user-groups' },
        { label: 'Auth settings', path: 'auth-settings' },
      ],
    },
    {
      label: 'Connections',
      items: [
        { label: 'Integrations', path: 'integrations' },
        { label: 'Git', path: 'git' },
      ],
    },
    {
      label: 'AI',
      items: [
        { label: 'AI providers', path: 'ai-providers' },
        { label: 'AI models', path: 'ai-models' },
        { label: 'Agent configs', path: 'agent-configs' },
        { label: 'Prompts', path: 'prompts' },
      ],
    },
    {
      label: 'Automation',
      items: [
        { label: 'Schedulers', path: 'scheduled-prompts' },
        { label: 'Tool invoke', path: 'tool-invoke' },
      ],
    },
    {
      label: 'Monitoring',
      items: [{ label: 'Usage and costs', path: 'usage' }],
    },
  ]

  protected openNamespace(): void {
    void this.router.navigate(['/agentos/home'], { queryParams: { ns: this.namespaceId } })
  }

  constructor() {
    this.integrationTypeApi
      .listTypesIntegrationType()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (types) => this.gitAvailable.set(types.some((type) => type.type === 'GIT')),
      })

    if (!this.namespaceId) return

    this.namespaceApi
      .getByIdNamespace(this.namespaceId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (namespace) => this.namespaceName.set(namespace.name),
      })
  }
}
