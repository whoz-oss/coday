import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { Prompt } from '@whoz-oss/agentos-api-client'
import { SectionHeaderComponent, TableDirective } from '@whoz-oss/design-system'
import { BehaviorSubject, switchMap } from 'rxjs'
import { PromptStateService } from '../../services/prompt-state.service'
import { createRowFilter } from '../../utils/filter-rows'
import { TableFilterComponent } from '../table-filter/table-filter.component'

/**
 * PlatformPromptsComponent — table view for platform-level prompts.
 *
 * Loaded at /agentos/admin/prompts. Accessible to super-admins only
 * (backend enforces via 403; frontend shows the link only when user.isAdmin).
 *
 * Platform prompts have no namespaceId and no userId — they are shared
 * across all namespaces.
 */
@Component({
  selector: 'agentos-platform-prompts',
  imports: [SectionHeaderComponent, TableDirective, TableFilterComponent],
  templateUrl: './platform-prompts.component.html',
  styleUrl: './platform-prompts.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformPromptsComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly promptState = inject(PromptStateService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  protected readonly isLoading = signal(true)
  protected readonly pendingDeleteId = signal<string | null>(null)
  protected readonly prompts = signal<Prompt[]>([])
  protected readonly rowFilter = createRowFilter(this.prompts, (prompt) =>
    [prompt.name, prompt.description].filter(Boolean).join(' ')
  )

  constructor() {
    this.refresh$
      .pipe(
        switchMap(() => {
          this.isLoading.set(true)
          return this.promptState.listPlatform()
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: (prompts) => {
          this.prompts.set(prompts)
          this.isLoading.set(false)
        },
        error: () => this.isLoading.set(false),
      })
  }

  protected openCreateForm(): void {
    void this.router.navigate(['/agentos', 'admin', 'prompts', 'new'])
  }

  protected edit(prompt: Prompt): void {
    if (!prompt.id) return
    void this.router.navigate(['/agentos', 'admin', 'prompts', prompt.id, 'edit'])
  }

  protected requestDelete(prompt: Prompt): void {
    this.pendingDeleteId.set(prompt.id ?? null)
  }

  protected cancelDelete(): void {
    this.pendingDeleteId.set(null)
  }

  protected confirmDelete(prompt: Prompt): void {
    this.pendingDeleteId.set(null)
    this.promptState
      .delete(prompt.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }
}
