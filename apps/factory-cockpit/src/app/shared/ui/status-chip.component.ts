import { ChangeDetectionStrategy, Component, booleanAttribute, input } from '@angular/core'
import { MatIconModule } from '@angular/material/icon'
import { Tone } from '../../core/models'

/**
 * Pastille de statut : toujours icône + libellé (jamais la couleur seule).
 * <sf-status-chip tone="blue" icon="progress_activity" spin>au travail</sf-status-chip>
 */
@Component({
  selector: 'sf-status-chip',
  imports: [MatIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class]': '"sf-chip sf-chip--" + tone()', '[class.sf-chip--sm]': 'size() === "sm"' },
  template: `
    @if (icon()) {
      <mat-icon [class.sf-spin]="spin()" aria-hidden="true">{{ icon() }}</mat-icon>
    } @else if (dot()) {
      <span class="dot" aria-hidden="true"></span>
    }
    <ng-content />
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      height: 26px;
      padding: 0 12px;
      border-radius: 999px;
      border: 1px solid;
      font-size: 13px;
      font-weight: 700;
      white-space: nowrap;
    }
    :host(.sf-chip--sm) {
      height: 24px;
      padding: 0 10px;
      font-size: 12.5px;
    }
    mat-icon {
      width: 14px;
      height: 14px;
      font-size: 14px;
    }
    .dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--sf-success);
    }
    :host(.sf-chip--blue) {
      color: var(--sf-running);
      border-color: #33486e;
      background: #121a2b;
    }
    :host(.sf-chip--amber) {
      color: var(--sf-tertiary);
      border-color: #5a4818;
      background: #1d180b;
      font-weight: 600;
    }
    :host(.sf-chip--green) {
      color: #6ee79a;
      border-color: #22583a;
      background: #0e1f16;
    }
    :host(.sf-chip--red) {
      color: #f58f8f;
      border-color: #6b2a2e;
      background: #1f1114;
    }
    :host(.sf-chip--neutral) {
      color: var(--sf-text-muted);
      border-color: var(--sf-outline-strong);
      background: var(--sf-surface-2);
    }
    :host(.sf-chip--violet) {
      color: #e3d6ff;
      border-color: #4b3d80;
      background: #2a2145;
    }
    :host(.sf-chip--cyan) {
      color: var(--sf-secondary);
      border-color: #2c5d63;
      background: #0e1d21;
    }
  `,
})
export class StatusChipComponent {
  readonly tone = input<Tone>('neutral')
  readonly icon = input<string>()
  readonly spin = input(false, { transform: booleanAttribute })
  readonly dot = input(false, { transform: booleanAttribute })
  readonly size = input<'md' | 'sm'>('md')
}
