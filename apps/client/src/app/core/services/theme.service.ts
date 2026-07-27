import { Injectable, inject, OnDestroy } from '@angular/core'
import { BehaviorSubject, Subscription } from 'rxjs'
import { PreferencesService } from '../../services/preferences.service'

export type ThemeVariant = 'industry' | 'terminal'
export type ThemeMode = 'light' | 'dark' | 'system'

export interface ThemeState {
  variant: ThemeVariant
  mode: ThemeMode
}

@Injectable({
  providedIn: 'root',
})
export class ThemeService implements OnDestroy {
  private currentThemeSubject = new BehaviorSubject<ThemeState>({ variant: 'industry', mode: 'light' })
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
    this.subscription = this.preferences.theme$.subscribe((mode) => {
      const variant = (this.preferences.getPreference<string>('themeVariant') ?? 'industry') as ThemeVariant
      const state: ThemeState = {
        variant: ['industry', 'terminal'].includes(variant) ? variant : 'industry',
        mode: (['light', 'dark', 'system'] as string[]).includes(mode) ? (mode as ThemeMode) : 'system',
      }
      console.log('[THEME] Theme preference changed to:', state)
      this.applyTheme(state)
    })
  }

  setTheme(state: ThemeState): void {
    this.preferences.setPreference('themeVariant', state.variant)
    this.preferences.setPreference('theme', state.mode)
    this.applyTheme(state)
  }

  getCurrentTheme(): ThemeState {
    return this.currentThemeSubject.value
  }

  private applyTheme(state: ThemeState): void {
    console.log('[THEME] Applying theme:', state)
    this.currentThemeSubject.next(state)

    const resolvedMode: 'light' | 'dark' =
      state.mode === 'system'
        ? typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches
          ? 'dark'
          : 'light'
        : state.mode

    this.setDocumentTheme(state.variant, resolvedMode)
  }

  private setDocumentTheme(variant: ThemeVariant, mode: 'light' | 'dark'): void {
    if (typeof document === 'undefined' || !document.documentElement) return
    const attr = variant === 'industry' && mode === 'light' ? null : `${variant}-${mode}`
    if (attr) {
      document.documentElement.setAttribute('data-theme', attr)
    } else {
      document.documentElement.removeAttribute('data-theme')
    }
  }

  private setupSystemThemeListener(): void {
    if (typeof window !== 'undefined' && window.matchMedia) {
      window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
        const current = this.getCurrentTheme()
        if (current.mode === 'system') {
          this.setDocumentTheme(current.variant, e.matches ? 'dark' : 'light')
        }
      })
    }
  }
}
