import { Injectable } from '@angular/core'
import { BehaviorSubject } from 'rxjs'
import { PreferencesService } from './preferences.service'

export type ThemeMode = 'light' | 'dark' | 'system'

@Injectable({
  providedIn: 'root'
})
export class ThemeService {
  private currentThemeSubject = new BehaviorSubject<ThemeMode>('light')
  currentTheme$ = this.currentThemeSubject.asObservable()

  constructor(private preferences: PreferencesService) {
    this.initializeTheme()
    this.setupSystemThemeListener()
  }

  private initializeTheme(): void {
    const savedTheme = this.preferences.getPreference<ThemeMode>('theme', 'light')
    this.applyTheme(savedTheme!)
  }

  setTheme(theme: ThemeMode): void {
    this.preferences.setPreference('theme', theme)
    this.applyTheme(theme)
  }

  getCurrentTheme(): ThemeMode {
    return this.currentThemeSubject.value
  }

  private applyTheme(theme: ThemeMode): void {
    this.currentThemeSubject.next(theme)

    if (theme === 'system') {
      // Use system preference
      const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
      this.setDocumentTheme(prefersDark ? 'dark' : 'light')
    } else {
      this.setDocumentTheme(theme)
    }
  }

  private setDocumentTheme(theme: 'light' | 'dark'): void {
    if (theme === 'dark') {
      document.documentElement.setAttribute('data-theme', 'dark')
    } else {
      document.documentElement.removeAttribute('data-theme')
    }
  }

  private setupSystemThemeListener(): void {
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
      const currentTheme = this.getCurrentTheme()
      if (currentTheme === 'system') {
        this.setDocumentTheme(e.matches ? 'dark' : 'light')
      }
    })
  }
}