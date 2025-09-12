import { Component, OnInit, OnDestroy, HostListener, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { Subject } from 'rxjs'
import { SessionStateService } from '../../core/services/session-state.service'
import { ThemeSelectorComponent } from '../theme-selector/theme-selector.component'
import { OptionsPanelComponent } from '../options-panel/options-panel.component'
import { ProjectSelectorComponent } from '../project-selector/project-selector.component'
import { ThreadSelectorComponent } from '../thread-selector/thread-selector.component'

@Component({
  selector: 'app-floating-menu',
  standalone: true,
  imports: [CommonModule, ThemeSelectorComponent, OptionsPanelComponent, ProjectSelectorComponent, ThreadSelectorComponent],
  templateUrl: './floating-menu.component.html',
  styleUrl: './floating-menu.component.scss'
})
export class FloatingMenuComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()
  isMenuOpen = false

  // Modern Angular dependency injection
  private sessionState = inject(SessionStateService)

  ngOnInit(): void {
    // Log session state for debugging (ensures sessionState is used)
    console.log('[FLOATING-MENU] SessionState service injected:', !!this.sessionState)
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }
  
  toggleMenu(): void {
    this.isMenuOpen = !this.isMenuOpen
  }
  
  closeMenu(): void {
    this.isMenuOpen = false
  }
  
  // Fermer le menu si on clique ailleurs
  @HostListener('document:click', ['$event'])
  onDocumentClick(event: Event): void {
    const target = event.target as HTMLElement
    if (!target.closest('.floating-menu-btn') && !target.closest('.floating-menu')) {
      this.closeMenu()
    }
  }
  
  // Fermer le menu avec Escape
  @HostListener('document:keydown.escape')
  onEscapeKey(): void {
    this.closeMenu()
  }
}