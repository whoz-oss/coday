import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { AiProvider, AiProviderControllerService } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { BehaviorSubject, map, switchMap } from 'rxjs'
import { AiProviderItemComponent } from '../ai-provider-item/ai-provider-item.component'

/**
 * PlatformAiProvidersComponent — list view for platform-level AI providers.
 *
 * Loaded at /agentos/admin/ai-providers. Accessible to super-admins only
 * (backend enforces via 403; frontend shows the link only when user.isAdmin).
 *
 * Platform providers have namespaceId IS NULL (sentinel: 'none').
 * The scope badge shows 'NS' for all items — there is no user-scoped provider at
 * platform level, and the badge still conveys the shared-namespace semantic.
 */
@Component({
  selector: 'agentos-platform-ai-providers',
  standalone: true,
  imports: [AsyncPipe, EntityListComponent, AiProviderItemComponent],
  templateUrl: './platform-ai-providers.component.html',
  styleUrl: './platform-ai-providers.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformAiProvidersComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly aiProviderController = inject(AiProviderControllerService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  /** Raw platform providers, kept for delete lookups. */
  private readonly providers$ = this.refresh$.pipe(switchMap(() => this.aiProviderController.listAiProvider('none')))

  /** Mapped to EntityListItem[] for ds-entity-list. */
  protected readonly providerItems$ = this.providers$.pipe(
    map((providers) =>
      providers.map(
        (p: AiProvider): EntityListItem => ({
          id: p.id ?? '',
          name: p.name,
          description: p.apiType,
        })
      )
    )
  )

  /** Full provider objects indexed by id — used to resolve itemTemplate events. */
  private providersById = new Map<string, AiProvider>()

  constructor() {
    this.providers$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((providers) => {
      this.providersById = new Map(providers.map((p: AiProvider) => [p.id ?? '', p]))
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'admin'])
  }

  protected openCreateForm(): void {
    // namespaceId='none' is the sentinel for namespaceId IS NULL (platform scope).
    // The form's navigateBack() will return to /agentos/none/ai-providers which
    // resolves to AiProvidersAllScopesComponent with the platform scope filter.
    this.router.navigate(['/agentos', 'none', 'ai-providers', 'new'], {
      queryParams: { scope: 'namespace' },
    })
  }

  protected onEdit(provider: AiProvider): void {
    if (!provider.id) return
    // Same sentinel pattern: the form loads by id and derives scope from the resource.
    this.router.navigate(['/agentos', 'none', 'ai-providers', provider.id, 'edit'])
  }

  protected onDelete(provider: AiProvider): void {
    if (!provider.id) return
    this.aiProviderController
      .deleteAiProvider(provider.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected resolveProvider(id: string): AiProvider | null {
    return this.providersById.get(id) ?? null
  }
}
