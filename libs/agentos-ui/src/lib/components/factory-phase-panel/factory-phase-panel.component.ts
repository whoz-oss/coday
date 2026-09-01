import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, input, signal } from '@angular/core'
import { SlicePipe } from '@angular/common'
import { toObservable } from '@angular/core/rxjs-interop'
import { switchMap, of, timer, EMPTY } from 'rxjs'
import { catchError } from 'rxjs/operators'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { CaseEventRestControllerService } from '@whoz-oss/agentos-api-client'
import { FactoryApiService, FactoryRunPhase, JiraTicketResponse } from '../../services/factory-api.service'
import {
  projectPhaseEvidence,
  projectPhaseFacts,
  projectPhaseBriefFromFacts,
  projectBriefResponseFromEvents,
  projectReviewOutcomes,
  projectFetchTicketInfo,
  PhaseBriefResponse,
  ReviewOutcomesProjection,
  FetchTicketInfo,
} from './factory-phase-panel.models'
import {
  extractPhaseCaseId,
  projectPhaseEventRows,
  PhaseEventRow,
  PhaseEventRowKind,
} from './factory-phase-events.utils'
import { FactoryPhaseEventRowComponent } from './factory-phase-event-row/factory-phase-event-row.component'

/** Poll interval (ms) used when the phase is still running. */
const LIVE_POLL_INTERVAL_MS = 5_000

export type PhaseEventsState =
  | { status: 'idle' }
  | { status: 'no-case-id' }
  | { status: 'loading' }
  | { status: 'loaded'; rows: PhaseEventRow[] }
  | { status: 'empty' }
  | { status: 'error'; message: string }

/**
 * State for the live Jira ticket fetch.
 *
 * no-credentials: server returned 501 — Jira not configured on the server.
 * error: fetch failed for another reason.
 */
export type JiraState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'loaded'; ticket: JiraTicketResponse }
  | { status: 'no-credentials' }
  | { status: 'error'; message: string }

const KIND_LABELS: Record<PhaseEventRowKind, string> = {
  message: 'Message',
  'agent-selected': 'Agent',
  'agent-running': 'Running',
  'agent-finished': 'Finished',
  'case-status': 'Status',
  tool: 'Tool',
  warn: 'Warn',
  error: 'Error',
  intention: 'Intention',
  question: 'Question',
  answer: 'Answer',
  unknown: 'Event',
}

