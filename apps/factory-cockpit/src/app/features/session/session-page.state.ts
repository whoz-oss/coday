import { Injectable, computed, inject, signal } from '@angular/core'
import { AGENTOS_BASE_URL, FactoryStore, buildAgentOsCaseUrl } from '../../core/factory.store'
import { BlockSelection, TimelineBlock } from '../../core/models'

/** Page-scoped selection and derived presentation model for one session. */
@Injectable()
export class SessionPageState {
  private readonly store = inject(FactoryStore)
  private readonly agentOsBaseUrl = inject(AGENTOS_BASE_URL)
  private readonly runId = signal('')
  private readonly namespace = signal<string | undefined>(undefined)

  readonly selection = signal<BlockSelection | null>(null)
  readonly session = computed(() => this.store.session(this.runId()))
  readonly run = computed(() => this.store.runs().find((candidate) => candidate.id === this.runId()))
  readonly selectedStepId = computed(() => this.selection()?.stepId ?? '')
  readonly selectedPhase = computed(() => {
    const session = this.session()
    if (!session) return undefined
    const stepId = this.selectedStepId()
    return stepId ? (session.phaseByStepId?.get(stepId) ?? session.phase) : session.phase
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

  selectSession(runId: string, namespaceId?: string): void {
    if (runId === this.runId() && namespaceId === this.namespace()) return
    const previous = this.runId()
    if (previous) this.store.clearSessionSelection(previous)
    this.runId.set(runId)
    this.namespace.set(namespaceId)
    this.selection.set(null)
    if (runId) this.store.selectSession(runId, namespaceId)
  }

  selectDefaultStep(): void {
    const stepId = this.session()?.phase.stepId
    if (stepId && !this.selection()) this.selection.set({ stepId })
  }

  selectBlock(block: TimelineBlock): void {
    const selection: BlockSelection = { stepId: block.stepId ?? block.label }
    if (block.attemptId) selection.attemptId = block.attemptId
    if (block.caseId) selection.caseId = block.caseId
    this.selection.set(selection)
  }

  caseLink(caseId: string): string {
    return buildAgentOsCaseUrl(caseId, this.namespaceId() || undefined, this.agentOsBaseUrl)
  }

  destroy(): void {
    const runId = this.runId()
    if (runId) this.store.clearSessionSelection(runId)
  }
}
