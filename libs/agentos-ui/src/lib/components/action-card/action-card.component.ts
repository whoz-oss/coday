import {
  ChangeDetectionStrategy,
  Component,
  Directive,
  HostListener,
  contentChild,
  input,
  output,
  signal,
} from '@angular/core'

export interface ActionCardMenuItem {
  key: string
  label: string
  variant?: 'default' | 'danger' | 'separator'
}

/** Slot chips — projeter les boutons d'actions rapides */
@Directive({ selector: '[agentosCardChips]', standalone: true })
export class ActionCardChipsDirective {}

@Component({
  selector: 'agentos-action-card',
  templateUrl: './action-card.component.html',
  styleUrl: './action-card.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ActionCardComponent {
  readonly name = input.required<string>()
  readonly mark = input<string | null>(null)
  readonly menuItems = input<ActionCardMenuItem[]>([])

  readonly nameClicked = output<void>()
  readonly menuAction = output<string>()

  protected readonly chipsSlot = contentChild(ActionCardChipsDirective)
  protected readonly menuOpen = signal(false)

  protected get resolvedMark(): string {
    return this.mark() ?? (this.name().charAt(0).toUpperCase() || '?')
  }

  protected get hasChips(): boolean {
    return this.chipsSlot() !== undefined
  }
  protected get hasMenu(): boolean {
    return this.menuItems().length > 0
  }

  protected toggleMenu(event: MouseEvent): void {
    event.stopPropagation()
    this.menuOpen.update((v) => !v)
  }

  protected onMenuItemClick(key: string): void {
    this.menuOpen.set(false)
    this.menuAction.emit(key)
  }

  @HostListener('document:click')
  protected onDocumentClick(): void {
    this.menuOpen.set(false)
  }
}
