import { DecimalPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { CaseRunCostStateService } from '../../services/case-run-cost-state.service'
import { CostPipe } from '../../pipes/cost.pipe'

/**
 * The cost-threshold decision of a paused run, asked in the composer like a question to the user: continue with a
 * doubled threshold, or stop. Presentational: the state, the poll and the actions are those of
 * {@link CaseRunCostStateService}, provided by the chat. Renders nothing unless usage tracking is on and the run waits.
 *
 * The root carries `data-cost-pause-controls`, which the chat uses to move the focus to the first action.
 */
@Component({
  selector: 'agentos-cost-pause-card',
  imports: [DecimalPipe, CostPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './cost-pause-card.component.html',
  styleUrl: './cost-pause-card.component.scss',
})
export class CostPauseCardComponent {
  protected readonly state = inject(CaseRunCostStateService)

  /** Who the paused conversation is, relative to the one on display. */
  protected subject(limit: { ancestor: boolean; caseId: string }): string {
    if (limit.ancestor) return 'the parent conversation'
    return limit.caseId === this.state.caseId() ? 'this conversation' : 'the delegated conversation'
  }

  protected label(limit: { ancestor: boolean; caseId: string }): string {
    if (limit.ancestor) return 'Parent conversation'
    return limit.caseId === this.state.caseId() ? 'Conversation' : 'Delegated run'
  }
}
