import { Component, OnInit, OnDestroy, inject } from '@angular/core'
import { FormsModule } from '@angular/forms'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { ThemeService, ThemeMode } from '../../core/services/theme.service'

@Component({
  selector: 'app-theme-selector',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="theme-radio-group">
      <label class="theme-radio">
        <input type="radio" name="theme" value="light" [(ngModel)]="selectedTheme" (change)="onThemeChange()" />
        <span class="radio-mark"></span>
        ☀️ Light
      </label>

      <label class="theme-radio">
        <input type="radio" name="theme" value="dark" [(ngModel)]="selectedTheme" (change)="onThemeChange()" />
        <span class="radio-mark"></span>
        🌙 Dark
      </label>

      <label class="theme-radio">
        <input type="radio" name="theme" value="system" [(ngModel)]="selectedTheme" (change)="onThemeChange()" />
        <span class="radio-mark"></span>
        💻 System
      </label>
    </div>
  `,
  styles: [
    `
      .theme-radio-group {
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
      }

      .theme-radio {
        display: flex;
        align-items: center;
        cursor: pointer;
        font-size: 0.9rem;
        color: var(--color-text);
        user-select: none;

        input[type='radio'] {
          display: none;
        }

        .radio-mark {
          width: 16px;
          height: 16px;
          border: 2px solid var(--color-border);
          border-radius: 50%;
          margin-right: 0.75rem;
          display: flex;
          align-items: center;
          justify-content: center;
          transition: all 0.2s ease;
          flex-shrink: 0;
          position: relative;

          &::after {
            content: '';
            width: 8px;
            height: 8px;
            border-radius: 50%;
            background-color: var(--color-primary);
            transition: all 0.2s ease;
            opacity: 0;
            transform: scale(0);
          }
        }

        input[type='radio']:checked + .radio-mark {
          border-color: var(--color-primary);

          &::after {
            opacity: 1;
            transform: scale(1);
          }
        }

        &:hover .radio-mark {
          border-color: var(--color-primary);
        }

        &:focus-within .radio-mark {
          box-shadow: 0 0 0 3px rgba(112, 100, 251, 0.1);
        }
      }
    `,
  ],
})
export class ThemeSelectorComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()

  selectedTheme: ThemeMode = 'light'

  // Modern Angular dependency injection
  private themeService = inject(ThemeService)

  ngOnInit(): void {
    // Subscribe to current theme
    this.themeService.currentTheme$.pipe(takeUntil(this.destroy$)).subscribe((theme) => {
      this.selectedTheme = theme
    })
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }

  onThemeChange(): void {
    this.themeService.setTheme(this.selectedTheme)
    console.log('[THEME] Changed to:', this.selectedTheme)
  }
}
