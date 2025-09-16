import { Component, OnInit, OnDestroy, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { ThemeService, ThemeMode } from '../../core/services/theme.service'

@Component({
  selector: 'app-theme-selector',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="theme-selector">
      <button 
        class="theme-toggle-btn" 
        (click)="togglePanel()"
        title="Theme settings"
      >
        🎨
      </button>
      
      <div 
        class="theme-panel" 
        [class.visible]="isPanelVisible"
      >
        <h4>Theme</h4>
        
        <label class="theme-option">
          <input 
            type="radio" 
            name="theme" 
            value="light"
            [(ngModel)]="selectedTheme"
            (change)="onThemeChange()"
          >
          ☀️ Light
        </label>
        
        <label class="theme-option">
          <input 
            type="radio" 
            name="theme" 
            value="dark"
            [(ngModel)]="selectedTheme"
            (change)="onThemeChange()"
          >
          🌙 Dark
        </label>
        
        <label class="theme-option">
          <input 
            type="radio" 
            name="theme" 
            value="system"
            [(ngModel)]="selectedTheme"
            (change)="onThemeChange()"
          >
          💻 System
        </label>
      </div>
    </div>
  `,
  styles: [`
    .theme-selector {
      position: relative;
    }
    
    .theme-toggle-btn {
      background: none;
      border: none;
      border-radius: 4px;
      padding: 0.5rem;
      cursor: pointer;
      font-size: 1rem;
      
      &:hover {
        background: var(--color-bg-secondary, #f3f4f6);
      }
    }
    
    .theme-panel {
      position: absolute;
      top: 100%;
      right: 0;
      margin-top: 0.5rem;
      background: var(--color-input-bg, #ffffff);
      border: 1px solid var(--color-border, #d1d5db);
      border-radius: 6px;
      padding: 1rem;
      box-shadow: var(--color-shadow, rgba(0, 0, 0, 0.1)) 0 4px 6px -1px;
      min-width: 150px;
      z-index: 1000;
      
      opacity: 0;
      visibility: hidden;
      transform: translateY(-10px);
      transition: all 0.2s ease;
      
      &.visible {
        opacity: 1;
        visibility: visible;
        transform: translateY(0);
      }
    }
    
    h4 {
      margin: 0 0 0.75rem 0;
      font-size: 0.9rem;
      font-weight: 600;
      color: var(--color-text, #374151);
    }
    
    .theme-option {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin-bottom: 0.5rem;
      cursor: pointer;
      font-size: 0.9rem;
      color: var(--color-text, #374151);
      
      &:last-child {
        margin-bottom: 0;
      }
      
      input[type="radio"] {
        margin: 0;
      }
      
      &:hover {
        color: var(--color-primary, #3b82f6);
      }
    }
  `]
})
export class ThemeSelectorComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()
  
  selectedTheme: ThemeMode = 'light'
  isPanelVisible = false

  // Modern Angular dependency injection
  private themeService = inject(ThemeService)

  ngOnInit(): void {
    // Subscribe to current theme
    this.themeService.currentTheme$
      .pipe(takeUntil(this.destroy$))
      .subscribe(theme => {
        this.selectedTheme = theme
      })
    
    // Close panel when clicking outside
    document.addEventListener('click', this.handleDocumentClick.bind(this))
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
    document.removeEventListener('click', this.handleDocumentClick.bind(this))
  }

  togglePanel(): void {
    this.isPanelVisible = !this.isPanelVisible
  }

  onThemeChange(): void {
    this.themeService.setTheme(this.selectedTheme)
    console.log('[THEME] Changed to:', this.selectedTheme)
  }

  private handleDocumentClick(event: Event): void {
    const target = event.target as HTMLElement
    if (!target.closest('.theme-selector')) {
      this.isPanelVisible = false
    }
  }
}