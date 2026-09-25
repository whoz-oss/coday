import {
  afterNextRender,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  input,
  OnInit,
  signal,
  viewChild,
  ChangeDetectionStrategy,
} from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { filter, firstValueFrom, Subject, takeUntil, throwError, timeout } from 'rxjs'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfig, CaseControllerService, CaseStatusEnum, Prompt } from '@whoz-oss/agentos-api-client'
import { CaseStateService } from '../../services/case-state.service'
import { BlueprintDirective, IconButtonComponent } from '@whoz-oss/design-system'
import { PromptAutocompleteComponent } from '../prompt-autocomplete/prompt-autocomplete.component'
import { AgentAutocompleteComponent } from '../agent-autocomplete/agent-autocomplete.component'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { ComposerAutocompleteService } from '../composer-autocomplete/composer-autocomplete.service'
import { ExchangeStateService } from '../../services/exchange-state.service'
import { ComposerAttachmentsComponent } from '../composer-attachments/composer-attachments.component'
import { ComposerAttachmentsService } from '../composer-attachments/composer-attachments.service'
import { isNamespaceTargeted, resolveUploadScope } from '../composer-attachments/composer-attachments.utils'
import { CaseWorkspaceService } from '../../services/case-workspace.service'

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
  imports: [
    BlueprintDirective,
    IconButtonComponent,
    PromptAutocompleteComponent,
    AgentAutocompleteComponent,
    ComposerAttachmentsComponent,
  ],
  providers: [ComposerAttachmentsService, ComposerAutocompleteService],
  templateUrl: './case-home.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './case-home.component.scss',
})
export class CaseHomeComponent implements OnInit {
  private readonly caseApi = inject(CaseControllerService)
  private readonly workspaces = inject(CaseWorkspaceService)
  private readonly router = inject(Router)
  private readonly route = inject(ActivatedRoute)
  private readonly caseState = inject(CaseStateService)
  private readonly destroyRef = inject(DestroyRef)
  protected readonly preferences = inject(USER_PREFERENCES_PORT)
  /** Nom du namespace actif — passé par CaseShellComponent */
  readonly namespaceName = input<string | null>(null)
  private readonly exchangeState = inject(ExchangeStateService)

  /** Files staged on the first message (component-scoped instance, see providers). */
  protected readonly attachments = inject(ComposerAttachmentsService)
  protected readonly autocomplete = inject(ComposerAutocompleteService)

  private readonly composerInput = viewChild<ElementRef<HTMLTextAreaElement>>('composerInput')
  private readonly promptAutocompleteRef = viewChild(PromptAutocompleteComponent)
  private readonly agentAutocompleteRef = viewChild(AgentAutocompleteComponent)

  protected namespaceId = this.route.snapshot.queryParams['ns'] as string
  protected readonly parentCaseId = signal<string | null>(this.route.snapshot.queryParams['parentCase'] ?? null)
  protected readonly parentCaseTitle = computed(() => {
    const parentId = this.parentCaseId()
    return parentId ? this.caseState.cases().find((c) => c.id === parentId)?.title || parentId : null
  })
  private creationContext = 0
  private readonly creationChanged = new Subject<void>()

  protected readonly inputValue = signal('')
  protected readonly isCreating = signal(false)
  protected readonly submitError = signal('')

  /** Case created by a previous failed submit — reused on retry, never duplicated. */
  private readonly pendingCaseId = signal<string | null>(null)

  /** The async submit chain must stop touching state or navigating once the view is gone. */
  private destroyed = false

  /** True when the message text targets the namespace exchange (previewed on the chips). */
  protected readonly namespaceTargeted = computed(() =>
    isNamespaceTargeted(this.inputValue(), this.exchangeState.canWriteNamespace())
  )

