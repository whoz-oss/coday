import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, input, signal } from '@angular/core'
import { SlicePipe } from '@angular/common'
import { toObservable } from '@angular/core/rxjs-interop'
import { switchMap, of, timer, EMPTY } from 'rxjs'
import { catchError } from 'rxjs/operators'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { CaseEventRestControllerService } from '@whoz-oss/agentos-api-client'
import { FactoryRunPhase } from '../../services/factory-api.service'
import {
  projectPhaseEvidence,
  projectPhaseFacts,
  projectPhaseBriefFromFacts,
  projectBriefResponseFromEvents,
  PhaseBriefResponse,
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
  private readonly destroyRef = inject(DestroyRef)

  constructor() {
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
}
