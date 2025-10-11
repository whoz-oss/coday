import { Component, Input, HostListener } from '@angular/core'
import { CommonModule } from '@angular/common'

export interface MenuAction {
  icon: string
  label: string
  tooltip?: string
  action: () => void
  destructive?: boolean // For red styling (e.g., delete)
}

@Component({
  selector: 'app-message-context-menu',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './message-context-menu.component.html',
  styleUrl: './message-context-menu.component.scss'
})
export class MessageContextMenuComponent {
  @Input() actions: MenuAction[] = []
  @Input() position: 'top' | 'bottom' = 'top'
  
  isOpen = false
  
  toggleMenu(event: Event): void {
    event.stopPropagation()
    this.isOpen = !this.isOpen
  }
  
  closeMenu(): void {
    this.isOpen = false
  }
  
  executeAction(action: MenuAction, event: Event): void {
    event.stopPropagation()
    action.action()
    this.closeMenu()
  }
  
  @HostListener('document:click')
  onDocumentClick(): void {
    // Close menu when clicking outside
    this.closeMenu()
  }
}
