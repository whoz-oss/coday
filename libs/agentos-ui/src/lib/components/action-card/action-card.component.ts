import { ChangeDetectionStrategy, Component, Directive, contentChild, input, output } from '@angular/core'
import { NgTemplateOutlet } from '@angular/common'
import { KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

// Re-export for consumers who typed ActionCardMenuItem directly
export type { KebabMenuItem as ActionCardMenuItem }

/** Slot chips — projeter les boutons d'actions rapides */
@Directive({ selector: '[agentosCardChips]', standalone: true })
export class ActionCardChipsDirective {}

@Component({
  selector: 'agentos-action-card',
  templateUrl: './action-card.component.html',
  styleUrl: './action-card.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [KebabMenuComponent, NgTemplateOutlet],
})
export class ActionCardComponent {
  readonly name = input.required<string>()
  readonly description = input<string | null>(null)
  readonly mark = input<string | null>(null)
  readonly markIsEmoji = input<boolean>(false)
  readonly logo = input<string | null>(null)
  readonly menuItems = input<KebabMenuItem[]>([])
  readonly clickable = input<boolean>(false)

  readonly nameClicked = output<void>()
  readonly menuAction = output<string>()

  protected readonly chipsSlot = contentChild(ActionCardChipsDirective)

  protected get resolvedMark(): string {
    return this.mark() ?? (this.name().charAt(0).toUpperCase() || '?')
  }

  protected get hasChips(): boolean {
    return this.chipsSlot() !== undefined
  }

  protected get hasMenu(): boolean {
    return this.menuItems().length > 0
  }
}
