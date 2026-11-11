import {
  afterNextRender,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  OnInit,
  signal,
  viewChild,
  ChangeDetectionStrategy,
} from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import {
  catchError,
  combineLatest,
  defer,
  filter,
  firstValueFrom,
  map,
  Observable,
  of,
  shareReplay,
  Subject,
  switchMap,
  takeUntil,
  throwError,
  timeout,
} from 'rxjs'
import { ActivatedRoute, Router } from '@angular/router'
import {
  AgentConfig,
  CaseControllerService,
  CaseStatusEnum,
  CaseWorkspaceViewStatusEnum,
  IntegrationTypeControllerService,
  NamespaceGitControllerService,
  Prompt,
} from '@whoz-oss/agentos-api-client'
import { CaseStateService } from '../../services/case-state.service'
import { BlueprintDirective, ControlDirective, DrawerComponent, IconButtonComponent } from '@whoz-oss/design-system'
import { PromptAutocompleteComponent } from '../prompt-autocomplete/prompt-autocomplete.component'
import { AgentAutocompleteComponent } from '../agent-autocomplete/agent-autocomplete.component'
import { FileAutocompleteComponent } from '../file-autocomplete/file-autocomplete.component'
import {
  buildReferenceMention,
  FileSuggestion,
  mergeFileReferences,
  withoutFileReferences,
} from '../composer-autocomplete/file-mention'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { ComposerAutocompleteService } from '../composer-autocomplete/composer-autocomplete.service'
import { ExchangeFileRef, ExchangeStateService } from '../../services/exchange-state.service'
import { ExchangeReferenceToggle } from '../exchange-drawer/exchange-drawer.component'
import { ExchangeShellComponent } from '../exchange-shell/exchange-shell.component'
import { ComposerAttachmentsComponent } from '../composer-attachments/composer-attachments.component'
import { ComposerAttachmentsService } from '../composer-attachments/composer-attachments.service'
import { isNamespaceTargeted, resolveUploadScope } from '../composer-attachments/composer-attachments.utils'
import { CaseWorkspaceService, workspacesUnavailable } from '../../services/case-workspace.service'
import { VoiceRecorderComponent } from '../voice-recorder/voice-recorder.component'
import { VoiceRecorderService } from '../voice-recorder/voice-recorder.service'
import { VoiceCapabilityStateService } from '../../services/voice-capability-state.service'
import { AiModelStateService } from '../../services/ai-model-state.service'
import { chatModelOptions } from '../../services/chat-model.utils'
import { ModelOption } from '../agent-config-form/model-options'

const GIT_INTEGRATION_TYPE = 'GIT'

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
 * A first message can also be a voice message (mic button, when the instance can transcribe). The case does
 * not exist while recording: it is created when the recording is sent, exactly like on a text send, then the
 * recording is posted to it (the server names the case from the transcript) and the app navigates. A voice
 * message is the whole message, so the mic is off while files are staged. A failure after the case was created
 * keeps it in [pendingCaseId] like a failed upload: the next send, typed or recorded, reuses it.
 *
 * When this instance has Git workspaces and the GIT plugin, and the namespace creates a worktree
 * for every top-level case, the composer offers it unchecked: checking it creates the case with a worktree.
 *
 * The folder button opens the file drawer on the namespace files (no case yet). The files checked there are kept as
 * chips ([referencedFiles], like the staged uploads) and go with the first message as a `[Files referenced: ...]`
 * line of `namespace:<path>` references, the same text as the `#` mention.
 *
 * The first message is never stored in router state to avoid re-sending on refresh.
 * The created case id is remembered in [pendingCaseId] so a failed upload/send retries
 * against the same case instead of creating a duplicate.
 */
