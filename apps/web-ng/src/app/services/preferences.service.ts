import { Injectable } from '@angular/core'
import { BehaviorSubject } from 'rxjs'

/**
 * Service Angular pour gérer les préférences utilisateur
 * Basé sur le système de préférences de l'ancienne application web
 */
@Injectable({
  providedIn: 'root'
})
export class PreferencesService {
  private readonly STORAGE_KEY = 'coday-preferences'
  
  // Observable pour les changements de langue vocale
  private voiceLanguageSubject = new BehaviorSubject<string>('en-US')
  public voiceLanguage$ = this.voiceLanguageSubject.asObservable()
  
  // Observable pour les changements du comportement de la touche Entrée
  private enterToSendSubject = new BehaviorSubject<boolean>(false)
  public enterToSend$ = this.enterToSendSubject.asObservable()

  constructor() {
    // Initialiser avec la valeur stockée
    const storedLanguage = this.getPreference<string>('voiceLanguage', 'en-US') ?? 'en-US'
    this.voiceLanguageSubject.next(storedLanguage)
    
    const storedEnterToSend = this.getPreference<boolean>('useEnterToSend', false) ?? false
    this.enterToSendSubject.next(storedEnterToSend)
  }

  /**
   * Obtenir une préférence utilisateur
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
   * Définir une préférence utilisateur
   */
  setPreference<T>(key: string, value: T): void {
    try {
      const stored = localStorage.getItem(this.STORAGE_KEY)
      const preferences = stored ? JSON.parse(stored) : {}
      
      preferences[key] = value
      localStorage.setItem(this.STORAGE_KEY, JSON.stringify(preferences))

      // Émettre le changement pour la langue vocale
      if (key === 'voiceLanguage') {
        this.voiceLanguageSubject.next(value as string)
      }
      
      // Émettre le changement pour le comportement de la touche Entrée
      if (key === 'useEnterToSend') {
        this.enterToSendSubject.next(value as boolean)
      }
    } catch (error) {
      console.error('Failed to set preference:', error)
    }
  }

  /**
   * Méthode spécifique pour changer la langue vocale
   */
  setVoiceLanguage(language: string): void {
    console.log('[PREFERENCES] Setting voice language to:', language)
    this.setPreference('voiceLanguage', language)
  }

  /**
   * Obtenir la langue vocale actuelle
   */
  getVoiceLanguage(): string {
    return this.getPreference<string>('voiceLanguage', 'en-US') ?? 'en-US'
  }
  
  /**
   * Méthode spécifique pour changer le comportement de la touche Entrée
   */
  setEnterToSend(useEnterToSend: boolean): void {
    console.log('[PREFERENCES] Setting Enter to send to:', useEnterToSend)
    this.setPreference('useEnterToSend', useEnterToSend)
  }
  
  /**
   * Obtenir le comportement actuel de la touche Entrée
   */
  getEnterToSend(): boolean {
    return this.getPreference<boolean>('useEnterToSend', false) ?? false
  }
}