import { DecimalPipe } from '@angular/common'
import { CostPipe } from '../../pipes/cost.pipe'
import { TokenCountPipe } from '../../pipes/token-count.pipe'
import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { CaseRunCostStateService } from '../../services/case-run-cost-state.service'

/**
 * Read-only usage strip of the case: the figures of the run and of the conversation. It holds no decision: when the
 * run reaches its cost threshold the strip says so, and the decision is asked in the composer (agentos-cost-pause-card).
 * The state, and its polling, belong to the chat that provides {@link CaseRunCostStateService}.
 */
@Component({
  selector: 'agentos-case-usage',
  imports: [DecimalPipe, CostPipe, TokenCountPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './case-usage.component.html',
  styleUrl: './case-usage.component.scss',
})
export class CaseUsageComponent {
  private readonly runCost = inject(CaseRunCostStateService)
  readonly configuration = this.runCost.configuration
  readonly run = this.runCost.run
  readonly total = this.runCost.total
  readonly error = this.runCost.error
}
