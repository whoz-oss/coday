import { HttpClient } from '@angular/common/http'
import {
  afterNextRender,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  input,
  OnInit,
  signal,
  viewChild,
} from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { firstValueFrom } from 'rxjs'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfig, Case, Configuration, Prompt } from '@whoz-oss/agentos-api-client'
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

  protected readonly inputValue = signal('')
  protected readonly isCreating = signal(false)

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

    // React to ?ns query param changes — the component is reused across namespace switches.
    // The handler skips the initial emission (newNs === this.namespaceId), hence the
    // explicit init above.
    this.route.queryParams.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const newNs = params['ns'] as string
      if (newNs && newNs !== this.namespaceId) {
        this.namespaceId = newNs
        this.autocomplete.init(newNs)
        this.autocomplete.reset()
        this.inputValue.set('')
        this.attachments.reset()
        this.pendingCaseId.set(null)
        this.exchangeState.initializeForNamespace(newNs)
      }
    })
  }

  constructor() {
    this.destroyRef.onDestroy(() => (this.destroyed = true))
    this.autocomplete.init(this.namespaceId)

    afterNextRender(() => {
      this.composerInput()?.nativeElement.focus()
    })
  }

  protected get canSend(): boolean {
    return (!!this.inputValue().trim() || this.attachments.hasAttachments()) && !this.isCreating()
  }

  protected onInput(event: Event): void {
    const value = (event.target as HTMLTextAreaElement).value
    this.autocomplete.onInput(value, this.inputValue)
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

  // NOTE: this file exceeds the ~200-line guideline. The submit orchestration is kept
  // inline for now: extracting a shared case-creation/composer service (which should also
  // deduplicate the slash-autocomplete logic copied from case-chat) is follow-up work.
  protected async submit(): Promise<void> {
    if (!this.canSend) return
    const firstMessage = this.inputValue().trim()
    // Captured for the whole chain: a namespace switch mid-flight abandons the submit.
    const namespaceId = this.namespaceId
    this.isCreating.set(true)

    try {
      // Step 1: create the case — once. A previous failed attempt is retried against the
      // same case (pendingCaseId), so a duplicate is never created.
      let caseId = this.pendingCaseId()
      if (!caseId) {
        const createdCase = await firstValueFrom(
          this.http.post<Case>(`${this.config.basePath}/api/cases`, {
            namespaceId,
            metadata: {},
          })
        )
        this.caseState.addCase(createdCase)
        caseId = createdCase.id ?? ''
        this.pendingCaseId.set(caseId)
      }
      if (this.abandoned(namespaceId)) {
        this.isCreating.set(false)
        return
      }

      // Step 2: upload the attachments to the fresh case (or the namespace on explicit
      // request) and reference them in the message content.
      let content = firstMessage
      if (this.attachments.hasAttachments()) {
        this.exchangeState.initializeForCase(namespaceId, caseId)
        const scope = resolveUploadScope(firstMessage, this.exchangeState.canWriteNamespace())
        const mention = await this.attachments.uploadAllAndBuildMention(scope)
        if (mention === null || this.abandoned(namespaceId)) {
          // Partial failure (or an abandoned submit): stay on home with the failed chips
          // and the intact text; a retry reuses the created case and skips the files
          // already uploaded.
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
      if (this.abandoned(namespaceId)) {
        this.isCreating.set(false)
        return
      }

      // Step 4: navigate — no firstMessage in state, the message is already posted.
      this.attachments.reset()
      this.inputValue.set('')
      this.pendingCaseId.set(null)
      this.router.navigate(['/agentos/home'], { queryParams: { ns: namespaceId, case: caseId } })
    } catch (err) {
      console.error('[CaseHome] Failed to create case or send first message', err)
      this.isCreating.set(false)
    }
  }

  /** True when the in-flight submit no longer matches the live view (destroyed or ns switch). */
  private abandoned(namespaceId: string): boolean {
    return this.destroyed || this.namespaceId !== namespaceId
  }
}