@Component({
  selector: 'agentos-case-home',
  imports: [
    BlueprintDirective,
    ControlDirective,
    DrawerComponent,
    ExchangeShellComponent,
    IconButtonComponent,
    PromptAutocompleteComponent,
    AgentAutocompleteComponent,
    FileAutocompleteComponent,
    ComposerAttachmentsComponent,
    VoiceRecorderComponent,
  ],
  providers: [ComposerAttachmentsService, ComposerAutocompleteService, VoiceRecorderService],
  templateUrl: './case-home.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './case-home.component.scss',
})
export class CaseHomeComponent implements OnInit {
  private readonly caseApi = inject(CaseControllerService)
  private readonly workspaces = inject(CaseWorkspaceService)
  private readonly gitApi = inject(NamespaceGitControllerService)
  private readonly integrationTypeApi = inject(IntegrationTypeControllerService)
  private readonly aiModels = inject(AiModelStateService)
  private readonly router = inject(Router)
  private readonly route = inject(ActivatedRoute)
  private readonly caseState = inject(CaseStateService)
  private readonly destroyRef = inject(DestroyRef)
  private readonly injector = inject(Injector)
  protected readonly preferences = inject(USER_PREFERENCES_PORT)
  /** Nom du namespace actif — passé par CaseShellComponent */
  readonly namespaceName = input<string | null>(null)
  private readonly exchangeState = inject(ExchangeStateService)

  /** Files staged on the first message (component-scoped instance, see providers). */
  protected readonly attachments = inject(ComposerAttachmentsService)
  protected readonly autocomplete = inject(ComposerAutocompleteService)
  /** Voice messages: the recorder is component-scoped (microphone released with the host), the capability is app-wide. */
  protected readonly voice = inject(VoiceRecorderService)
  protected readonly voiceCapability = inject(VoiceCapabilityStateService)

  private readonly composerInput = viewChild<ElementRef<HTMLTextAreaElement>>('composerInput')
  private readonly promptAutocompleteRef = viewChild(PromptAutocompleteComponent)
  private readonly agentAutocompleteRef = viewChild(AgentAutocompleteComponent)
  private readonly fileAutocompleteRef = viewChild(FileAutocompleteComponent)

  protected namespaceId = this.route.snapshot.queryParams['ns'] as string
  protected readonly parentCaseId = signal<string | null>(this.route.snapshot.queryParams['parentCase'] ?? null)
  protected readonly parentCaseTitle = computed(() => {
    const parentId = this.parentCaseId()
    return parentId ? this.caseState.cases().find((c) => c.id === parentId)?.title || parentId : null
  })
  private creationContext = 0
  private readonly creationChanged = new Subject<void>()

  protected readonly inputValue = signal('')
  /** Right-side file drawer (namespace files only: there is no case yet). */
  protected readonly filesOpen = signal(false)
  /** Exchange files picked in the drawer: shown as chips, referenced (never uploaded) by the first message. */
  protected readonly referencedFiles = signal<ExchangeFileRef[]>([])
  protected readonly isCreating = signal(false)
  protected readonly submitError = signal('')

  /** Case created by a previous failed submit — reused on retry, never duplicated. */
  protected readonly pendingCaseId = signal<string | null>(null)

  /** Offered with Git workspaces and the GIT plugin, where the namespace gives every top-level case a worktree. */
  protected readonly worktreeOffered = signal(false)
  /** True once the creator opts in to the worktree the namespace can give the new case. Unchecked by default. */
  protected readonly worktree = signal(false)
  private readonly worktreeNamespace = new Subject<string>()

  /** The chat models the first message can run on, for the namespace in use (empty until loaded, or on failure). */
  protected readonly modelOptions = signal<ModelOption[]>([])
  /** The model chosen for the new case; '' keeps each agent's own model (the default). */
  protected readonly modelName = signal('')
  private readonly modelNamespace = new Subject<string>()
  /**
   * Git workspaces are on and the GIT plugin is loaded, which the integration type catalogue shows.
   * A failure is not kept: the next namespace asks again.
   */
  private readonly gitWorkspaces$ = combineLatest([
    this.workspaces.enabled$,
    defer(() => this.integrationTypeApi.listTypesIntegrationType()).pipe(
      map((types) => types.some((type) => type.type === GIT_INTEGRATION_TYPE))
    ),
  ]).pipe(
    map(([enabled, gitPlugin]) => enabled && gitPlugin),
    shareReplay({ bufferSize: 1, refCount: false })
  )

  /** The async submit chain must stop touching state or navigating once the view is gone. */
  private destroyed = false

  /** True when the message text targets the namespace exchange (previewed on the chips). */
  protected readonly namespaceTargeted = computed(() =>
    isNamespaceTargeted(this.inputValue(), this.exchangeState.canWriteNamespace())
  )

