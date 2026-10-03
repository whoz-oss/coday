import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { PlanChangeProposalDto } from '../../../core/models/workstream.model'

/** Plan-change decision intent emitted by the view. */
export interface PlanChangeDecision {
  proposalId: string
  decision: 'approve' | 'reject'
}

/**
 * Plan changes proposal view (placeholder): proposed plan operations with their
 * summary, reason/impact and approve / reject decision buttons.
 *
 * `propose_plan_change` never applies anything by itself (Phase 0 doc §6.2) —
 * the Factory validates and decides. This view only surfaces the decision intent.
 */
@Component({
  selector: 'app-plan-changes',
  standalone: true,
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './plan-changes.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './plan-changes.component.scss',
})
export class PlanChangesComponent {
  @Input() proposals: PlanChangeProposalDto[] = []

  @Output() decide = new EventEmitter<PlanChangeDecision>()

  // TODO(Phase 6): wire the decision to the real plan-change decision endpoint (TBD in Phase 0 doc).
  protected onDecide(proposal: PlanChangeProposalDto, decision: 'approve' | 'reject'): void {
    this.decide.emit({ proposalId: proposal.proposalId, decision })
  }
}
