import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { AiProvider, AiProviderControllerService } from '@whoz-oss/agentos-api-client'
import { SectionHeaderComponent, TableDirective } from '@whoz-oss/design-system'
import { BehaviorSubject, switchMap } from 'rxjs'
import { createRowFilter } from '../../utils/filter-rows'
import { createRowDeletionState } from '../../utils/row-deletion-state'
import { RowDeleteActionComponent } from '../row-delete-action/row-delete-action.component'
import { TableFilterComponent } from '../table-filter/table-filter.component'

/**
 * PlatformAiProvidersComponent — table view for platform-level AI providers.
 *
 * Loaded at /agentos/admin/ai-providers. Accessible to super-admins only
 * (backend enforces via 403; frontend shows the link only when user.isAdmin).
 *
 * Platform providers have namespaceId IS NULL (sentinel: 'none'). The API key is never
 * displayed here: credentials are only visible (masked) in the form.
 */
@Component({
  selector: 'agentos-platform-ai-providers',
  imports: [SectionHeaderComponent, TableDirective, TableFilterComponent, RowDeleteActionComponent],
  templateUrl: './platform-ai-providers.component.html',
  styleUrl: './platform-ai-providers.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformAiProvidersComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly aiProviderController = inject(AiProviderControllerService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  protected readonly isLoading = signal(true)
  protected readonly deletion = createRowDeletionState()
  protected readonly providers = signal<AiProvider[]>([])
  protected readonly rowFilter = createRowFilter(this.providers, (provider) =>
    [provider.name, provider.apiType, provider.baseUrl].filter(Boolean).join(' ')
  )

  constructor() {
    this.refresh$
      .pipe(
        switchMap(() => {
          this.isLoading.set(true)
          return this.aiProviderController.listAiProvider('none')
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: (providers) => {
          this.providers.set(providers)
          this.isLoading.set(false)
        },
        error: () => this.isLoading.set(false),
      })
  }

  protected openCreateForm(): void {
    void this.router.navigate(['/agentos', 'admin', 'ai-providers', 'new'])
  }

  protected edit(provider: AiProvider): void {
    if (!provider.id) return
    void this.router.navigate(['/agentos', 'admin', 'ai-providers', provider.id, 'edit'])
  }

  protected confirmDelete(provider: AiProvider): void {
    if (!provider.id) return
    this.deletion.execute(provider.id, {
      request$: this.aiProviderController.deleteAiProvider(provider.id).pipe(takeUntilDestroyed(this.destroyRef)),
      onSuccess: () => this.refresh$.next(),
      errorMessage: `Deleting "${provider.name}" failed. Try again.`,
    })
  }
}
