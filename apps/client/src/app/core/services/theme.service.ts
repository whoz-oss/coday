import { Injectable, inject, OnDestroy } from '@angular/core'
import { BehaviorSubject, Subscription } from 'rxjs'
import { PreferencesService } from '../../services/preferences.service'

export type ThemeMode = 'light' | 'dark' | 'system'

@Injectable({
  providedIn: 'root',
})
export class ThemeService implements OnDestroy {
  private currentThemeSubject = new BehaviorSubject<ThemeMode>('light')
  currentTheme$ = this.currentThemeSubject.asObservable()

  private preferences = inject(PreferencesService)
  private subscription: Subscription | null = null

  constructor() {
    console.log('[THEME] Initializing theme service')
    this.initializeTheme()
    this.setupSystemThemeListener()
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe()
  }

  private initializeTheme(): void {
    // Subscribe to theme preference reactively.
    // On desktop, loadPreferencesAsync() will emit the saved value from Electron storage
    // once the IPC call completes, automatically applying the correct theme without polling.
    this.subscription = this.preferences.theme$.subscribe((theme) => {
      console.log('[THEME] Theme preference changed to:', theme)
      this.applyTheme(theme as ThemeMode)
    })
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
      if (typeof window !== 'undefined' && window.matchMedia) {
        const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
        const resolvedTheme = prefersDark ? 'dark' : 'light'
        console.log('[THEME] System theme resolved to:', resolvedTheme)
        this.setDocumentTheme(resolvedTheme)
      } else {
        console.log('[THEME] matchMedia not available, defaulting to light theme')
        this.setDocumentTheme('light')
      }
    } else {
      this.setDocumentTheme(theme)
    }
  }

  private setDocumentTheme(theme: 'light' | 'dark'): void {
    console.log('[THEME] Setting document theme to:', theme)
    if (typeof document !== 'undefined' && document.documentElement) {
      if (theme === 'dark') {
        document.documentElement.setAttribute('data-theme', 'dark')
      } else {
        document.documentElement.removeAttribute('data-theme')
      }
    }
  }

  private setupSystemThemeListener(): void {
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
