import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core'
import { PhaseSegment } from '../../core/models'

/** Barre horizontale des phases d'un run ; la phase en cours est hachurée. */
@Component({
  selector: 'sf-phase-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bar" role="img" [attr.aria-label]="ariaLabel()" [style.height.px]="height()">
      @for (p of phases(); track p.key) {
        <span
          class="seg"
          [class]="'seg--' + p.tone"
          [class.running]="p.status === 'running'"
          [style.flex-grow]="p.ratio"
        ></span>
      }
    </div>
    @if (showLegend()) {
      <span class="legend">
        @for (p of phases(); track p.key; let last = $last) {
          <span [class.current]="p.status === 'running'">{{ p.key }}</span>
          @if (!last) {
            ·
          }
        }
      </span>
    }
  `,
  styles: `
    :host {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .bar {
      flex: 1;
      display: flex;
      gap: 3px;
    }
    .seg {
      min-width: 4px;
      border-radius: 4px;
    }
    .seg--amber {
      background: var(--sf-tertiary);
    }
    .seg--violet {
      background: var(--sf-primary-deep);
    }
    .seg--green {
      background: var(--sf-success);
    }
    .seg--cyan {
      background: var(--sf-secondary);
    }
    .seg.running {
      background: repeating-linear-gradient(135deg, #5fd4e0 0 6px, #3aa9b6 6px 12px);
    }
    .legend {
      font-family: var(--sf-mono);
      font-size: 12px;
      color: var(--sf-text-muted);
      white-space: nowrap;
    }
    .legend .current {
      color: var(--sf-secondary);
    }
  `,
})
export class PhaseBarComponent {
  readonly phases = input.required<PhaseSegment[]>()
  readonly height = input(8)
  readonly showLegend = input(true)
  readonly ariaLabel = computed(
    () =>
      'Phases: ' +
      this.phases()
        .map((p) => {
          if (p.status === 'running') return `${p.key} running`
          if (p.status === 'waiting_human') return `${p.key} waiting for human`
          if (p.status === 'failed') return `${p.key} failed`
          if (p.status === 'indeterminate') return `${p.key} indeterminate`
          if (p.status === 'cancelled') return `${p.key} cancelled`
          if (p.status === 'pending') return `${p.key} waiting`
          return `${p.key} completed`
        })
        .join(', ')
  )
}
