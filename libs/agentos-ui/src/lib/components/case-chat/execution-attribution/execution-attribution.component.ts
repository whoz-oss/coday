import { ChangeDetectionStrategy, Component, input } from '@angular/core'
import { AgentRunningEvent } from '@whoz-oss/agentos-api-client'

/** Public, lightweight attribution for the start of an agent execution. */
@Component({
  selector: 'agentos-execution-attribution',
  templateUrl: './execution-attribution.component.html',
  styleUrl: './execution-attribution.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExecutionAttributionComponent {
  readonly event = input.required<AgentRunningEvent>()
}
