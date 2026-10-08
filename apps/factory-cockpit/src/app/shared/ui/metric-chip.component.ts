import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core'
import { MatIconModule } from '@angular/material/icon'
import { MatTooltipModule } from '@angular/material/tooltip'

/** <sf-metric icon="paid" label="Coût">$1.0723</sf-metric> */
@Component({
  selector: 'sf-metric',
  imports: [MatIconModule, MatTooltipModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="metric" [matTooltip]="tooltip()" [attr.aria-label]="tooltip()">
      <mat-icon aria-hidden="true">{{ icon() }}</mat-icon>
      <ng-content />
    </span>
  `,
  styles: `
    .metric {
      display: inline-flex;
      align-items: center;
      gap: 7px;
      height: 30px;
      padding: 0 11px;
      border-radius: 8px;
      border: 1px solid var(--sf-outline);
      background: var(--sf-surface);
      font-family: var(--sf-mono);
      font-size: 13px;
      font-weight: 600;
      color: var(--sf-text);
    }
    mat-icon {
      width: 16px;
      height: 16px;
      font-size: 16px;
      color: var(--sf-text-muted);
    }
  `,
})
export class MetricChipComponent {
  readonly icon = input.required<string>()
  readonly label = input('')
  /** Number of sub-costs with an unknown price; > 0 marks the value as uncertain. */
  readonly unknownCostCount = input(0)

  protected readonly tooltip = computed(() => {
    const base = this.label()
    const unknown = this.unknownCostCount()
    return unknown > 0 ? `${base} · ${unknown} round(s) with unknown cost (floor value)` : base
  })
}
