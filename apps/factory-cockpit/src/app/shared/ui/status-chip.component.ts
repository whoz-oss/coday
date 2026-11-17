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
    /* Tone: --tone is the fill (border and background are tints of it), --tone-text the readable text color. */
    :host {
      --tone: var(--color-neutral-500);
      --tone-text: var(--color-text-secondary);
      display: inline-flex;
      align-items: center;
      gap: 6px;
      height: 26px;
      padding: 0 12px;
      border-radius: var(--radius-pill, 0);
      border: 1px solid color-mix(in srgb, var(--tone) 40%, transparent);
      background: color-mix(in srgb, var(--tone) 12%, transparent);
      color: var(--tone-text);
      font-size: 13px;
      font-weight: 600;
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
      background: var(--color-success);
    }
    /* running */
    :host(.sf-chip--blue) {
      --tone: var(--color-accent);
      --tone-text: var(--color-accent-700);
    }
    /* waiting for a human */
    :host(.sf-chip--amber) {
      --tone: var(--color-warning);
      --tone-text: var(--status-pending-text);
    }
    /* done */
    :host(.sf-chip--green) {
      --tone: var(--color-success);
      --tone-text: color-mix(in srgb, var(--color-success) 70%, var(--color-text));
    }
    /* failure */
    :host(.sf-chip--red) {
      --tone: var(--color-error);
      --tone-text: var(--red-deep);
    }
    :host(.sf-chip--neutral) {
      --tone: var(--color-neutral-500);
      --tone-text: var(--color-text-secondary);
    }
    :host(.sf-chip--violet) {
      --tone: var(--color-accent);
      --tone-text: var(--color-accent-800);
      background: color-mix(in srgb, var(--tone) 22%, transparent);
    }
    :host(.sf-chip--cyan) {
      --tone: var(--color-info);
      --tone-text: color-mix(in srgb, var(--color-info) 50%, var(--color-text));
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
