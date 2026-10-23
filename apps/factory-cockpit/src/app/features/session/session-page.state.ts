import { Injectable, computed, inject, signal } from '@angular/core'
import { FactoryStore, buildAgentOsCaseUrl } from '../../core/factory.store'
import { BlockSelection, TimelineBlock } from '../../core/models'

/** Page-scoped selection and derived presentation model for one session. */
@Injectable()
export class SessionPageState {
  private readonly store = inject(FactoryStore)
  private readonly runId = signal('')
  private readonly namespace = signal<string | undefined>(undefined)

  readonly selection = signal<BlockSelection | null>(null)
  readonly session = computed(() => this.store.session(this.runId()))
  readonly run = computed(() => this.store.runs().find((candidate) => candidate.id === this.runId()))
  readonly selectedStepId = computed(() => this.selection()?.stepId ?? '')
  readonly selectedPhase = computed(() => {
    const session = this.session()
    if (!session) return undefined
    const selection = this.selection()
    if (!selection) return session.phase
    if (selection.kind === 'launch_request') return session.launchRequest
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

  reconcileSelection(): void {
    const session = this.session()
    const selection = this.selection()
    if (!session || !selection) return
    const blocks = session.lanes.flatMap((lane) => [lane.request, ...lane.blocks]).filter(Boolean) as TimelineBlock[]
    const stillExists = blocks.some((block) =>
      selection.kind === 'launch_request'
        ? block.kind === 'launch_request'
        : selection.attemptId
          ? block.attemptId === selection.attemptId
          : (block.stepId ?? block.label) === selection.stepId && !block.attemptId
    )
    if (stillExists) return
    const fallback = blocks[0]
    if (fallback) this.selectBlock(fallback)
    else this.selection.set(null)
  }

  selectBlock(block: TimelineBlock): void {
    const selection: BlockSelection = { stepId: block.stepId ?? block.label }
    if (block.kind === 'launch_request') selection.kind = 'launch_request'
    if (block.attemptId) selection.attemptId = block.attemptId
    if (block.caseId) selection.caseId = block.caseId
    this.selection.set(selection)
  }

  caseLink(caseId: string): string {
    return buildAgentOsCaseUrl(caseId, this.namespaceId() || undefined)
  }

  destroy(): void {
    const runId = this.runId()
    if (runId) this.store.clearSessionSelection(runId)
  }
}
