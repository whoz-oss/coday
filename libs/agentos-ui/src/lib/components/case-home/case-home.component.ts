import { HttpClient } from '@angular/common/http'
import { afterNextRender, Component, DestroyRef, ElementRef, inject, OnInit, signal, ViewChild } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { Case, Configuration, Prompt } from '@whoz-oss/agentos-api-client'
import { CaseStateService } from '../../services/case-state.service'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { catchError, debounceTime, map, of, Subject, switchMap } from 'rxjs'
import { PromptStateService } from '../../services/prompt-state.service'
import { PromptAutocompleteComponent } from '../prompt-autocomplete/prompt-autocomplete.component'
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
  imports: [IconButtonComponent, PromptAutocompleteComponent],
  templateUrl: './case-home.component.html',
  styleUrl: './case-home.component.scss',
})
export class CaseHomeComponent implements OnInit {
  private readonly http = inject(HttpClient)
  private readonly router = inject(Router)
  private readonly route = inject(ActivatedRoute)
  private readonly config = inject(Configuration)
  private readonly caseState = inject(CaseStateService)
  private readonly destroyRef = inject(DestroyRef)
  protected readonly preferences = inject(USER_PREFERENCES_PORT)
  private readonly promptState = inject(PromptStateService)

  @ViewChild('composerInput') private composerInput?: ElementRef<HTMLTextAreaElement>
  @ViewChild(PromptAutocompleteComponent) private autocompleteRef?: PromptAutocompleteComponent

  protected namespaceId = this.route.snapshot.queryParams['ns'] as string

  protected readonly inputValue = signal('')
  protected readonly isCreating = signal(false)

  // ---------------------------------------------------------------------------
  // Slash-command autocomplete
  // ---------------------------------------------------------------------------

  private effectivePrompts: Prompt[] = []
  private promptsLoaded = false
  private readonly slashPrefix$ = new Subject<string>()

  protected readonly slashSuggestions = signal<Prompt[]>([])

  ngOnInit(): void {
    // React to ?ns query param changes — the component is reused across namespace switches.
    this.route.queryParams.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const newNs = params['ns'] as string
      if (newNs && newNs !== this.namespaceId) {
        this.namespaceId = newNs
        // Reset autocomplete state so stale suggestions from the previous namespace are cleared.
        this.effectivePrompts = []
        this.promptsLoaded = false
        this.slashSuggestions.set([])
        this.inputValue.set('')
      }
    })
  }

  constructor() {
    afterNextRender(() => {
      this.composerInput?.nativeElement.focus()
    })

    this.slashPrefix$
      .pipe(
        debounceTime(60),
        switchMap((prefix) => {
          const source$ = this.promptsLoaded
            ? of(this.effectivePrompts)
            : this.promptState.listEffective(this.namespaceId).pipe(catchError(() => of([] as Prompt[])))
          return source$.pipe(map((prompts) => ({ prefix, prompts })))
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe(({ prefix, prompts }) => {
        this.effectivePrompts = prompts
        this.promptsLoaded = true
        this.slashSuggestions.set(prompts.filter((p) => p.name.toLowerCase().startsWith(prefix.toLowerCase())))
      })
  }

  protected get canSend(): boolean {
    return !!this.inputValue().trim() && !this.isCreating()
  }

  protected onInput(event: Event): void {
    const value = (event.target as HTMLTextAreaElement).value
    this.inputValue.set(value)
    this.updateSlashAutocomplete(value)
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (this.slashSuggestions().length > 0) {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault()
        this.autocompleteRef?.navigate(event.key)
        return
      }
      if (event.key === 'Escape') {
        event.preventDefault()
        this.closeAutocomplete()
        return
      }
      if (event.key === 'Tab' || event.key === 'Enter') {
        event.preventDefault()
        this.autocompleteRef?.navigate('Enter')
        return
      }
    }
    if (this.preferences.shouldSend(event)) {
      event.preventDefault()
      this.submit()
    }
  }

  protected onPromptSelected(prompt: Prompt): void {
    const completion = this.autocompleteRef?.completionFor(prompt) ?? `/${prompt.name} `
    this.inputValue.set(completion)
    queueMicrotask(() => {
      const el = this.composerInput?.nativeElement
      if (!el) return
      el.value = completion
      el.setSelectionRange(completion.length, completion.length)
      el.focus()
    })
    this.closeAutocomplete()
  }

  protected closeAutocomplete(): void {
    this.slashSuggestions.set([])
  }

  private updateSlashAutocomplete(value: string): void {
    if (!value.startsWith('/')) {
      if (this.slashSuggestions().length) this.slashSuggestions.set([])
      return
    }
    const withoutSlash = value.slice(1)
    if (withoutSlash.includes(' ')) {
      if (this.slashSuggestions().length) this.slashSuggestions.set([])
      return
    }
    if (this.promptsLoaded) {
      this.slashSuggestions.set(
        this.effectivePrompts.filter((p) => p.name.toLowerCase().startsWith(withoutSlash.toLowerCase()))
      )
    }
    this.slashPrefix$.next(withoutSlash)
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