  ngOnInit(): void {
    this.voiceCapability.load()
    // Namespace-only exchange init: no case exists yet, but canWriteNamespace() gating
    // (namespace-intent badge and upload target) needs the namespace manifest.
    this.exchangeState.initializeForNamespace(this.namespaceId)
    this.worktreeNamespace.next(this.namespaceId)
    this.modelNamespace.next(this.namespaceId)

    // The composer is reused when switching namespaces or the parent of a new sub-case.
    this.route.queryParams.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const newNs = params['ns'] as string
      const newParent = (params['parentCase'] as string) || null
      if (newNs && (newNs !== this.namespaceId || newParent !== this.parentCaseId())) {
        this.creationContext++
        this.creationChanged.next()
        this.voice.reset()
        this.namespaceId = newNs
        this.parentCaseId.set(newParent)
        this.autocomplete.init(newNs)
        this.autocomplete.reset()
        this.inputValue.set('')
        this.attachments.reset()
        this.referencedFiles.set([])
        this.pendingCaseId.set(null)
        this.submitError.set('')
        this.isCreating.set(false)
        this.exchangeState.initializeForNamespace(newNs)
        this.worktreeOffered.set(false)
        this.worktreeNamespace.next(newNs)
        this.modelName.set('')
        this.modelOptions.set([])
        this.modelNamespace.next(newNs)
      }
    })
  }

  constructor() {
    this.destroyRef.onDestroy(() => (this.destroyed = true))
    this.autocomplete.init(this.namespaceId)
    // No case yet: `#` lists the namespace exchange only.
    this.autocomplete.enableFileMentions(() => ({ namespaceId: this.namespaceId, caseId: null }))

    // The models of the namespace, for the optional model of the new case. A namespace switch drops the answer
    // still pending for the previous one; a failed read leaves the select with its single "Agent default" entry.
    this.modelNamespace
      .pipe(
        switchMap((namespaceId) => this.aiModels.listAvailable(namespaceId)),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((models) => this.modelOptions.set(chatModelOptions(models)))

    // A namespace switch drops the answer still pending for the previous one. A sub-case shares its
    // root's worktree, so its composer asks nothing.
    this.worktreeNamespace
      .pipe(
        switchMap((namespaceId) =>
          this.parentCaseId()
            ? of(null)
            : this.gitWorkspaces$.pipe(
                switchMap((available) => (available ? this.gitApi.getAssociationNamespaceGit(namespaceId) : of(null))),
                // Nothing readable offers nothing: the composer stays as it is without Git.
                catchError(() => of(null))
              )
        ),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((association) => {
        // Only an associated namespace creates worktrees; an unusable association reports false.
        this.worktreeOffered.set(association?.autoWorktreeForRootCases === true)
        this.worktree.set(false)
      })

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
    return (
      (!!this.inputValue().trim() || this.attachments.hasAttachments() || this.referencedFiles().length > 0) &&
      !this.isCreating() &&
      !this.voice.isBusy()
    )
  }

  /** Files can be staged (picker + drop) while nothing is being created and no recording is under way. */
  protected get canStageFiles(): boolean {
    return !this.isCreating() && !this.voice.isBusy()
  }

  /**
   * The mic is usable where sending is, and while neither a file is staged nor a text typed: the recording is the
   * whole message, and the home composer is left for the case chat on success, which would drop a draft silently.
   */
  protected get canRecord(): boolean {
    return (
      !this.isCreating() &&
      !this.inputValue().trim() &&
      !this.attachments.hasAttachments() &&
      this.referencedFiles().length === 0 &&
      !this.attachments.isUploading() &&
      !this.voice.isBusy()
    )
  }

  /**
   * Asks for the microphone, then records until the user sends or cancels (or the length limit sends). The case is
   * created when the recording is sent, never before: a cancelled recording leaves nothing behind.
   */
  protected startRecording(): void {
    if (!this.canRecord) return
    // Switching namespace or parent abandons this recording, like a submit.
    const namespaceId = this.namespaceId
    const parentCaseId = this.parentCaseId()
    const creationContext = this.creationContext
    let caseId = ''
    this.submitError.set('')
    void this.voice.start(
      async () => {
        let created: string | null
        try {
          created = await firstValueFrom(this.ensureCase$(namespaceId, parentCaseId, creationContext))
        } catch (err) {
          console.error('[CaseHome] Failed to create case for the voice message', err)
          // Shown in the recording bar, with the message the text flow would show.
          throw new Error(this.errorMessage(err))
        }
        // Abandoned: the recorder was reset by the switch and ignores this.
        if (created === null) throw new Error('Voice message abandoned')
        caseId = created
        return created
      },
      {
        onSending: () => this.isCreating.set(true),
        onSendFailed: () => {
          if (!this.abandoned(creationContext)) this.isCreating.set(false)
        },
        onSent: () => {
          if (this.abandoned(creationContext)) return
          this.pendingCaseId.set(null)
          this.router.navigate(['/agentos/home'], { queryParams: { ns: namespaceId, case: caseId } })
          this.isCreating.set(false)
        },
      }
    )
  }

  protected sendRecording(): void {
    void this.voice.stopAndSend()
  }

  protected cancelRecording(): void {
    this.voice.cancel()
    // The recording bar leaves: the textarea is shown again, sized to its content, and takes the focus back.
    afterNextRender(
      () => {
        const input = this.composerInput()?.nativeElement
        input?.focus()
        this.resizeComposer(input)
      },
      { injector: this.injector }
    )
  }

  /** The send button of the footer: while recording it sends the recording, otherwise the typed message. */
  protected onSendClick(): void {
    if (this.voice.isRecording()) {
      this.sendRecording()
    } else {
      void this.submit()
    }
  }

  /** A recording can be sent as soon as it runs; the typed message follows the usual rules. */
  protected get canSendNow(): boolean {
    return this.voice.isRecording() || this.canSend
  }

  /** The key that sends, for the recording hint: the Enter preference decides whether it needs Ctrl/Cmd. */
  protected get sendKeyLabel(): string {
    return this.preferences.enterKeyBehavior() === 'newline' ? 'Ctrl/Cmd+Enter' : 'Enter'
  }

  /**
   * Keys of the recording bar (the textarea is hidden while recording, so it cannot read them): Enter sends the
   * recording under the same preference as a typed message, Escape discards it. A key held down, or pressed again
   * once the recording is on its way (the length limit may have sent it), does nothing.
   */
  protected onRecordingKeydown(event: KeyboardEvent): void {
    if (!this.voice.isRecording()) return
    if (event.key === 'Escape') {
      event.preventDefault()
      this.cancelRecording()
    } else if (this.preferences.shouldSend(event)) {
      event.preventDefault()
      if (!event.repeat) this.sendRecording()
    }
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

  /**
   * A file was checked or unchecked in the drawer, whose checkboxes mirror [referencedFiles]: the chip follows.
   * The focus stays where it is, the drawer remains open for the next file.
   */
  protected onReferenceToggled(change: ExchangeReferenceToggle): void {
    if (change.referenced) {
      this.referencedFiles.update((current) => mergeFileReferences(current, [change.ref]))
    } else {
      this.removeReference(change.ref)
    }
  }

  protected removeReference(ref: ExchangeFileRef): void {
    this.referencedFiles.update((current) => withoutFileReferences(current, [ref]))
  }

  protected onWorktreeChange(event: Event): void {
    this.worktree.set((event.target as HTMLInputElement).checked)
  }

  protected onModelChange(event: Event): void {
    this.modelName.set((event.target as HTMLSelectElement).value)
  }

  protected onKeydown(event: KeyboardEvent): void {
    const consumed = this.autocomplete.onKeydown(
      event,
      this.promptAutocompleteRef,
      this.agentAutocompleteRef,
      undefined,
      this.fileAutocompleteRef
    )
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

  protected onFileSelected(file: FileSuggestion): void {
    this.autocomplete.onFileSelected(file, this.fileAutocompleteRef, this.composerInput, this.inputValue)
  }

  protected closeFileAutocomplete(): void {
    this.autocomplete.closeFileSuggestions()
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
    // Files picked while the case is being created are not part of this message: only these are sent and dropped.
    const references = this.referencedFiles()
    // Switching namespace or parent abandons this submit, even if the user switches back.
    const namespaceId = this.namespaceId
    const parentCaseId = this.parentCaseId()
    const creationContext = this.creationContext
    this.submitError.set('')
    this.isCreating.set(true)

    try {
      // Step 1: create the case — once (see ensureCase$).
      const caseId = await firstValueFrom(this.ensureCase$(namespaceId, parentCaseId, creationContext))
      if (caseId === null) return

      // Keep the same case and local attachments on retry; never fill the worktree before Git does.
      if (this.attachments.hasAttachments()) {
        const state = await firstValueFrom(
          this.workspaces.watch(caseId).pipe(
            filter(
              ({ view }) =>
                !view?.equipped ||
                (view.status !== CaseWorkspaceViewStatusEnum.REQUESTED &&
                  view.status !== CaseWorkspaceViewStatusEnum.PREPARING)
            ),
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
        // Without Git workspaces on this instance there is nothing to wait for.
        const unready = !state.view || (state.view.equipped && state.view.status !== CaseWorkspaceViewStatusEnum.READY)
        if (!workspacesUnavailable(state) && unready) {
          throw new Error(state.view?.failureReason || 'Workspace is not ready. Retry this submission shortly.')
        }
        if (this.abandoned(creationContext)) return
      }

      // Step 2: upload the attachments to the fresh case (or the namespace on explicit
      // request) and reference them in the message content.
      let content = firstMessage
      const mentions: string[] = []
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
        mentions.push(mention)
      }
      const referenceMention = buildReferenceMention(references)
      if (referenceMention) mentions.push(referenceMention)
      if (mentions.length > 0) {
        const block = mentions.join('\n')
        content = content ? `${content}\n\n${block}` : block
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
      this.referencedFiles.update((current) => withoutFileReferences(current, references))
      this.inputValue.set('')
      this.pendingCaseId.set(null)
      this.router.navigate(['/agentos/home'], { queryParams: { ns: namespaceId, case: caseId } })
    } catch (err) {
      if (this.abandoned(creationContext)) return
      console.error('[CaseHome] Failed to create case or send first message', err)
      this.submitError.set(this.errorMessage(err))
    } finally {
      if (!this.abandoned(creationContext)) this.isCreating.set(false)
    }
  }

  /**
   * The case the first message goes to: the one a previous failed attempt created (pendingCaseId), else a new one,
   * so a retry never creates a duplicate. Emits null when the composer moved on (namespace or parent switch,
   * destroyed) meanwhile: the caller stops, and nothing was added to the case list.
   *
   * An Observable rather than an async function so that, as in the text flow before it was shared, nothing is
   * awaited beyond the creation request itself.
   */
  private ensureCase$(
    namespaceId: string,
    parentCaseId: string | null,
    creationContext: number
  ): Observable<string | null> {
    return defer(() => {
      const pending = this.pendingCaseId()
      if (pending) return of(pending)
      return this.caseApi
        .createCase({
          namespaceId,
          ...(parentCaseId ? { parentCaseId } : {}),
          // When the box is offered for a top-level case, the creator's explicit choice is sent (true or false).
          // Nothing is sent otherwise: the backend rejects a worktree choice on a sub-case.
          ...(this.worktreeOffered() && !parentCaseId ? { worktree: this.worktree() } : {}),
          // Only a chosen model is sent: nothing keeps each agent on its own model.
          ...(this.modelName() ? { modelName: this.modelName() } : {}),
          status: CaseStatusEnum.PENDING,
          favorite: false,
          removed: false,
        })
        .pipe(
          map((createdCase) => {
            if (this.abandoned(creationContext)) return null
            this.caseState.addCase(createdCase)
            const caseId = createdCase.id ?? ''
            this.pendingCaseId.set(caseId)
            return caseId
          })
        )
    }).pipe(map((caseId) => (this.abandoned(creationContext) ? null : caseId)))
  }

  /** What the creator is told when creating the case or sending the first message failed. */
  private errorMessage(err: unknown): string {
    return (
      (err as { error?: { message?: string }; message?: string })?.error?.message ||
      (err as Error)?.message ||
      'Could not send the message. Retry to continue with the same case.'
    )
  }

  /** True when the in-flight submit no longer belongs to the displayed composer. */
  private abandoned(creationContext: number): boolean {
    return this.destroyed || this.creationContext !== creationContext
  }
}
