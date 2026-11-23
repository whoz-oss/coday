import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { finalize } from 'rxjs'
import { FactoryApiError } from '../../core/factory-api.service'
import { FactoryStore, buildAgentOsCaseUrl } from '../../core/factory.store'
import { BlockSelection, TimelineBlock } from '../../core/models'
import { WorkflowDraftHandoffService } from '../../core/workflow-draft-handoff.service'

/** Page-scoped selection and derived presentation model for one session. */
@Injectable()
export class SessionPageState {
  private readonly store = inject(FactoryStore)
  private readonly router = inject(Router)
  private readonly draftHandoff = inject(WorkflowDraftHandoffService)
  private readonly destroyRef = inject(DestroyRef)
  private readonly runId = signal('')
  private readonly namespace = signal<string | undefined>(undefined)

  /** True while the draft of the followed definition is being read. */
  readonly savingDefinition = signal(false)
  /** Failure of the last "save as reusable workflow" attempt, shown in the page banner. */
  readonly saveDefinitionError = signal<string | null>(null)

  readonly selection = signal<BlockSelection | null>(null)
  /**
   * Automatic selection follows the current backend phase after every live
   * projection update. A selection made from the diagram or timeline pins the
   * requested step instead.
   */
  private readonly selectionPinnedByUser = signal(false)
  readonly session = computed(() => this.store.session(this.runId()))
  readonly parentRun = computed(() => this.store.parentRunFor(this.runId()))
  readonly run = computed(() => this.store.runs().find((candidate) => candidate.id === this.runId()))
  readonly selectedStepId = computed(() => this.selection()?.stepId ?? '')
  readonly selectedPhase = computed(() => {
    const session = this.session()
    if (!session) return undefined
    const selection = this.selection()
    if (!selection) return session.phase
    if (selection.kind === 'launch_request') return session.launchRequest
    // An outcome ledger selection is more specific than its linked agent attempt.
    if (selection.executionId) return session.phaseByExecutionId?.get(selection.executionId)
    if (selection.attemptId) return session.phaseByAttemptId?.get(selection.attemptId)
    return session.phaseByStepId?.get(selection.stepId)
  })
  readonly selectedAttemptCaseId = computed(() => this.selection()?.caseId ?? this.selectedPhase()?.caseId)
  readonly selectedAttempt = computed(() => {
    const session = this.session()
    const attemptId = this.selection()?.attemptId
    return attemptId ? (session?.attempts?.find((attempt) => attempt.attemptId === attemptId) ?? null) : null
  })
  readonly selectedEvents = computed(() => {
    const session = this.session()
    if (!session) return []
    const selection = this.selection()
    if (!selection) return session.events
    if (selection.kind === 'launch_request') return []
    if (selection.executionId) {
      return session.eventsByExecutionId ? (session.eventsByExecutionId.get(selection.executionId) ?? []) : null
    }
    if (selection.attemptId) {
      return session.eventsByAttemptId ? (session.eventsByAttemptId.get(selection.attemptId) ?? []) : null
    }
    return session.eventsByStepId ? (session.eventsByStepId.get(selection.stepId) ?? []) : null
  })
  readonly namespaceId = computed(() => {
    const runNamespace = this.run()?.namespaceId
    if (runNamespace) return runNamespace
    const session = this.session()
    return (
      session?.agentQuestions?.find((question) => question.case?.namespaceId)?.case?.namespaceId ??
      session?.agentQuestionsError?.namespaceId ??
      ''
    )
  })

  /** Only a run that received steps from an Architect continuation has a definition worth saving. */
  readonly canSaveAsWorkflow = computed(() => !!this.session()?.expansion)

  selectSession(runId: string, namespaceId?: string): void {
    if (runId === this.runId() && namespaceId === this.namespace()) return
    this.saveDefinitionError.set(null)
    const previous = this.runId()
    if (previous) this.store.clearSessionSelection(previous)
    this.runId.set(runId)
    this.namespace.set(namespaceId)
    this.selection.set(null)
    this.selectionPinnedByUser.set(false)
    if (runId) {
      this.store.selectSession(runId, namespaceId)
      if (runId.includes('__f')) {
        this.store.loadParentRun(runId, namespaceId)
      }
    }
  }

