import { DestroyRef, ElementRef, inject, Injectable, Signal, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { AgentConfig, Prompt } from '@whoz-oss/agentos-api-client'
import { catchError, debounceTime, map, of, Subject, switchMap } from 'rxjs'
import { AgentStateService } from '../../services/agent-state.service'
import { filterAndSortAgents } from '../../services/agent.utils'
import { PromptStateService } from '../../services/prompt-state.service'
import { AgentAutocompleteComponent } from '../agent-autocomplete/agent-autocomplete.component'
import { PromptAutocompleteComponent } from '../prompt-autocomplete/prompt-autocomplete.component'

/**
 * ComposerAutocompleteService — component-scoped service centralising slash-command (/)
 * and agent @-mention (@) autocomplete logic.
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
        this.slashSuggestions.set(prompts.filter((p) => p.name.toLowerCase().startsWith(prefix.toLowerCase())))
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
        this.atSuggestions.set(filterAndSortAgents(agents, prefix))
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
    agentRef: Signal<AgentAutocompleteComponent | undefined>
  ): boolean {
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

  // ---------------------------------------------------------------------------
  // Internal — autocomplete dispatch
  // ---------------------------------------------------------------------------

  private updateAutocomplete(value: string): void {
    // Slash commands (/): only active when the value starts with / and has no space yet.
    if (value.startsWith('/')) {
      const withoutSlash = value.slice(1)
      if (!withoutSlash.includes(' ')) {
        if (this.atSuggestions().length) this.atSuggestions.set([])
        if (this.promptsLoaded) {
          this.slashSuggestions.set(
            this.effectivePrompts.filter((p) => p.name.toLowerCase().startsWith(withoutSlash.toLowerCase()))
          )
        }
        this.slashPrefix$.next(withoutSlash)
        return
      }
    }
    if (this.slashSuggestions().length) this.slashSuggestions.set([])

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
