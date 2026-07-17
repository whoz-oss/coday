import { HttpClient } from '@angular/common/http'
import {
  afterNextRender,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  OnInit,
  signal,
  ViewChild,
} from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { Case, Configuration, ExchangeFileEntryScopeEnum, Prompt } from '@whoz-oss/agentos-api-client'
import { CaseStateService } from '../../services/case-state.service'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { catchError, debounceTime, firstValueFrom, map, of, Subject, switchMap } from 'rxjs'
import { PromptStateService } from '../../services/prompt-state.service'
import { PromptAutocompleteComponent } from '../prompt-autocomplete/prompt-autocomplete.component'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { UserStateService } from '../../services/user-state.service'
import { ExchangeStateService } from '../../services/exchange-state.service'
import { ComposerAttachmentsComponent } from '../composer-attachments/composer-attachments.component'
import { ComposerAttachmentsService } from '../composer-attachments/composer-attachments.service'
import { resolveUploadScope } from '../composer-attachments/composer-attachments.utils'

/**
 * CaseHomeComponent — landing page for a namespace.
 *
 * Flow:
 * 1. User types a message (optionally attaching files) and presses Enter (or clicks Send)
 * 2. POST /api/cases creates the case
 * 3. Attached files are uploaded to the new case's exchange (or the namespace's, on
 *    explicit request) and referenced in the message content
 * 4. POST /api/cases/:id/messages sends the first message
 * 5. Only then does the app navigate to the case chat
 *
 * The first message is never stored in router state to avoid re-sending on refresh.
 * The created case id is remembered in [pendingCaseId] so a failed upload/send retries
 * against the same case instead of creating a duplicate.
 */
@Component({
  selector: 'agentos-case-home',
  imports: [IconButtonComponent, PromptAutocompleteComponent, ComposerAttachmentsComponent],
  providers: [ComposerAttachmentsService],
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
  private readonly userState = inject(UserStateService)
  private readonly exchangeState = inject(ExchangeStateService)

  /** Files staged on the first message (component-scoped instance, see providers). */
  protected readonly attachments = inject(ComposerAttachmentsService)

  @ViewChild('composerInput') private composerInput?: ElementRef<HTMLTextAreaElement>
  @ViewChild(PromptAutocompleteComponent) private autocompleteRef?: PromptAutocompleteComponent

  protected namespaceId = this.route.snapshot.queryParams['ns'] as string

  protected readonly inputValue = signal('')
  protected readonly isCreating = signal(false)

  /** Case created by a previous failed submit — reused on retry, never duplicated. */
  private readonly pendingCaseId = signal<string | null>(null)

  /** True when the message text targets the namespace exchange (previewed on the chips). */
  protected readonly namespaceTargeted = computed(
    () =>
      resolveUploadScope(this.inputValue(), this.exchangeState.canWriteNamespace()) ===
      ExchangeFileEntryScopeEnum.NAMESPACE
  )

  // ---------------------------------------------------------------------------
  // Slash-command autocomplete
  // ---------------------------------------------------------------------------

  private effectivePrompts: Prompt[] = []
  private promptsLoaded = false
  private readonly slashPrefix$ = new Subject<string>()

  protected readonly slashSuggestions = signal<Prompt[]>([])

  ngOnInit(): void {
    // Namespace-only exchange init: no case exists yet, but canWriteNamespace() gating
    // (namespace-intent badge and upload target) needs the namespace manifest.
    this.exchangeState.initializeForNamespace(this.namespaceId)

    // React to ?ns query param changes — the component is reused across namespace switches.
    // The handler skips the initial emission (newNs === this.namespaceId), hence the
    // explicit init above.
    this.route.queryParams.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const newNs = params['ns'] as string
      if (newNs && newNs !== this.namespaceId) {
        this.namespaceId = newNs
        // Reset autocomplete state so stale suggestions from the previous namespace are cleared.
        this.effectivePrompts = []
        this.promptsLoaded = false
        this.slashSuggestions.set([])
        this.inputValue.set('')
        this.attachments.reset()
        this.pendingCaseId.set(null)
        this.exchangeState.initializeForNamespace(newNs)
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
            : this.promptState
                .listEffective(this.namespaceId, this.userState.currentUser()?.id ?? '')
                .pipe(catchError(() => of([] as Prompt[])))
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
    return (!!this.inputValue().trim() || this.attachments.hasAttachments()) && !this.isCreating()
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

  protected async submit(): Promise<void> {
    if (!this.canSend) return
    const firstMessage = this.inputValue().trim()
    this.isCreating.set(true)

    try {
      // Step 1: create the case — once. A previous failed attempt is retried against the
      // same case (pendingCaseId), so a duplicate is never created.
      let caseId = this.pendingCaseId()
      if (!caseId) {
        const createdCase = await firstValueFrom(
          this.http.post<Case>(`${this.config.basePath}/api/cases`, {
            namespaceId: this.namespaceId,
            metadata: {},
          })
        )
        this.caseState.addCase(createdCase)
        caseId = createdCase.id ?? ''
        this.pendingCaseId.set(caseId)
      }

      // Step 2: upload the attachments to the fresh case (or the namespace on explicit
      // request) and reference them in the message content.
      let content = firstMessage
      if (this.attachments.hasAttachments()) {
        this.exchangeState.initializeForCase(this.namespaceId, caseId)
        const scope = resolveUploadScope(firstMessage, this.exchangeState.canWriteNamespace())
        const mention = await this.attachments.uploadAllAndBuildMention(scope)
        if (mention === null) {
          // Partial failure: stay on home with the failed chips and the intact text; a
          // retry reuses the created case and skips the files already uploaded.
          this.isCreating.set(false)
          return
        }
        content = content ? `${content}\n\n${mention}` : mention
      }

      // Step 3: send the first message before navigating.
      await firstValueFrom(
        this.http.post(`${this.config.basePath}/api/cases/${caseId}/messages`, {
          content,
          userId: 'default-user',
        })
      )

      // Step 4: navigate — no firstMessage in state, the message is already posted.
      this.attachments.reset()
      this.inputValue.set('')
      this.pendingCaseId.set(null)
      this.router.navigate(['/agentos/home'], { queryParams: { ns: this.namespaceId, case: caseId } })
    } catch (err) {
      console.error('[CaseHome] Failed to create case or send first message', err)
      this.isCreating.set(false)
    }
  }
}
