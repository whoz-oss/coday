import { AsyncPipe } from '@angular/common'
import { HttpClient } from '@angular/common/http'
import { afterNextRender, Component, DestroyRef, ElementRef, inject, signal, ViewChild } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { Case, CaseControllerService, Configuration } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, switchMap } from 'rxjs'

/**
 * CaseHomeComponent — landing page for a namespace.
 *
 * Displays a centered, inviting text area for starting a new case.
 * A side drawer lists existing cases for the namespace.
 *
 * Flow:
 * 1. User types a message and presses Enter (or clicks Send)
 * 2. A new case is created implicitly via POST /api/cases
 * 3. The app navigates to the case chat, passing the first message
 *    via router navigation state so CaseChatComponent sends it immediately.
 */
@Component({
  selector: 'agentos-case-home',
  standalone: true,
  imports: [AsyncPipe, IconButtonComponent],
  templateUrl: './case-home.component.html',
  styleUrl: './case-home.component.scss',
})
export class CaseHomeComponent {
  private readonly http = inject(HttpClient)
  private readonly router = inject(Router)
  private readonly route = inject(ActivatedRoute)
  private readonly config = inject(Configuration)
  private readonly caseController = inject(CaseControllerService)
  private readonly destroyRef = inject(DestroyRef)

  @ViewChild('composerInput') private composerInput?: ElementRef<HTMLTextAreaElement>

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  constructor() {
    // Focus the textarea on first render — avoids the accessibility issues of the `autofocus` attribute
    afterNextRender(() => {
      this.composerInput?.nativeElement.focus()
    })
  }

  protected readonly inputValue = signal('')
  protected readonly isCreating = signal(false)
  protected readonly isDrawerOpen = signal(false)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  protected readonly cases$ = this.refresh$.pipe(
    switchMap(() => this.caseController.listByParentCase(this.namespaceId))
  )

  protected get canSend(): boolean {
    return !!this.inputValue().trim() && !this.isCreating()
  }

  protected onInput(event: Event): void {
    this.inputValue.set((event.target as HTMLTextAreaElement).value)
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      this.submit()
    }
  }

  protected toggleDrawer(): void {
    const opening = !this.isDrawerOpen()
    this.isDrawerOpen.set(opening)
    if (opening) {
      // Refresh the list when opening the drawer
      this.refresh$.next()
    }
  }

  protected openCase(caseId: string): void {
    this.router.navigate(['/agentos', this.namespaceId, 'cases', caseId])
  }

  protected submit(): void {
    if (!this.canSend) return
    const firstMessage = this.inputValue().trim()
    this.inputValue.set('')
    this.isCreating.set(true)

    this.http
      .post<Case>(`${this.config.basePath}/api/cases`, {
        namespaceId: this.namespaceId,
        metadata: {},
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (createdCase) => {
          this.router.navigate(['/agentos', this.namespaceId, 'cases', createdCase.id ?? ''], {
            state: { firstMessage },
          })
        },
        error: (err) => {
          console.error('[CaseHome] Failed to create case', err)
          this.isCreating.set(false)
          // Restore the message so the user can retry
          this.inputValue.set(firstMessage)
        },
      })
  }

  protected trackById(_index: number, c: Case): string {
    return c.id ?? ''
  }
}
