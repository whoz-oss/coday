import { Component, Input } from '@angular/core'
import { CommonModule } from '@angular/common'
import { MatIconButton } from '@angular/material/button'
import { MatIcon } from '@angular/material/icon'

export interface MenuAction {
  /** Name of the Material icon (see https://fonts.google.com/icons) */
  icon: string
  label: string
  tooltip?: string
  action: () => void
  destructive?: boolean // For red styling (e.g., delete)
}

@Component({
  selector: 'app-message-context-menu',
  standalone: true,
  imports: [CommonModule, MatIconButton, MatIcon],
  templateUrl: './message-context-menu.component.html',
  styleUrl: './message-context-menu.component.scss',
})
export class MessageContextMenuComponent {
  @Input() actions: MenuAction[] = []

  executeAction(action: MenuAction, event: Event): void {
    event.stopPropagation()
    action.action()
  }
}