  ngOnInit(): void {
    // Namespace-only exchange init: no case exists yet, but canWriteNamespace() gating
    // (namespace-intent badge and upload target) needs the namespace manifest.
    this.exchangeState.initializeForNamespace(this.namespaceId)

    // The composer is reused when switching namespaces or the parent of a new sub-case.
    this.route.queryParams.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const newNs = params['ns'] as string
      const newParent = (params['parentCase'] as string) || null
      if (newNs && (newNs !== this.namespaceId || newParent !== this.parentCaseId())) {
        this.creationContext++
        this.creationChanged.next()
        this.namespaceId = newNs
        this.parentCaseId.set(newParent)
        this.autocomplete.init(newNs)
        this.autocomplete.reset()
        this.inputValue.set('')
        this.attachments.reset()
        this.pendingCaseId.set(null)
        this.submitError.set('')
        this.isCreating.set(false)
        this.exchangeState.initializeForNamespace(newNs)
      }
    })
  }

  constructor() {
    this.destroyRef.onDestroy(() => (this.destroyed = true))
    this.autocomplete.init(this.namespaceId)

    // Track both the value and the view query. This covers programmatic writes
    // (autocomplete, namespace reset, and post-submit clear) once the textarea exists.
    effect(() => {
      this.inputValue()
      const input = this.composerInput()?.nativeElement
      if (!input) return
      queueMicrotask(() => this.resizeComposer(input))
    })

    afterNextRender(() => {
      const input = this.composerInput()?.nativeElement
      input?.focus()
      this.resizeComposer(input)
    })
  }

  protected get canSend(): boolean {
    return (!!this.inputValue().trim() || this.attachments.hasAttachments()) && !this.isCreating()
  }

  protected onInput(event: Event): void {
    const input = event.target as HTMLTextAreaElement
    this.autocomplete.onInput(input.value, this.inputValue)
    // `input` fires after the browser updates the DOM value, so scrollHeight is current.
    this.resizeComposer(input)
  }

  /** Size to the complete content height; programmatic changes are handled by the effect above. */
  private resizeComposer(input?: HTMLTextAreaElement): void {
    if (!input) return

    input.style.height = 'auto'
    input.style.height = `${input.scrollHeight}px`
  }

  protected onKeydown(event: KeyboardEvent): void {
    const consumed = this.autocomplete.onKeydown(event, this.promptAutocompleteRef, this.agentAutocompleteRef)
    if (consumed) return
    if (this.preferences.shouldSend(event)) {
      event.preventDefault()
      this.submit()
    }
  }

  protected onPromptSelected(prompt: Prompt): void {
    this.autocomplete.onPromptSelected(prompt, this.promptAutocompleteRef, this.composerInput, this.inputValue)
  }

  protected onAgentSelected(agent: AgentConfig): void {
    this.autocomplete.onAgentSelected(agent, this.agentAutocompleteRef, this.composerInput, this.inputValue)
  }

  protected closeSlashAutocomplete(): void {
    this.autocomplete.slashSuggestions.set([])
  }

  protected closeAtAutocomplete(): void {
    this.autocomplete.atSuggestions.set([])
  }

  protected returnToParent(): void {
    this.router.navigate(['/agentos/home'], {
      queryParams: { ns: this.namespaceId, case: this.parentCaseId() },
    })
  }

  // NOTE: this file exceeds the ~200-line guideline. The submit orchestration is kept
  // inline for now: extracting a shared case-creation/composer service (which should also
  // deduplicate the slash-autocomplete logic copied from case-chat) is follow-up work.
  protected async submit(): Promise<void> {
    if (!this.canSend) return
    const firstMessage = this.inputValue().trim()
    // Switching namespace or parent abandons this submit, even if the user switches back.
    const namespaceId = this.namespaceId
    const parentCaseId = this.parentCaseId()
    const creationContext = this.creationContext
    this.submitError.set('')
    this.isCreating.set(true)

    try {
      // Step 1: create the case — once. A previous failed attempt is retried against the
      // same case (pendingCaseId), so a duplicate is never created.
      let caseId = this.pendingCaseId()
      if (!caseId) {
        const createdCase = await firstValueFrom(
          this.caseApi.createCase({
            namespaceId,
            ...(parentCaseId ? { parentCaseId } : {}),
            status: CaseStatusEnum.PENDING,
            favorite: false,
            removed: false,
          })
        )
        if (this.abandoned(creationContext)) return
        this.caseState.addCase(createdCase)
        caseId = createdCase.id ?? ''
        this.pendingCaseId.set(caseId)
      }
      if (this.abandoned(creationContext)) return

      // Keep the same case and local attachments on retry; never fill the worktree before Git does.
      if (this.attachments.hasAttachments()) {
        const state = await firstValueFrom(
          this.workspaces.watch(caseId).pipe(
            filter(({ view }) => !view?.equipped || !['REQUESTED', 'PREPARING'].includes(view.status ?? '')),
            timeout({
              first: 120_000,
              with: () =>
                throwError(
                  () =>
                    new Error(
                      'Workspace preparation is still in progress. Your message and attachments are saved here. Retry shortly to continue with the same case.'
                    )
                ),
            }),
            takeUntil(this.creationChanged),
            takeUntilDestroyed(this.destroyRef)
          )
        )
        if (!state.view || (state.view.equipped && state.view.status !== 'READY')) {
          throw new Error(state.view?.failureReason || 'Workspace is not ready. Retry this submission shortly.')
        }
        if (this.abandoned(creationContext)) return
      }

      // Step 2: upload the attachments to the fresh case (or the namespace on explicit
      // request) and reference them in the message content.
      let content = firstMessage
      if (this.attachments.hasAttachments()) {
        this.exchangeState.initializeForCase(namespaceId, caseId)
        const scope = resolveUploadScope(firstMessage, this.exchangeState.canWriteNamespace())
        const mention = await this.attachments.uploadAllAndBuildMention(scope)
        if (mention === null || this.abandoned(creationContext)) {
          // Partial failure (or an abandoned submit): stay on home with the failed chips
          // and the intact text; a retry reuses the created case and skips the files
          // already uploaded.
          return
        }
        content = content ? `${content}\n\n${mention}` : mention
      }

      // Step 3: send the first message before navigating.
      await firstValueFrom(
        this.caseApi.addMessageCase(caseId, {
          content,
        })
      )
      if (this.abandoned(creationContext)) return

      // Step 4: navigate — no firstMessage in state, the message is already posted.
      this.attachments.reset()
      this.inputValue.set('')
      this.pendingCaseId.set(null)
      this.router.navigate(['/agentos/home'], { queryParams: { ns: namespaceId, case: caseId } })
    } catch (err) {
      if (this.abandoned(creationContext)) return
      console.error('[CaseHome] Failed to create case or send first message', err)
      this.submitError.set(
        (err as { error?: { message?: string }; message?: string })?.error?.message ||
          (err as Error)?.message ||
          'Could not send the message. Retry to continue with the same case.'
      )
    } finally {
      if (!this.abandoned(creationContext)) this.isCreating.set(false)
    }
  }

  /** True when the in-flight submit no longer belongs to the displayed composer. */
  private abandoned(creationContext: number): boolean {
    return this.destroyed || this.creationContext !== creationContext
  }
}
