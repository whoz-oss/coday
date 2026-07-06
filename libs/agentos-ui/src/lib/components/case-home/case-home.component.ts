import { HttpClient } from '@angular/common/http'
import { afterNextRender, Component, DestroyRef, ElementRef, inject, signal, ViewChild } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { Case, Configuration } from '@whoz-oss/agentos-api-client'
import { CaseStateService } from '../../services/case-state.service'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { map, switchMap } from 'rxjs'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'

/**
 * CaseHomeComponent — landing page for a namespace.
 *
 * Flow:
 * 1. User types a message and presses Enter (or clicks Send)
 * 2. POST /api/cases creates the case
 * 3. POST /api/cases/:id/messages sends the first message
 * 4. Only then does the app navigate to the case chat
 *
 * The first message is never stored in router state to avoid re-sending on refresh.
 */
@Component({
  selector: 'agentos-case-home',
  standalone: true,
  imports: [IconButtonComponent],
  templateUrl: './case-home.component.html',
  styleUrl: './case-home.component.scss',
})
export class CaseHomeComponent {
  private readonly http = inject(HttpClient)
  private readonly router = inject(Router)
  private readonly route = inject(ActivatedRoute)
  private readonly config = inject(Configuration)
  private readonly caseState = inject(CaseStateService)
  private readonly destroyRef = inject(DestroyRef)
  protected readonly preferences = inject(USER_PREFERENCES_PORT)

  @ViewChild('composerInput') private composerInput?: ElementRef<HTMLTextAreaElement>

  protected readonly namespaceId = this.route.snapshot.queryParams['ns'] as string

  protected readonly inputValue = signal('')
  protected readonly isCreating = signal(false)

  constructor() {
    afterNextRender(() => {
      this.composerInput?.nativeElement.focus()
    })
  }

  protected get canSend(): boolean {
    return !!this.inputValue().trim() && !this.isCreating()
  }

  protected onInput(event: Event): void {
    this.inputValue.set((event.target as HTMLTextAreaElement).value)
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (this.preferences.shouldSend(event)) {
      event.preventDefault()
      this.submit()
    }
  }

  protected submit(): void {
    if (!this.canSend) return
    const firstMessage = this.inputValue().trim()
    this.inputValue.set('')
    this.isCreating.set(true)

    // Step 1: create the case
    this.http
      .post<Case>(`${this.config.basePath}/api/cases`, {
        namespaceId: this.namespaceId,
        metadata: {},
      })
      .pipe(
        // Step 2: send the first message before navigating, carry the case id through
        switchMap((createdCase) => {
          this.caseState.addCase(createdCase)
          return this.http
            .post(`${this.config.basePath}/api/cases/${createdCase.id}/messages`, {
              content: firstMessage,
              userId: 'default-user',
            })
            .pipe(map(() => createdCase))
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: (createdCase) => {
          // Step 3: navigate — no firstMessage in state, the message is already posted
          this.router.navigate(['/agentos/home'], { queryParams: { ns: this.namespaceId, case: createdCase.id ?? '' } })
        },
        error: (err) => {
          console.error('[CaseHome] Failed to create case or send first message', err)
          this.isCreating.set(false)
          this.inputValue.set(firstMessage)
        },
      })
  }
}
