import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  NgZone,
  computed,
  inject,
  input,
  linkedSignal,
  signal,
  untracked,
} from '@angular/core'
import { AgentActivity } from './agent-activity.models'

/** How long a step must last before the line starts counting the seconds out loud. */
const ELAPSED_THRESHOLD_SECONDS = 5

const SECONDS_PER_MINUTE = 60

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

  private readonly zone = inject(NgZone)

  /** Advanced by the ticker; the only thing that moves the counter. */
  private readonly now = signal(Date.now())

  /**
   * When the current step started: the timestamp of the event that named it, so the count is
   * right after a reconnection or a spell in a background tab, and the moment the sentence
   * appeared when no event carries one.
   *
   * Keyed on the sentence so it is read once per step: the activity object itself is rebuilt on
   * every frame of the stream, and `since` is read outside the reactive context for that reason.
   */
  private readonly startedAt = linkedSignal<string, number>({
    source: () => this.activity().label,
    computation: () => {
      const since = untracked(() => this.activity().since)
      const parsed = since ? Date.parse(since) : Number.NaN
      return Number.isNaN(parsed) ? Date.now() : parsed
    },
  })

  /**
   * The sentence as a single-item list: `@for` tracks it by its own text, so a new sentence
   * replaces the node and replays the entrance animation, while a sentence that has not
   * changed stays where it is.
   */
  protected readonly lines = computed<AgentActivity[]>(() => [this.activity()])

  /** The counter, shown once the step has lasted long enough to be worth timing. */
  protected readonly elapsed = computed<string | null>(() => {
    // A step started by the server can be timestamped slightly ahead of this clock.
    const seconds = Math.max(0, Math.floor((this.now() - this.startedAt()) / 1000))
    if (seconds < ELAPSED_THRESHOLD_SECONDS) return null
    if (seconds < SECONDS_PER_MINUTE) return `${seconds}s`
    return `${Math.floor(seconds / SECONDS_PER_MINUTE)}m ${seconds % SECONDS_PER_MINUTE}s`
  })

  constructor() {
    // Outside the Angular zone on purpose: the signal write refreshes this line, where a
    // zone-patched timer would run a change-detection pass over the whole chat every second.
    const ticker = this.zone.runOutsideAngular(() => setInterval(() => this.now.set(Date.now()), 1000))
    inject(DestroyRef).onDestroy(() => clearInterval(ticker))
  }
}