@Component({
  selector: 'agentos-factory-phase-panel',
  imports: [SlicePipe, FactoryPhaseEventRowComponent],
  templateUrl: './factory-phase-panel.component.html',
  styleUrl: './factory-phase-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FactoryPhasePanelComponent {
  readonly phase = input.required<FactoryRunPhase>()

  protected readonly sections = computed(() => projectPhaseFacts(this.phase()))
  protected readonly evidence = computed(() => projectPhaseEvidence(this.phase()))
  protected readonly reviewOutcomes = computed((): ReviewOutcomesProjection => projectReviewOutcomes(this.phase()))

  /** Fetch-ticket metadata projected from facts. null when not a fetch-ticket phase. */
  protected readonly fetchTicketInfo = computed((): FetchTicketInfo | null => projectFetchTicketInfo(this.phase()))

  /** Jira live-fetch state. Only active when fetchTicketInfo is non-null. */
  protected readonly jiraState = signal<JiraState>({ status: 'idle' })

  /**
   * Brief + agent-response, resolved in priority order:
   * 1. From loaded event rows (authoritative, full content from AgentOS)
   * 2. From phase-recorded facts.messages / facts.conversation (stable snapshot)
   * 3. null / null (no data available)
   */
  protected readonly briefResponse = computed((): PhaseBriefResponse => {
    const state = this.eventsState()
    if (state.status === 'loaded' && state.rows.length > 0) {
      const fromEvents = projectBriefResponseFromEvents(state.rows)
      if (fromEvents.brief !== null || fromEvents.agentResponse !== null) {
        return fromEvents
      }
    }
    return projectPhaseBriefFromFacts(this.phase())
  })

  /** Count of events for the header label. */
  protected readonly eventCount = computed(() => {
    const state = this.eventsState()
    return state.status === 'loaded' ? (state as Extract<PhaseEventsState, { status: 'loaded' }>).rows.length : null
  })

  protected readonly eventsState = signal<PhaseEventsState>({ status: 'idle' })

  private readonly caseEventRest = inject(CaseEventRestControllerService)
  private readonly factoryApi = inject(FactoryApiService)
  private readonly destroyRef = inject(DestroyRef)

  constructor() {
    // When the phase carries a ticketId, load the Jira content live.
    // switchMap automatically cancels any prior in-flight request on phase change.
    toObservable(this.phase)
      .pipe(
        switchMap((phase) => {
          const info = projectFetchTicketInfo(phase)
          if (!info) {
            this.jiraState.set({ status: 'idle' })
            return EMPTY
          }
          this.jiraState.set({ status: 'loading' })
          return this.factoryApi.getJiraTicket(info.ticketId).pipe(
            catchError((err: unknown) => {
              const httpErr = err as { status?: number; error?: { error?: string } }
              if (httpErr?.status === 501) {
                this.jiraState.set({ status: 'no-credentials' })
              } else {
                const msg = httpErr?.error?.error ?? (err instanceof Error ? err.message : 'Failed to load Jira ticket')
                this.jiraState.set({ status: 'error', message: msg })
              }
              return EMPTY
            })
          )
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((ticket: JiraTicketResponse) => {
        this.jiraState.set({ status: 'loaded', ticket })
      })

    /**
     * toObservable() remplace le pattern Subject + effect().
     * switchMap cancels automatiquement le load/poll précédent à chaque
     * changement de phase — comportement identique, sans bridge intermédiaire.
     */
    toObservable(this.phase)
      .pipe(
        switchMap((phase) => {
          const caseId = extractPhaseCaseId(phase)
          if (!caseId) {
            this.eventsState.set({ status: 'no-case-id' })
            return EMPTY
          }

          this.eventsState.set({ status: 'loading' })

          // For a running phase, poll at LIVE_POLL_INTERVAL_MS.
          // For finished phases, a single REST load is sufficient.
          const isRunning = phase.status === 'running' || phase.status === 'RUNNING'
          const trigger$ = isRunning ? timer(0, LIVE_POLL_INTERVAL_MS) : of(0)

          return trigger$.pipe(
            switchMap(() =>
              this.caseEventRest.listByCaseCaseEventRest(caseId).pipe(
                catchError((err: unknown) => {
                  const msg = err instanceof Error ? err.message : 'Failed to load case events'
                  this.eventsState.set({ status: 'error', message: msg })
                  return EMPTY
                })
              )
            )
          )
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((rawEvents) => {
        const events = rawEvents as import('@whoz-oss/agentos-api-client').CaseEvent[]
        const rows = projectPhaseEventRows(events)
        this.eventsState.set(rows.length === 0 ? { status: 'empty' } : { status: 'loaded', rows })
      })
  }

  protected eventKindLabel(kind: PhaseEventRowKind): string {
    return KIND_LABELS[kind] ?? 'Event'
  }

  /** Type-safe accessor for the loaded rows — used in the template with @let. */
  protected loadedRows(state: PhaseEventsState): PhaseEventRow[] {
    return state.status === 'loaded' ? state.rows : []
  }

  /** Type-safe accessor for the error message — used in the template with @let. */
  protected errorMessage(state: PhaseEventsState): string {
    return state.status === 'error' ? state.message : ''
  }

  /** Type-safe accessor for the loaded Jira ticket — used in the template with @let. */
  protected loadedTicket(state: JiraState): JiraTicketResponse | null {
    return state.status === 'loaded' ? state.ticket : null
  }

  /** Type-safe accessor for the Jira error message. */
  protected jiraErrorMessage(state: JiraState): string {
    return state.status === 'error' ? state.message : ''
  }
}
