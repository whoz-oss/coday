import { Injectable, inject } from '@angular/core'
import { BehaviorSubject, Observable } from 'rxjs'
import { WINDOW } from '../tokens/window'

@Injectable({
  providedIn: 'root',
})
export class PreferencesService {
  private readonly STORAGE_KEY = 'coday-preferences'
  private readonly window = inject(WINDOW)

  // Subjects for reactive preferences
  private preferencesSubject = new BehaviorSubject<Record<string, any>>(this.loadAllPreferences())

  // Public observable
  preferences$ = this.preferencesSubject.asObservable()

  constructor() {
    // Listen for storage changes from other tabs
    this.window.addEventListener('storage', (event) => {
      if (event.key === this.STORAGE_KEY) {
        this.preferencesSubject.next(this.loadAllPreferences())
      }
    })
  }

  /**
   * Get a user preference
   */
  getPreference<T>(key: string, defaultValue?: T): T | undefined {
    try {
      const stored = localStorage.getItem(this.STORAGE_KEY)
      if (!stored) return defaultValue

      const preferences = JSON.parse(stored)
      return preferences[key] !== undefined ? preferences[key] : defaultValue
    } catch {
      return defaultValue
    }
  }

  /**
   * Set a user preference
   */
  setPreference<T>(key: string, value: T): void {
    try {
      const stored = localStorage.getItem(this.STORAGE_KEY)
      const preferences = stored ? JSON.parse(stored) : {}

      preferences[key] = value
      localStorage.setItem(this.STORAGE_KEY, JSON.stringify(preferences))

      // Emit updated preferences
      this.preferencesSubject.next(preferences)

      console.log(`[PREFS] Set ${key}:`, value)
    } catch (error) {
      console.error('Failed to set preference:', error)
    }
  }

  /**
   * Get an observable for a specific preference
   */
  getPreference$<T>(key: string, defaultValue?: T): Observable<T> {
    return new Observable((subscriber) => {
      // Emit current value
      const currentValue = this.getPreference(key, defaultValue)
      if (currentValue !== undefined) {
        subscriber.next(currentValue)
      } else if (defaultValue !== undefined) {
        subscriber.next(defaultValue)
      }

      // Subscribe to changes
      const subscription = this.preferences$.subscribe((preferences) => {
        const value = preferences[key] !== undefined ? preferences[key] : defaultValue
        subscriber.next(value)
      })

      return () => subscription.unsubscribe()
    })
  }

  /**
   * Remove a preference
   */
  removePreference(key: string): void {
    try {
      const stored = localStorage.getItem(this.STORAGE_KEY)
      if (!stored) return

      const preferences = JSON.parse(stored)
      delete preferences[key]

      localStorage.setItem(this.STORAGE_KEY, JSON.stringify(preferences))
      this.preferencesSubject.next(preferences)

      console.log(`[PREFS] Removed ${key}`)
    } catch (error) {
      console.error('Failed to remove preference:', error)
    }
  }

  /**
   * Clear all preferences
   */
  clearAllPreferences(): void {
    try {
      localStorage.removeItem(this.STORAGE_KEY)
      this.preferencesSubject.next({})
      console.log('[PREFS] Cleared all preferences')
    } catch (error) {
      console.error('Failed to clear preferences:', error)
    }
  }

  /**
   * Load all preferences from storage
   */
  private loadAllPreferences(): Record<string, any> {
    try {
      const stored = localStorage.getItem(this.STORAGE_KEY)
      return stored ? JSON.parse(stored) : {}
    } catch {
      return {}
    }
  }

  /**
   * Get all preferences as a plain object
   */
  getAllPreferences(): Record<string, any> {
    return this.preferencesSubject.value
  }
}
