import { booleanAttribute, ChangeDetectionStrategy, Component, input, output } from '@angular/core'

/**
 * ds-disclosure — a titled block that opens and closes: a heading with a chevron, and the projected content under
 * it while `open`. The state is the host's (`open` in, `toggled` out), so it can be remembered or driven.
 *
 * `bordered` frames it as a row of a list; `mono` sets the heading in the monospace (a tool name, a path).
 */
@Component({
  selector: 'ds-disclosure',
  template: `
    <div class="ds-disclosure" [class.ds-disclosure--bordered]="bordered()">
      <button type="button" class="ds-disclosure__header" [attr.aria-expanded]="open()" (click)="toggled.emit()">
        <span class="ds-disclosure__title" [class.ds-disclosure__title--mono]="mono()">{{ heading() }}</span>
        <span class="ds-disclosure__chevron" [class.ds-disclosure__chevron--open]="open()" aria-hidden="true"
          >&#9660;</span
        >
      </button>
      @if (open()) {
        <div class="ds-disclosure__body"><ng-content /></div>
      }
    </div>
  `,
  styleUrl: './disclosure.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DisclosureComponent {
  readonly heading = input.required<string>()
  readonly open = input(false, { transform: booleanAttribute })
  readonly mono = input(false, { transform: booleanAttribute })
  readonly bordered = input(false, { transform: booleanAttribute })

  readonly toggled = output<void>()
}
