import { ChangeDetectionStrategy, Component, input } from '@angular/core'
import { SlicePipe } from '@angular/common'
import { PhaseEventRow } from '../factory-phase-events.utils'

/**
 * FactoryPhaseEventRowComponent — renders a single typed event row.
 *
 * Receives a fully-typed PhaseEventRow and narrows the union via
 * explicit type-guard getters, eliminating all $any() usage from
 * the parent template.
 */
@Component({
  selector: 'agentos-factory-phase-event-row',
  imports: [SlicePipe],
  templateUrl: './factory-phase-event-row.component.html',
  styleUrl: './factory-phase-event-row.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FactoryPhaseEventRowComponent {
  readonly row = input.required<PhaseEventRow>()

  // ---------------------------------------------------------------------------
  // Type-guard accessors — narrow the union for template use
  // ---------------------------------------------------------------------------

  protected asMessage(row: PhaseEventRow) {
    return row.kind === 'message' ? row : null
  }

  protected asAgentSelected(row: PhaseEventRow) {
    return row.kind === 'agent-selected' ? row : null
  }

  protected asAgentRunning(row: PhaseEventRow) {
    return row.kind === 'agent-running' ? row : null
  }

  protected asAgentFinished(row: PhaseEventRow) {
    return row.kind === 'agent-finished' ? row : null
  }

  protected asCaseStatus(row: PhaseEventRow) {
    return row.kind === 'case-status' ? row : null
  }

  protected asTool(row: PhaseEventRow) {
    return row.kind === 'tool' ? row : null
  }

  protected asWarn(row: PhaseEventRow) {
    return row.kind === 'warn' ? row : null
  }

  protected asError(row: PhaseEventRow) {
    return row.kind === 'error' ? row : null
  }

  protected asIntention(row: PhaseEventRow) {
    return row.kind === 'intention' ? row : null
  }

  protected asQuestion(row: PhaseEventRow) {
    return row.kind === 'question' ? row : null
  }

  protected asAnswer(row: PhaseEventRow) {
    return row.kind === 'answer' ? row : null
  }

  protected asUnknown(row: PhaseEventRow) {
    return row.kind === 'unknown' ? row : null
  }
}
