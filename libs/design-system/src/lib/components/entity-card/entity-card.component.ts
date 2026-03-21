import { ChangeDetectionStrategy, Component, ContentChild, EventEmitter, Input, Output } from '@angular/core'
import { MatRippleModule } from '@angular/material/core'
import { CardActionsDirective } from './card-actions.directive'

export type EntityCardBadgeVariant = 'warning' | 'info' | 'success' | 'error'

export interface EntityCardBadge {
  label: string
  variant: EntityCardBadgeVariant
}

/**
 * DsEntityCard — a generic card for entity list pages.
 *
 * Displays a name, optional description, optional badges, and an optional
 * actions slot via the `dsCardActions` directive.
 *
 * When actions are projected, the card surface is not clickable — only the
 * `selected` output via the body click is suppressed; action buttons handle
 * their own events. When no actions are projected, the whole card is a
 * keyboard-accessible clickable surface.
 *
 * CSS contract: --color-text, --color-text-secondary, --color-text-inverse,
 * --color-bg-secondary, --color-bg-hover, --color-border, --color-primary,
 * --color-warning, --color-success, --color-error, --color-info
 *
 * @example Basic (project-list style)
 * <ds-entity-card id="my-project" name="My Project" (selected)="onSelect($event)" />
 *
 * @example With actions (prompt-list style)
 * <ds-entity-card id="p1" name="My Prompt" description="Does X" (selected)="onOpen($event)">
 *   <ng-container dsCardActions>
 *     <button mat-icon-button (click)="edit(p1)"><mat-icon>edit</mat-icon></button>
 *     <button mat-icon-button (click)="delete(p1)"><mat-icon>delete</mat-icon></button>
 *   </ng-container>
 * </ds-entity-card>
 */
@Component({
  selector: 'ds-entity-card',
  standalone: true,
  imports: [MatRippleModule],
  templateUrl: './entity-card.component.html',
  styleUrl: './entity-card.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EntityCardComponent {
  @Input({ required: true }) id!: string
  @Input({ required: true }) name!: string
  @Input() description: string | undefined = undefined
  @Input() badges: EntityCardBadge[] = []

  @Output() selected = new EventEmitter<string>()

  @ContentChild(CardActionsDirective) protected cardActions?: CardActionsDirective

  protected get hasActions(): boolean {
    return this.cardActions !== undefined
  }

  protected onClick(): void {
    this.selected.emit(this.id)
  }
}
