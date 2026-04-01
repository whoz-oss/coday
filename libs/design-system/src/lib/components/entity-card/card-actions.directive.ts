import { Directive } from '@angular/core'

/**
 * Marker directive for the card actions slot in `ds-entity-card`.
 *
 * Usage:
 * ```html
 * <ds-entity-card ...>
 *   <ng-container dsCardActions>
 *     <button mat-icon-button (click)="edit()">...</button>
 *   </ng-container>
 * </ds-entity-card>
 * ```
 */
@Directive({ selector: '[dsCardActions]', standalone: true })
export class CardActionsDirective {}