  selectDefaultStep(): void {
    const stepId = this.session()?.phase.stepId
    const currentSelection = this.selection()
    // Retain the selection object for an unchanged active step. Consumers use
    // its identity as a context-change signal (for example, EventLog resets
    // filters), so every SSE projection must not look like a new selection.
    if (stepId && !this.selectionPinnedByUser() && currentSelection?.stepId !== stepId) {
      this.selection.set({ stepId })
    }
  }

  selectStep(stepId: string): void {
    this.selectionPinnedByUser.set(true)
    this.selection.set({ stepId })
  }

  reconcileSelection(): void {
    // Automatic selection follows the current phase directly; only a pinned
    // user selection needs validity reconciliation against the refreshed view.
    if (!this.selectionPinnedByUser()) return
    const session = this.session()
    const selection = this.selection()
    if (!session || !selection) return
    const blocks = session.lanes.flatMap((lane) => [lane.request, ...lane.blocks]).filter(Boolean) as TimelineBlock[]
    const stillExists = blocks.some((block) =>
      selection.kind === 'launch_request'
        ? block.kind === 'launch_request'
        : selection.executionId
          ? block.executionId === selection.executionId
          : selection.attemptId
            ? block.attemptId === selection.attemptId
            : (block.stepId ?? block.label) === selection.stepId && !block.attemptId && !block.executionId
    )
    if (stillExists || (!selection.attemptId && !selection.executionId && session.routingSteps?.some((step) => step.id === selection.stepId)))
      return
    const fallback = blocks[0]
    if (fallback) this.selectBlock(fallback)
    else this.selection.set(null)
  }

  selectBlock(block: TimelineBlock): void {
    this.selectionPinnedByUser.set(true)
    const selection: BlockSelection = { stepId: block.stepId ?? block.label }
    if (block.kind === 'launch_request') selection.kind = 'launch_request'
    if (block.attemptId) selection.attemptId = block.attemptId
    if (block.caseId) selection.caseId = block.caseId
    if (block.executionId) selection.executionId = block.executionId
    this.selection.set(selection)
  }

  /**
   * Read the draft definition of this run, hand it to the workflow composer and open it. The draft is only
   * pre-filled there: the author picks type, version and title, and registration stays an explicit save.
   */
  saveAsReusableWorkflow(): void {
    const runId = this.runId()
    const namespaceId = this.namespaceId()
    if (!this.canSaveAsWorkflow() || !runId || this.savingDefinition()) return
    if (!namespaceId) {
      this.saveDefinitionError.set('The namespace of this run is unknown, so its workflow cannot be read.')
      return
    }
    this.savingDefinition.set(true)
    this.saveDefinitionError.set(null)
    this.store
      .expandedDefinition(runId, namespaceId)
      .pipe(
        finalize(() => this.savingDefinition.set(false)),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: ({ definition }) => {
          this.draftHandoff.set(definition)
          // The draft must not outlive a navigation that does not happen (cancelled, superseded or failed):
          // a later unrelated visit to the composer would otherwise open pre-filled with this run's steps.
          this.router.navigate(['/workflows', 'new']).then(
            (navigated) => {
              if (!navigated) this.draftHandoff.clear()
            },
            () => this.draftHandoff.clear()
          )
        },
        error: (error: FactoryApiError) =>
          this.saveDefinitionError.set(
            `${error.message || 'Unable to read the workflow followed by this run.'}${error.code ? ` (${error.code})` : ''}`
          ),
      })
  }

  caseLink(caseId: string): string {
    return buildAgentOsCaseUrl(caseId, this.namespaceId() || undefined)
  }

  destroy(): void {
    const runId = this.runId()
    if (runId) this.store.clearSessionSelection(runId)
  }
}
