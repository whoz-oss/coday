import { DestroyRef, ElementRef, inject, Injectable, Injector, Signal, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { AgentConfig, ExchangeControllerService, ExchangeManifest, Prompt } from '@whoz-oss/agentos-api-client'
import {
  catchError,
  debounceTime,
  finalize,
  forkJoin,
  map,
  Observable,
  of,
  shareReplay,
  Subject,
  switchMap,
  tap,
} from 'rxjs'
import { AgentStateService } from '../../services/agent-state.service'
import { filterAndSortAgents } from '../../services/agent.utils'
import { PromptStateService } from '../../services/prompt-state.service'
import { AgentAutocompleteComponent } from '../agent-autocomplete/agent-autocomplete.component'
import { EffortAutocompleteComponent } from '../effort-autocomplete/effort-autocomplete.component'
import { FileAutocompleteComponent } from '../file-autocomplete/file-autocomplete.component'
import { PromptAutocompleteComponent } from '../prompt-autocomplete/prompt-autocomplete.component'
import { BTW_COMMAND, BTW_PROMPT } from './btw-command'
import { EFFORT_OPTIONS, EffortOption, effortOptionsFor } from './effort-command'
import {
  applyFileMention,
  FileMentionScope,
  fileMentionPrefix,
  FileSuggestion,
  fileReference,
  manifestToSuggestions,
  rankFiles,
} from './file-mention'

/** How long the files fetched on a `#` stay valid: past it, the next `#` fetches them again. */
export const FILE_CACHE_TTL_MS = 30_000

/** Where the composer is: the exchanges the agent can reach from there. `caseId` is null outside a case. */
export interface FileMentionScopes {
  namespaceId: string
  caseId: string | null
}

/**
 * ComposerAutocompleteService — component-scoped service centralising slash-command (/),
 * agent @-mention (@) and file `#` autocomplete logic.
 *
 * Must be provided at component level (not root) so each composer instance gets its
 * own isolated state:
 *
 *   @Component({ providers: [ComposerAutocompleteService] })
 *
 * ## Usage
 *
 * 1. Call `init(namespaceId)` once the namespace is known (ngOnInit or constructor).
 * 2. Call `reset()` when the namespace changes to clear stale suggestions.
 * 3. Bind `slashSuggestions` and `atSuggestions` signals to the template dropdowns.
 * 4. Delegate `onInput`, `onKeydown`, `onPromptSelected`, `onAgentSelected` from the template.
 *
 * A host that can change the reasoning effort (an open case) also calls `enableEffortCommand`:
 * "/effort" then lists the levels in `effortSuggestions` instead of the matching prompts.
 *
 * A host that offers `/btw` (a side question) calls `enableBtwCommand`: it then heads the slash suggestions, and
 * while the host says a run is in progress it is the only command listed, since nothing else can be sent then.
 *
 * A host that wants file references calls `enableFileMentions`: typing `#` (at the start or after a
 * whitespace) then lists in `fileSuggestions` the files the agent can reach through its exchange tools
 * — the namespace exchange and, inside a case, the case exchange. Selecting one inserts plain text,
 * qualified by the scope so the agent knows which tool to read it with: `namespace:docs/readme.md ` or
 * `case:notes/todo.md `. `#` never conflicts with `@` (agents); the dropdown only opens when a file
 * matches, so an issue reference such as `#123` stays silent.
 */
@Injectable()
export class ComposerAutocompleteService {
  private readonly promptState = inject(PromptStateService)
  private readonly agentState = inject(AgentStateService)
  private readonly destroyRef = inject(DestroyRef)

  // ---------------------------------------------------------------------------
  // Public state — bind to template
  // ---------------------------------------------------------------------------

  /** Filtered prompts matching the current slash prefix. Empty = dropdown closed. */
  readonly slashSuggestions = signal<Prompt[]>([])

  /** Filtered agents matching the current @-mention prefix. Empty = dropdown closed. */
  readonly atSuggestions = signal<AgentConfig[]>([])

  /** /effort levels matching the current value. Empty = dropdown closed. */
  readonly effortSuggestions = signal<EffortOption[]>([])

  /** Accessible files matching the current `#` prefix, best first. Empty = dropdown closed. */
  readonly fileSuggestions = signal<FileSuggestion[]>([])

  // ---------------------------------------------------------------------------
  // Internal state
  // ---------------------------------------------------------------------------

  private namespaceId = ''

  private effectivePrompts: Prompt[] = []
  private promptsLoaded = false
  private readonly slashPrefix$ = new Subject<string>()

  private effectiveAgents: AgentConfig[] = []
  private agentsLoaded = false
  private readonly atPrefix$ = new Subject<string>()

  /** Null until the host enables /effort; then tells whether the effort can be changed right now. */
  private effortCommandAvailable: (() => boolean) | null = null

  /** Null until the host enables /btw; then tells whether it is the only command offered right now. */
  private btwOnly: (() => boolean) | null = null

  /** Null until the host enables `#` file mentions; then tells where the composer is. */
  private fileScopes: (() => FileMentionScopes) | null = null
  /** True while the word being typed is a `#` mention: a late answer must not open another dropdown then. */
  private fileMentionActive = false
  private readonly filePrefix$ = new Subject<string>()
  /** Files fetched for one (namespace, case) pair, valid for [FILE_CACHE_TTL_MS]. */
  private fileCache: { key: string; files: FileSuggestion[]; loadedAt: number } | null = null
  /** The fetch in flight, shared so a burst of keystrokes does not start one request per key. */
  private fileRequest: { key: string; files$: Observable<FileSuggestion[]> } | null = null
  /** Bumped by [reset]: a fetch started before it must not fill the cache afterwards. */
  private fileGeneration = 0
  /**
   * Used to resolve the exchange API on the first `#` only: no other composer feature needs it, and a
   * composer that never asks for a file never instantiates it.
   */
  private readonly injector = inject(Injector)

  constructor() {
    // Slash-command pipeline: debounce → lazy-load prompts on first `/` → filter locally.
    // The prefix is carried through the pipeline so the subscribe callback filters
    // correctly even if the user has continued typing before the HTTP response arrives.
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
        // A late answer must not open the prompt list over the /effort picker or the file list the user has reached since.
        if (this.effortSuggestions().length || this.fileMentionActive) return
        this.slashSuggestions.set(this.slashMatches(prompts, prefix))
      })

    // Agent @-mention pipeline: same debounce pattern, lazy-loads on first `@`.
    this.atPrefix$
      .pipe(
        debounceTime(60),
        switchMap((prefix) => {
          const source$ = this.agentsLoaded
            ? of(this.effectiveAgents)
            : this.agentState.listEffective(this.namespaceId).pipe(catchError(() => of([] as AgentConfig[])))
          return source$.pipe(map((agents) => ({ prefix, agents })))
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe(({ prefix, agents }) => {
        this.effectiveAgents = agents
        this.agentsLoaded = true
        // Same for a late answer: the user may have moved on to a `#` mention since.
        if (this.fileMentionActive) return
        this.atSuggestions.set(filterAndSortAgents(agents, prefix))
      })

    // File `#` pipeline: debounce → lazy-fetch the manifests on the first `#` (cached, see loadFiles) → rank locally.
    this.filePrefix$
      .pipe(
        debounceTime(60),
        switchMap((prefix) => this.loadFiles().pipe(map((files) => ({ prefix, files })))),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe(({ prefix, files }) => {
        // The word may have stopped being a mention while the manifests were loading.
        if (!this.fileMentionActive) return
        this.fileSuggestions.set(rankFiles(files, prefix))
      })
  }

  // ---------------------------------------------------------------------------
  // Lifecycle hooks — called by the host component
  // ---------------------------------------------------------------------------

  /**
   * Set the active namespace. Must be called before the user can type.
   * Safe to call multiple times — idempotent when the namespace hasn't changed.
   */
  init(namespaceId: string): void {
    this.namespaceId = namespaceId
  }

  /**
   * Offer the /effort command in this composer. [available] is checked on each keystroke: the
   * levels are only listed while the effort can be saved (a loaded case that has not ended).
   */
  enableEffortCommand(available: () => boolean): void {
    this.effortCommandAvailable = available
  }

  /**
   * Offer the /btw command in this composer. [onlyCommand] is checked on each keystroke: while it is true (the
   * agent is running) /btw is the only slash suggestion, as it is the only thing that can be sent.
   */
  enableBtwCommand(onlyCommand: () => boolean): void {
    this.btwOnly = onlyCommand
  }

  /**
   * Offer `#` file references in this composer. [scopes] is read when files are needed, so a host whose case
   * changes under the same composer always asks for the current one.
   */
  enableFileMentions(scopes: () => FileMentionScopes): void {
    this.fileScopes = scopes
  }

  /** Open the /effort picker on every level, e.g. after a level the command does not know. */
  showEffortLevels(): void {
    if (!this.effortCommandAvailable?.()) return
    this.slashSuggestions.set([])
    this.atSuggestions.set([])
    this.closeFileSuggestions()
    this.effortSuggestions.set([...EFFORT_OPTIONS])
  }

  /**
   * Clear all autocomplete state (suggestions + loaded caches).
   * Call when the namespace changes so stale data from the previous namespace
   * is never shown.
   */
  reset(): void {
    this.slashSuggestions.set([])
    this.effectivePrompts = []
    this.promptsLoaded = false
    this.atSuggestions.set([])
    this.effectiveAgents = []
    this.agentsLoaded = false
    this.effortSuggestions.set([])
    this.closeFileSuggestions()
    this.fileCache = null
    this.fileRequest = null
    this.fileGeneration++
  }

  // ---------------------------------------------------------------------------
  // Input handling — delegate from the host component's template
  // ---------------------------------------------------------------------------

  /**
   * Update the composer value and trigger autocomplete detection.
   * Call from `(input)` on the textarea.
   */
  onInput(value: string, inputValue: { set: (v: string) => void }): void {
    inputValue.set(value)
    this.updateAutocomplete(value)
  }

  /**
   * Handle keyboard navigation for the active dropdown.
   * Call from `(keydown)` on the textarea, passing the submit callback.
   *
   * Returns true when the event was consumed (caller should not process it further).
   */
  onKeydown(
    event: KeyboardEvent,
    promptRef: Signal<PromptAutocompleteComponent | undefined>,
    agentRef: Signal<AgentAutocompleteComponent | undefined>,
    effortRef?: Signal<EffortAutocompleteComponent | undefined>,
    fileRef?: Signal<FileAutocompleteComponent | undefined>
  ): boolean {
    if (this.effortSuggestions().length > 0) {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault()
        effortRef?.()?.navigate(event.key)
        return true
      }
      if (event.key === 'Escape') {
        event.preventDefault()
        this.effortSuggestions.set([])
        return true
      }
      if (event.key === 'Tab' || event.key === 'Enter') {
        event.preventDefault()
        effortRef?.()?.navigate('Enter')
        return true
      }
    }

    if (this.fileSuggestions().length > 0) {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault()
        fileRef?.()?.navigate(event.key)
        return true
      }
      if (event.key === 'Escape') {
        event.preventDefault()
        this.closeFileSuggestions()
        return true
      }
      if (event.key === 'Tab' || event.key === 'Enter') {
        event.preventDefault()
        fileRef?.()?.navigate('Enter')
        return true
      }
    }

    if (this.slashSuggestions().length > 0) {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault()
        promptRef()?.navigate(event.key)
        return true
      }
      if (event.key === 'Escape') {
        event.preventDefault()
        this.slashSuggestions.set([])
        return true
      }
      if (event.key === 'Tab' || event.key === 'Enter') {
        event.preventDefault()
        promptRef()?.navigate('Enter')
        return true
      }
    }

    if (this.atSuggestions().length > 0) {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault()
        agentRef()?.navigate(event.key)
        return true
      }
      if (event.key === 'Escape') {
        event.preventDefault()
        this.atSuggestions.set([])
        return true
      }
      if (event.key === 'Tab' || event.key === 'Enter') {
        event.preventDefault()
        agentRef()?.navigate('Enter')
        return true
      }
    }

    return false
  }

  /**
   * Apply a prompt selection: replace the composer value with the completion
   * and reset the cursor to the end.
   *
   * @param prompt       The selected prompt.
   * @param promptRef    The PromptAutocompleteComponent (for completionFor()).
   * @param composerRef  The textarea ElementRef (for DOM value + cursor sync).
   * @param inputValue   The signal holding the composer value.
   */
  onPromptSelected(
    prompt: Prompt,
    promptRef: Signal<PromptAutocompleteComponent | undefined>,
    composerRef: Signal<ElementRef<HTMLTextAreaElement> | undefined>,
    inputValue: { set: (v: string) => void }
  ): void {
    const completion = promptRef()?.completionFor(prompt) ?? `/${prompt.name} `
    inputValue.set(completion)
    queueMicrotask(() => {
      const el = composerRef()?.nativeElement
      if (!el) return
      el.value = completion
      el.setSelectionRange(completion.length, completion.length)
      el.focus()
    })
    this.slashSuggestions.set([])
  }

  /**
   * Apply an agent selection: replace the trailing `@<prefix>` in the composer
   * with the full `@name ` completion.
   *
   * @param agent        The selected agent config.
   * @param agentRef     The AgentAutocompleteComponent (for completionFor()).
   * @param composerRef  The textarea ElementRef (for DOM value + cursor sync).
   * @param inputValue   The signal holding the composer value (read + write).
   */
  onAgentSelected(
    agent: AgentConfig,
    agentRef: Signal<AgentAutocompleteComponent | undefined>,
    composerRef: Signal<ElementRef<HTMLTextAreaElement> | undefined>,
    inputValue: { set: (v: string) => void; (): string }
  ): void {
    const completion = agentRef()?.completionFor(agent) ?? `@${agent.name} `
    const replaced = inputValue().replace(/@\w*$/, completion)
    inputValue.set(replaced)
    queueMicrotask(() => {
      const el = composerRef()?.nativeElement
      if (!el) return
      el.value = replaced
      el.setSelectionRange(replaced.length, replaced.length)
      el.focus()
    })
    this.atSuggestions.set([])
  }

  /**
   * Apply a file selection: replace the trailing `#<prefix>` in the composer with the scope-qualified
   * reference (`namespace:<path> ` or `case:<path> `).
   *
   * @param file         The selected file.
   * @param fileRef      The FileAutocompleteComponent (for completionFor()).
   * @param composerRef  The textarea ElementRef (for DOM value + cursor sync).
   * @param inputValue   The signal holding the composer value (read + write).
   */
  onFileSelected(
    file: FileSuggestion,
    fileRef: Signal<FileAutocompleteComponent | undefined>,
    composerRef: Signal<ElementRef<HTMLTextAreaElement> | undefined>,
    inputValue: { set: (v: string) => void; (): string }
  ): void {
    const completion = fileRef()?.completionFor(file) ?? fileReference(file)
    const replaced = applyFileMention(inputValue(), completion)
    inputValue.set(replaced)
    queueMicrotask(() => {
      const el = composerRef()?.nativeElement
      if (!el) return
      el.value = replaced
      el.setSelectionRange(replaced.length, replaced.length)
      el.focus()
    })
    this.closeFileSuggestions()
  }

  /** Close the `#` dropdown, and ignore any file answer still on its way. */
  closeFileSuggestions(): void {
    this.fileMentionActive = false
    if (this.fileSuggestions().length) this.fileSuggestions.set([])
  }

  // ---------------------------------------------------------------------------
  // Internal — file `#` mention data
  // ---------------------------------------------------------------------------

  private fileCacheKey(scopes: FileMentionScopes): string {
    return `${scopes.namespaceId}|${scopes.caseId ?? ''}`
  }

  /** The cached files of the current scope, while they are younger than [FILE_CACHE_TTL_MS]. */
  private freshFiles(): FileSuggestion[] | null {
    const scopes = this.fileScopes?.()
    const cache = this.fileCache
    if (!scopes || !cache || cache.key !== this.fileCacheKey(scopes)) return null
    return Date.now() - cache.loadedAt < FILE_CACHE_TTL_MS ? cache.files : null
  }

  /**
   * Every file the agent can reach from here: the recursive manifest of the namespace exchange and, inside a
   * case, of the case exchange. A scope that answers an error (403/404/409…) contributes nothing: fail-closed
   * and silent, the mention simply does not list it.
   */
  private loadFiles(): Observable<FileSuggestion[]> {
    const scopes = this.fileScopes?.()
    if (!scopes?.namespaceId) return of([])
    const cached = this.freshFiles()
    if (cached) return of(cached)
    const key = this.fileCacheKey(scopes)
    if (this.fileRequest?.key === key) return this.fileRequest.files$

    const controller = this.injector.get(ExchangeControllerService)
    const generation = this.fileGeneration
    const fetchScope = (scope: FileMentionScope, manifest$: Observable<ExchangeManifest>) =>
      manifest$.pipe(
        map((manifest) => manifestToSuggestions(manifest, scope)),
        catchError(() => of([] as FileSuggestion[]))
      )
    const files$ = forkJoin([
      fetchScope('namespace', controller.getNamespaceFilesManifestExchange(scopes.namespaceId)),
      scopes.caseId
        ? fetchScope('case', controller.getCaseFilesManifestExchange(scopes.caseId))
        : of([] as FileSuggestion[]),
    ]).pipe(
      map(([namespaceFiles, caseFiles]) => [...caseFiles, ...namespaceFiles]),
      tap((files) => {
        if (generation === this.fileGeneration) this.fileCache = { key, files, loadedAt: Date.now() }
      }),
      finalize(() => {
        if (generation === this.fileGeneration && this.fileRequest?.key === key) this.fileRequest = null
      }),
      // Not refCounted: a keystroke that supersedes this answer must not cancel the request itself.
      shareReplay({ bufferSize: 1, refCount: false })
    )
    this.fileRequest = { key, files$ }
    return files$
  }

  // ---------------------------------------------------------------------------
  // Internal — autocomplete dispatch
  // ---------------------------------------------------------------------------

  /**
   * The slash suggestions for [prefix]: the prompts it starts, headed by /btw when the host offers it. /btw is a
   * command of the composer that wins over a prompt of the same name; while the agent runs it is alone, since
   * nothing else can be sent.
   */
  private slashMatches(prompts: Prompt[], prefix: string): Prompt[] {
    const wanted = prefix.toLowerCase()
    if (!this.btwOnly) return prompts.filter((p) => p.name.toLowerCase().startsWith(wanted))
    const btw = BTW_COMMAND.startsWith(wanted) ? [BTW_PROMPT] : []
    if (this.btwOnly()) return btw
    const others = prompts.filter(
      (p) => p.name.toLowerCase() !== BTW_COMMAND && p.name.toLowerCase().startsWith(wanted)
    )
    return [...btw, ...others]
  }

  private updateAutocomplete(value: string): void {
    // /effort takes precedence over the prompts whose name starts the same way.
    const effortOptions = this.effortCommandAvailable?.() ? effortOptionsFor(value) : null
    if (effortOptions?.length) {
      this.slashSuggestions.set([])
      this.atSuggestions.set([])
      this.closeFileSuggestions()
      this.effortSuggestions.set(effortOptions)
      return
    }
    if (this.effortSuggestions().length) this.effortSuggestions.set([])

    // Slash commands (/): only active when the value starts with / and has no space yet.
    if (value.startsWith('/')) {
      const withoutSlash = value.slice(1)
      if (!withoutSlash.includes(' ')) {
        if (this.atSuggestions().length) this.atSuggestions.set([])
        this.closeFileSuggestions()
        if (this.promptsLoaded) {
          this.slashSuggestions.set(this.slashMatches(this.effectivePrompts, withoutSlash))
        } else if (this.btwOnly) {
          // /btw needs no prompt: it is offered at once, the prompts join it once loaded.
          this.slashSuggestions.set(this.slashMatches([], withoutSlash))
        }
        this.slashPrefix$.next(withoutSlash)
        return
      }
    }
    if (this.slashSuggestions().length) this.slashSuggestions.set([])

    // File mention (#): the word being typed starts with # (at the start or after a whitespace). It only
    // opens once a file matches, so `#123` with no such file stays silent. Mutually exclusive with the others.
    const filePrefix = this.fileScopes ? fileMentionPrefix(value) : null
    if (filePrefix !== null) {
      this.fileMentionActive = true
      if (this.atSuggestions().length) this.atSuggestions.set([])
      const cached = this.freshFiles()
      if (cached) this.fileSuggestions.set(rankFiles(cached, filePrefix))
      this.filePrefix$.next(filePrefix)
      return
    }
    this.closeFileSuggestions()

    // Agent @-mention (@): detects the last @ not followed by a space.
    const atMatch = value.match(/@(\w*)$/)
    if (atMatch) {
      const prefix = atMatch[1] ?? ''
      if (this.agentsLoaded) {
        this.atSuggestions.set(filterAndSortAgents(this.effectiveAgents, prefix))
      }
      this.atPrefix$.next(prefix)
      return
    }
    if (this.atSuggestions().length) this.atSuggestions.set([])
  }
}
