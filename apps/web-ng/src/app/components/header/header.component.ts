import { Component, OnInit, OnDestroy, HostListener, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { CodayService } from '../../core/services/coday.service'
import { ThemeSelectorComponent } from '../theme-selector/theme-selector.component'
import { OptionsPanelComponent } from '../options-panel/options-panel.component'

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [CommonModule, ThemeSelectorComponent, OptionsPanelComponent],
  templateUrl: './header.component.html',
  styleUrl: './header.component.scss'
})
export class HeaderComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()
  title = 'Coday'
  isMenuOpen = false

  // Modern Angular dependency injection
  private codayService = inject(CodayService)

  ngOnInit(): void {
    this.codayService.projectTitle$
      .pipe(takeUntil(this.destroy$))
      .subscribe(title => {
        this.title = title
        document.title = title
      })
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