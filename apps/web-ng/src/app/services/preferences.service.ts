import { Injectable } from '@angular/core'
import { BehaviorSubject } from 'rxjs'


@Injectable({
  providedIn: 'root'
})
export class PreferencesService {
  private readonly STORAGE_KEY = 'coday-preferences'
  
  private voiceLanguageSubject = new BehaviorSubject<string>('en-US')
  public voiceLanguage$ = this.voiceLanguageSubject.asObservable()
  
  private enterToSendSubject = new BehaviorSubject<boolean>(false)
  public enterToSend$ = this.enterToSendSubject.asObservable()
  
  private voiceAnnounceEnabledSubject = new BehaviorSubject<boolean>(false)
  public voiceAnnounceEnabled$ = this.voiceAnnounceEnabledSubject.asObservable()
  
  private voiceModeSubject = new BehaviorSubject<'speech' | 'notification'>('speech')
  public voiceMode$ = this.voiceModeSubject.asObservable()
  
  private voiceReadFullTextSubject = new BehaviorSubject<boolean>(false)
  public voiceReadFullText$ = this.voiceReadFullTextSubject.asObservable()
  
  private voiceVolumeSubject = new BehaviorSubject<number>(0.8)
  public voiceVolume$ = this.voiceVolumeSubject.asObservable()
  
  private voiceRateSubject = new BehaviorSubject<number>(1.2)
  public voiceRate$ = this.voiceRateSubject.asObservable()
  
  private selectedVoiceSubject = new BehaviorSubject<string | null>(null)
  public selectedVoice$ = this.selectedVoiceSubject.asObservable()

  constructor() {
    console.log('[PREFERENCES] Initializing preferences service')
    
    const storedLanguage = this.getPreference<string>('voiceLanguage', 'en-US') ?? 'en-US'
    this.voiceLanguageSubject.next(storedLanguage)
    console.log('[PREFERENCES] Voice language initialized to:', storedLanguage)
    
    const storedEnterToSend = this.getPreference<boolean>('useEnterToSend', false) ?? false
    this.enterToSendSubject.next(storedEnterToSend)
    console.log('[PREFERENCES] Enter to send initialized to:', storedEnterToSend)
    
    const storedAnnounceEnabled = this.getPreference<boolean>('voiceAnnounceEnabled', false) ?? false
    this.voiceAnnounceEnabledSubject.next(storedAnnounceEnabled)
    
    const storedVoiceMode = this.getPreference<'speech' | 'notification'>('voiceMode', 'speech') ?? 'speech'
    this.voiceModeSubject.next(storedVoiceMode)
    
    const storedReadFullText = this.getPreference<boolean>('voiceReadFullText', false) ?? false
    this.voiceReadFullTextSubject.next(storedReadFullText)
    
    const storedVolume = this.getPreference<number>('voiceVolume', 0.8) ?? 0.8
    this.voiceVolumeSubject.next(storedVolume)
    
    const storedRate = this.getPreference<number>('voiceRate', 1.2) ?? 1.2
    this.voiceRateSubject.next(storedRate)
    
    const storedSelectedVoice = this.getPreference<string | null>('selectedVoice', null) ?? null
    this.selectedVoiceSubject.next(storedSelectedVoice)
    
    console.log('[PREFERENCES] All preferences initialized successfully')
  }

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

  setPreference<T>(key: string, value: T): void {
    try {
      const stored = localStorage.getItem(this.STORAGE_KEY)
      const preferences = stored ? JSON.parse(stored) : {}
      
      preferences[key] = value
      localStorage.setItem(this.STORAGE_KEY, JSON.stringify(preferences))

      if (key === 'voiceLanguage') {
        this.voiceLanguageSubject.next(value as string)
      }
      
      if (key === 'useEnterToSend') {
        this.enterToSendSubject.next(value as boolean)
      }
      
      if (key === 'voiceAnnounceEnabled') {
        this.voiceAnnounceEnabledSubject.next(value as boolean)
      }
      if (key === 'voiceMode') {
        this.voiceModeSubject.next(value as 'speech' | 'notification')
      }
      if (key === 'voiceReadFullText') {
        this.voiceReadFullTextSubject.next(value as boolean)
      }
      if (key === 'voiceVolume') {
        this.voiceVolumeSubject.next(value as number)
      }
      if (key === 'voiceRate') {
        this.voiceRateSubject.next(value as number)
      }
      if (key === 'selectedVoice') {
        this.selectedVoiceSubject.next(value as string | null)
      }
    } catch (error) {
      console.error('Failed to set preference:', error)
    }
  }

  setVoiceLanguage(language: string): void {
    console.log('[PREFERENCES] Setting voice language to:', language)
    this.setPreference('voiceLanguage', language)
  }

  getVoiceLanguage(): string {
    return this.getPreference<string>('voiceLanguage', 'en-US') ?? 'en-US'
  }
  
  setEnterToSend(useEnterToSend: boolean): void {
    console.log('[PREFERENCES] Setting Enter to send to:', useEnterToSend)
    this.setPreference('useEnterToSend', useEnterToSend)
  }
  
  getEnterToSend(): boolean {
    return this.getPreference<boolean>('useEnterToSend', false) ?? false
  }
  
  setVoiceAnnounceEnabled(enabled: boolean): void {
    console.log('[PREFERENCES] Setting voice announce enabled to:', enabled)
    this.setPreference('voiceAnnounceEnabled', enabled)
  }
  
  getVoiceAnnounceEnabled(): boolean {
    return this.getPreference<boolean>('voiceAnnounceEnabled', false) ?? false
  }
  
  setVoiceMode(mode: 'speech' | 'notification'): void {
    console.log('[PREFERENCES] Setting voice mode to:', mode)
    this.setPreference('voiceMode', mode)
  }
  
  getVoiceMode(): 'speech' | 'notification' {
    return this.getPreference<'speech' | 'notification'>('voiceMode', 'speech') ?? 'speech'
  }
  
  setVoiceReadFullText(enabled: boolean): void {
    console.log('[PREFERENCES] Setting voice read full text to:', enabled)
    this.setPreference('voiceReadFullText', enabled)
  }
  
  getVoiceReadFullText(): boolean {
    return this.getPreference<boolean>('voiceReadFullText', false) ?? false
  }
  
  setVoiceVolume(volume: number): void {
    const clampedVolume = Math.max(0, Math.min(1, volume))
    console.log('[PREFERENCES] Setting voice volume to:', clampedVolume)
    this.setPreference('voiceVolume', clampedVolume)
  }
  
  getVoiceVolume(): number {
    return this.getPreference<number>('voiceVolume', 0.8) ?? 0.8
  }
  
  setVoiceRate(rate: number): void {
    const clampedRate = Math.max(0.5, Math.min(2, rate))
    console.log('[PREFERENCES] Setting voice rate to:', clampedRate)
    this.setPreference('voiceRate', clampedRate)
  }
  
  getVoiceRate(): number {
    return this.getPreference<number>('voiceRate', 1.2) ?? 1.2
  }
  
  setSelectedVoice(voice: string | null): void {
    console.log('[PREFERENCES] Setting selected voice to:', voice)
    this.setPreference('selectedVoice', voice)
  }
  
  getSelectedVoice(): string | null {
    return this.getPreference<string | null>('selectedVoice', null) ?? null
  }
}