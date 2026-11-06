import { ChangeDetectionStrategy, Component } from '@angular/core'

/** ds-form-actions — the buttons under a form: the primary action first, then the others, left-aligned. */
@Component({
  selector: 'ds-form-actions',
  template: '<ng-content />',
  styleUrl: './form-actions.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FormActionsComponent {}
