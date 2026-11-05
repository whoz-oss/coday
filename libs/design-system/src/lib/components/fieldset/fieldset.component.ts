import { booleanAttribute, ChangeDetectionStrategy, Component, input } from '@angular/core'

/**
 * ds-fieldset — a bordered group of related fields, with a legend and an optional hint.
 *
 * A real `<fieldset>`, so `disabled` disables every control inside it (projected ones included) and the legend
 * names the group for assistive technologies.
 */
@Component({
  selector: 'ds-fieldset',
  template: `
    <fieldset class="ds-fieldset" [disabled]="disabled()">
      @if (legend()) {
        <legend class="ds-fieldset__legend">
          {{ legend() }}
          @if (legendHint()) {
            <span class="ds-fieldset__legend-hint">{{ legendHint() }}</span>
          }
        </legend>
      }
      @if (hint()) {
        <p class="ds-hint">{{ hint() }}</p>
      }
      <ng-content />
    </fieldset>
  `,
  styleUrl: './fieldset.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FieldsetComponent {
  readonly legend = input<string>('')
  readonly legendHint = input<string>('')
  readonly hint = input<string>('')
  readonly disabled = input(false, { transform: booleanAttribute })
}
