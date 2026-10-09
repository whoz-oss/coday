import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core'
import { MatTooltipModule } from '@angular/material/tooltip'
import { PhaseSegment } from '../../core/models'

/**
 * Compact representation of a run's steps as small dots.
 *
 * Colours by status:
 * - done           → green  (--sf-success)
 * - failed         → red   (--sf-error)
 * - indeterminate  → muted red (opacity 0.6, distinct from failed)
 * - cancelled      → neutral grey (--sf-outline-strong)
 * - running        → animated cyan pulse (1.8s)
 * - waiting_human  → amber, NEVER animated
 * - pending        → dark grey (--sf-outline)
 *
 * Accessibility: tooltip title + aria-label = "name: readable status".
 * Keyboard focus visible (:focus-visible outline violet).
 * prefers-reduced-motion: pulse animation disabled.
 */
@Component({
  selector: 'sf-step-dots',
  imports: [MatTooltipModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="dots" [attr.aria-label]="ariaLabel()" role="list">
      @for (step of phases(); track step.key) {
        <li
          class="dot"
          [class]="dotClass(step)"
          tabindex="0"
          [matTooltip]="dotLabel(step)"
          matTooltipShowDelay="200"
          [attr.aria-label]="dotLabel(step)"
          role="listitem"
        ></li>
      }
    </ol>
  `,
  styles: `
    :host {
      display: block;
    }

    .dots {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 7px;
    }

    .dot {
      width: 12px;
      height: 12px;
      border-radius: 50%;
      flex-shrink: 0;
      cursor: default;
      outline-offset: 2px;
    }
    .dot:focus-visible {
      outline: 2px solid var(--sf-primary);
    }

    /* \u2500\u2500 Statuses \u2500\u2500 */
    .dot--done {
      background: var(--sf-success);
    }
    .dot--failed {
      background: var(--sf-error);
    }
    /* indeterminate: same red but muted to distinguish from failed */
    .dot--indeterminate {
      background: var(--sf-error);
      opacity: 0.55;
    }
    /* cancelled: neutral fixed, no success or failure connotation */
    .dot--cancelled {
      background: var(--sf-outline-strong);
    }
    .dot--running {
      background: var(--sf-secondary);
      animation: sf-dot-pulse 1.8s ease-in-out infinite;
    }
    /* waiting_human: fixed amber \u2014 NEVER animated */
    .dot--waiting-human {
      background: var(--sf-tertiary);
    }
    .dot--pending {
      background: var(--sf-outline);
    }

    @keyframes sf-dot-pulse {
      0%,
      100% {
        box-shadow: 0 0 0 0 rgba(95, 212, 224, 0);
      }
      50% {
        box-shadow: 0 0 0 4px rgba(95, 212, 224, 0.22);
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .dot--running {
        animation: none;
      }
    }
  `,
})
export class StepDotsComponent {
  readonly phases = input.required<PhaseSegment[]>()

  readonly ariaLabel = computed(() => {
    const steps = this.phases()
    if (steps.length === 0) return 'No steps'
    return `Steps: ${steps.map((s) => this.dotLabel(s)).join(', ')}`
  })

  dotClass(step: PhaseSegment): string {
    switch (step.status) {
      case 'done':
        return 'dot--done'
      case 'failed':
        return 'dot--failed'
      case 'indeterminate':
        return 'dot--indeterminate'
      case 'cancelled':
        return 'dot--cancelled'
      case 'running':
        return 'dot--running'
      case 'waiting_human':
        return 'dot--waiting-human'
      case 'pending':
        return 'dot--pending'
    }
  }

  dotLabel(step: PhaseSegment): string {
    const labels: Record<PhaseSegment['status'], string> = {
      done: 'completed',
      failed: 'failed',
      indeterminate: 'indeterminate',
      cancelled: 'cancelled',
      running: 'running',
      waiting_human: 'waiting for human',
      pending: 'waiting',
    }
    // Prefer the readable label (name from backend, or synthetic label)
    // over the raw id key.
    const name = step.label ?? step.key
    return `${name}: ${labels[step.status]}`
  }
}
