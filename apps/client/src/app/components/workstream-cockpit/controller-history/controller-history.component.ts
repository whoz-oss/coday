import { ChangeDetectionStrategy, Component, Input } from '@angular/core'
import { MatIconModule } from '@angular/material/icon'
import { ControllerHistoryDto } from '../../../core/models/workstream.model'

/**
 * Controller history view: timeline of controller CASE transitions, visually
 * distinct from worker execution steps (Phase 0 doc §1.2 distinguishes
 * `WorkflowTransitionNode` worker steps from controller cases).
 */
@Component({
  selector: 'app-controller-history',
  standalone: true,
  imports: [MatIconModule],
  templateUrl: './controller-history.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './controller-history.component.scss',
})
export class ControllerHistoryComponent {
  @Input() history: ControllerHistoryDto | null = null
}
