import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, linkedSignal } from '@angular/core'
import { AgentActivity } from './agent-activity.models'

/** How long a step must last before the line starts counting the seconds out loud. */
const ELAPSED_THRESHOLD_SECONDS = 5

/**
 * AgentActivityComponent — the line shown while an agent holds the turn, in place of a spinner.
 *
 * It says one thing at a time: what the agent is doing now and, once a step lasts long enough
 * to wonder about, how long it has been on it. Each sentence replaces the previous one with a
 * short entrance animation, so the chat shows progress without filling up with status lines.
 *
 * Usage:
 *   <agentos-agent-activity [activity]="activity()" />
 */
@Component({
  selector: 'agentos-agent-activity',
  templateUrl: './agent-activity.component.html',
  styleUrl: './agent-activity.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AgentActivityComponent {
  /** The step to announce. */
  readonly activity = input.required<AgentActivity>()

  /** Seconds spent on the current step: counted by the ticker, back to zero on a new sentence. */
  private readonly seconds = linkedSignal<string, number>({
    source: () => this.activity().label,
    computation: () => 0,
  })

  /**
   * The sentence as a single-item list: `@for` tracks it by its own text, so a new sentence
   * replaces the node and replays the entrance animation, while a sentence that has not
   * changed stays where it is.
   */
  protected readonly lines = computed<AgentActivity[]>(() => [this.activity()])

  /** The counter, shown once the step has lasted long enough to be worth timing. */
  protected readonly elapsed = computed<string | null>(() => {
    const seconds = this.seconds()
    return seconds >= ELAPSED_THRESHOLD_SECONDS ? `${seconds}s` : null
  })

  constructor() {
    const ticker = setInterval(() => this.seconds.update((value) => value + 1), 1000)
    inject(DestroyRef).onDestroy(() => clearInterval(ticker))
  }
}
