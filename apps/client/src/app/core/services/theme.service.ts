import { Injectable, inject } from '@angular/core'
import { DOCUMENT } from '@angular/common'
import { BehaviorSubject } from 'rxjs'
import { PreferencesService } from '../../services/preferences.service'
import { WINDOW } from '../tokens/window'

export type ThemeMode = 'light' | 'dark' | 'system'

@Injectable({
  providedIn: 'root',
})
export class ThemeService {
  private currentThemeSubject = new BehaviorSubject<ThemeMode>('light')
  currentTheme$ = this.currentThemeSubject.asObservable()

  // Modern Angular dependency injection
  private readonly window = inject(WINDOW)
  private readonly document = inject(DOCUMENT)
  private preferences = inject(PreferencesService)

  constructor() {
    console.log('[THEME] Initializing theme service')
    this.initializeTheme()
    this.setupSystemThemeListener()
  }

  private initializeTheme(): void {
    // Check if running in desktop app - preferences will be loaded asynchronously
    // For now, apply default theme and let the preference service update it
    const savedTheme = this.preferences.getPreference<ThemeMode>('theme', 'light') ?? 'light'
    console.log('[THEME] Loaded saved theme:', savedTheme)
    this.applyTheme(savedTheme)

    // Subscribe to theme preference changes (useful for desktop app async loading)
    setTimeout(() => {
      const currentTheme = this.preferences.getPreference<ThemeMode>('theme', 'light') ?? 'light'
      if (currentTheme !== savedTheme) {
        console.log('[THEME] Theme updated after async load:', currentTheme)
        this.applyTheme(currentTheme)
      }
    }, 100)
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
      if (this.window.matchMedia) {
        const prefersDark = this.window.matchMedia('(prefers-color-scheme: dark)').matches
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
    if (this.document?.documentElement) {
      if (theme === 'dark') {
        this.document.documentElement.setAttribute('data-theme', 'dark')
      } else {
        this.document.documentElement.removeAttribute('data-theme')
      }
    }
  }

  private setupSystemThemeListener(): void {
    // Check if matchMedia is available (not available in test environment)
    if (this.window.matchMedia) {
      this.window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
        const currentTheme = this.getCurrentTheme()
        if (currentTheme === 'system') {
          this.setDocumentTheme(e.matches ? 'dark' : 'light')
        }
      })
    }
  }
}
