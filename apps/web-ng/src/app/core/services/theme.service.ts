import { Injectable, inject } from '@angular/core'
import { BehaviorSubject } from 'rxjs'
import { PreferencesService } from '../../services/preferences.service'

export type ThemeMode = 'light' | 'dark' | 'system'

@Injectable({
  providedIn: 'root'
})
export class ThemeService {
  private currentThemeSubject = new BehaviorSubject<ThemeMode>('light')
  currentTheme$ = this.currentThemeSubject.asObservable()

  // Modern Angular dependency injection
  private preferences = inject(PreferencesService)
  
  constructor() {
    console.log('[THEME] Initializing theme service')
    this.initializeTheme()
    this.setupSystemThemeListener()
  }

  private initializeTheme(): void {
    const savedTheme = this.preferences.getPreference<ThemeMode>('theme', 'light') ?? 'light'
    console.log('[THEME] Loaded saved theme:', savedTheme)
    this.applyTheme(savedTheme)
  }

  setTheme(theme: ThemeMode): void {
    this.preferences.setPreference('theme', theme)
    this.applyTheme(theme)
  }

  getCurrentTheme(): ThemeMode {
    return this.currentThemeSubject.value
  }

  private applyTheme(theme: ThemeMode): void {
    console.log('[THEME] Applying theme:', theme)
    this.currentThemeSubject.next(theme)

    if (theme === 'system') {
      // Use system preference (check if matchMedia is available)
      if (typeof window !== 'undefined' && window.matchMedia) {
        const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
        const resolvedTheme = prefersDark ? 'dark' : 'light'
        console.log('[THEME] System theme resolved to:', resolvedTheme)
        this.setDocumentTheme(resolvedTheme)
      } else {
        // Fallback to light theme in test environment
        console.log('[THEME] matchMedia not available, defaulting to light theme')
        this.setDocumentTheme('light')
      }
    } else {
      this.setDocumentTheme(theme)
    }
  }

  private setDocumentTheme(theme: 'light' | 'dark'): void {
    console.log('[THEME] Setting document theme to:', theme)
    // Check if document is available (not always available in test environment)
    if (typeof document !== 'undefined' && document.documentElement) {
      if (theme === 'dark') {
        document.documentElement.setAttribute('data-theme', 'dark')
      } else {
        document.documentElement.removeAttribute('data-theme')
      }
    }
  }

  private setupSystemThemeListener(): void {
    // Check if matchMedia is available (not available in test environment)
    if (typeof window !== 'undefined' && window.matchMedia) {
      window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
        const currentTheme = this.getCurrentTheme()
        if (currentTheme === 'system') {
          this.setDocumentTheme(e.matches ? 'dark' : 'light')
        }
      })
    }
  }
}