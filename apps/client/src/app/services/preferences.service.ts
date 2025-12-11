import { Injectable } from '@angular/core'
import { BehaviorSubject } from 'rxjs'

// Type definition for Electron storage API
interface ElectronStorage {
  get(key: string): Promise<string | null>
  set(key: string, value: string): Promise<boolean>
  remove(key: string): Promise<boolean>
  clear(): Promise<boolean>
}

@Injectable({
  providedIn: 'root',
})
export class PreferencesService {
  private readonly STORAGE_KEY = 'coday-preferences'
  private isDesktopApp = false
  private electronStorage: ElectronStorage | null = null

  private voiceLanguageSubject = new BehaviorSubject<string>('en-US')
  public voiceLanguage$ = this.voiceLanguageSubject.asObservable()

  private enterToSendSubject = new BehaviorSubject<boolean>(true)
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

  private printTechnicalMessagesSubject = new BehaviorSubject<boolean>(false)
  public printTechnicalMessages$ = this.printTechnicalMessagesSubject.asObservable()

  private hideTechnicalMessagesSubject = new BehaviorSubject<boolean>(true)
  public hideTechnicalMessages$ = this.hideTechnicalMessagesSubject.asObservable()

  private hideWarningMessagesSubject = new BehaviorSubject<boolean>(false)
  public hideWarningMessages$ = this.hideWarningMessagesSubject.asObservable()

  private agentNotificationEnabledSubject = new BehaviorSubject<boolean>(false)
  public agentNotificationEnabled$ = this.agentNotificationEnabledSubject.asObservable()

  private notificationSoundEnabledSubject = new BehaviorSubject<boolean>(false)
  public notificationSoundEnabled$ = this.notificationSoundEnabledSubject.asObservable()

  private browserNotificationEnabledSubject = new BehaviorSubject<boolean>(false)
  public browserNotificationEnabled$ = this.browserNotificationEnabledSubject.asObservable()

  constructor() {
    console.log('[PREFERENCES] Initializing preferences service')

    // Check if running in Electron desktop app
    if (typeof window !== 'undefined' && (window as any).codayDesktop?.storage) {
      this.isDesktopApp = true
      this.electronStorage = (window as any).codayDesktop.storage as ElectronStorage
      console.log('[PREFERENCES] Running in desktop app mode with persistent storage')
    }

    this.initializePreferences()
  }

  private initializePreferences(): void {
    // Use async initialization for desktop app
    if (this.isDesktopApp) {
      void this.loadPreferencesAsync()
    } else {
      this.loadPreferencesSync()
    }
  }

  private loadPreferencesSync(): void {
    const storedLanguage = this.getPreferenceSync<string>('voiceLanguage', 'en-US') ?? 'en-US'
    this.voiceLanguageSubject.next(storedLanguage)
    console.log('[PREFERENCES] Voice language initialized to:', storedLanguage)

    const storedEnterToSend = this.getPreferenceSync<boolean>('useEnterToSend', true) ?? true
    this.enterToSendSubject.next(storedEnterToSend)
    console.log('[PREFERENCES] Enter to send initialized to:', storedEnterToSend)

    const storedAnnounceEnabled = this.getPreferenceSync<boolean>('voiceAnnounceEnabled', false) ?? false
    this.voiceAnnounceEnabledSubject.next(storedAnnounceEnabled)

    const storedVoiceMode = this.getPreferenceSync<'speech' | 'notification'>('voiceMode', 'speech') ?? 'speech'
    this.voiceModeSubject.next(storedVoiceMode)

    const storedReadFullText = this.getPreferenceSync<boolean>('voiceReadFullText', false) ?? false
    this.voiceReadFullTextSubject.next(storedReadFullText)

    const storedVolume = this.getPreferenceSync<number>('voiceVolume', 0.8) ?? 0.8
    this.voiceVolumeSubject.next(storedVolume)

    const storedRate = this.getPreferenceSync<number>('voiceRate', 1.2) ?? 1.2
    this.voiceRateSubject.next(storedRate)

    const storedSelectedVoice = this.getPreferenceSync<string | null>('selectedVoice', null) ?? null
    this.selectedVoiceSubject.next(storedSelectedVoice)

    const storedPrintTechnicalMessages = this.getPreferenceSync<boolean>('printTechnicalMessages', false) ?? false
    this.printTechnicalMessagesSubject.next(storedPrintTechnicalMessages)

    const storedHideTechnicalMessages = this.getPreferenceSync<boolean>('hideTechnicalMessages', true) ?? true
    this.hideTechnicalMessagesSubject.next(storedHideTechnicalMessages)

    const storedHideWarningMessages = this.getPreferenceSync<boolean>('hideWarningMessages', false) ?? false
    this.hideWarningMessagesSubject.next(storedHideWarningMessages)

    const storedAgentNotificationEnabled = this.getPreferenceSync<boolean>('agentNotificationEnabled', false) ?? false
    this.agentNotificationEnabledSubject.next(storedAgentNotificationEnabled)

    const storedNotificationSoundEnabled = this.getPreferenceSync<boolean>('notificationSoundEnabled', true) ?? true
    this.notificationSoundEnabledSubject.next(storedNotificationSoundEnabled)

    const storedBrowserNotificationEnabled =
      this.getPreferenceSync<boolean>('browserNotificationEnabled', false) ?? false
    this.browserNotificationEnabledSubject.next(storedBrowserNotificationEnabled)

    console.log('[PREFERENCES] All preferences initialized successfully')
  }

  private async loadPreferencesAsync(): Promise<void> {
    const storedLanguage = (await this.getPreferenceAsync<string>('voiceLanguage', 'en-US')) ?? 'en-US'
    this.voiceLanguageSubject.next(storedLanguage)
    console.log('[PREFERENCES] Voice language initialized to:', storedLanguage)

    const storedEnterToSend = (await this.getPreferenceAsync<boolean>('useEnterToSend', true)) ?? true
    this.enterToSendSubject.next(storedEnterToSend)
    console.log('[PREFERENCES] Enter to send initialized to:', storedEnterToSend)

    const storedAnnounceEnabled = (await this.getPreferenceAsync<boolean>('voiceAnnounceEnabled', false)) ?? false
    this.voiceAnnounceEnabledSubject.next(storedAnnounceEnabled)

    const storedVoiceMode =
      (await this.getPreferenceAsync<'speech' | 'notification'>('voiceMode', 'speech')) ?? 'speech'
    this.voiceModeSubject.next(storedVoiceMode)

    const storedReadFullText = (await this.getPreferenceAsync<boolean>('voiceReadFullText', false)) ?? false
    this.voiceReadFullTextSubject.next(storedReadFullText)

    const storedVolume = (await this.getPreferenceAsync<number>('voiceVolume', 0.8)) ?? 0.8
    this.voiceVolumeSubject.next(storedVolume)

    const storedRate = (await this.getPreferenceAsync<number>('voiceRate', 1.2)) ?? 1.2
    this.voiceRateSubject.next(storedRate)

    const storedSelectedVoice = (await this.getPreferenceAsync<string | null>('selectedVoice', null)) ?? null
    this.selectedVoiceSubject.next(storedSelectedVoice)

    const storedPrintTechnicalMessages =
      (await this.getPreferenceAsync<boolean>('printTechnicalMessages', false)) ?? false
    this.printTechnicalMessagesSubject.next(storedPrintTechnicalMessages)

    const storedHideTechnicalMessages = (await this.getPreferenceAsync<boolean>('hideTechnicalMessages', true)) ?? true
    this.hideTechnicalMessagesSubject.next(storedHideTechnicalMessages)

    const storedHideWarningMessages = (await this.getPreferenceAsync<boolean>('hideWarningMessages', false)) ?? false
    this.hideWarningMessagesSubject.next(storedHideWarningMessages)

    const storedAgentNotificationEnabled =
      (await this.getPreferenceAsync<boolean>('agentNotificationEnabled', false)) ?? false
    this.agentNotificationEnabledSubject.next(storedAgentNotificationEnabled)

    const storedNotificationSoundEnabled =
      (await this.getPreferenceAsync<boolean>('notificationSoundEnabled', true)) ?? true
    this.notificationSoundEnabledSubject.next(storedNotificationSoundEnabled)

    const storedBrowserNotificationEnabled =
      (await this.getPreferenceAsync<boolean>('browserNotificationEnabled', false)) ?? false
    this.browserNotificationEnabledSubject.next(storedBrowserNotificationEnabled)

    console.log('[PREFERENCES] All preferences initialized successfully (async)')
  }

  getPreference<T>(key: string, defaultValue?: T): T | undefined {
    return this.getPreferenceSync(key, defaultValue)
  }

  private getPreferenceSync<T>(key: string, defaultValue?: T): T | undefined {
    try {
      const stored = localStorage.getItem(this.STORAGE_KEY)
      if (!stored) return defaultValue

      const preferences = JSON.parse(stored)
      return preferences[key] !== undefined ? preferences[key] : defaultValue
    } catch {
      return defaultValue
    }
  }

  private async getPreferenceAsync<T>(key: string, defaultValue?: T): Promise<T | undefined> {
    if (!this.electronStorage) {
      return this.getPreferenceSync(key, defaultValue)
    }

    try {
      const stored = await this.electronStorage.get(this.STORAGE_KEY)
      if (!stored) return defaultValue

      const preferences = JSON.parse(stored)
      return preferences[key] !== undefined ? preferences[key] : defaultValue
    } catch {
      return defaultValue
    }
  }

  setPreference<T>(key: string, value: T): void {
    if (this.isDesktopApp) {
      void this.setPreferenceAsync(key, value)
    } else {
      this.setPreferenceSync(key, value)
    }
  }

  private setPreferenceSync<T>(key: string, value: T): void {
    try {
      const stored = localStorage.getItem(this.STORAGE_KEY)
      const preferences = stored ? JSON.parse(stored) : {}

      preferences[key] = value
      localStorage.setItem(this.STORAGE_KEY, JSON.stringify(preferences))

      this.updateSubject(key, value)
    } catch (error) {
      console.error('Failed to set preference:', error)
    }
  }

  private async setPreferenceAsync<T>(key: string, value: T): Promise<void> {
    if (!this.electronStorage) {
      this.setPreferenceSync(key, value)
      return
    }

    try {
      const stored = await this.electronStorage.get(this.STORAGE_KEY)
      const preferences = stored ? JSON.parse(stored) : {}

      preferences[key] = value
      await this.electronStorage.set(this.STORAGE_KEY, JSON.stringify(preferences))

      this.updateSubject(key, value)
    } catch (error) {
      console.error('Failed to set preference:', error)
    }
  }

  private updateSubject<T>(key: string, value: T): void {
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
    if (key === 'printTechnicalMessages') {
      this.printTechnicalMessagesSubject.next(value as boolean)
    }
    if (key === 'hideTechnicalMessages') {
      this.hideTechnicalMessagesSubject.next(value as boolean)
    }
    if (key === 'hideWarningMessages') {
      this.hideWarningMessagesSubject.next(value as boolean)
    }
    if (key === 'agentNotificationEnabled') {
      this.agentNotificationEnabledSubject.next(value as boolean)
    }
    if (key === 'notificationSoundEnabled') {
      this.notificationSoundEnabledSubject.next(value as boolean)
    }
    if (key === 'browserNotificationEnabled') {
      this.browserNotificationEnabledSubject.next(value as boolean)
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

  setPrintTechnicalMessages(enabled: boolean): void {
    console.log('[PREFERENCES] Setting print technical messages to:', enabled)
    this.setPreference('printTechnicalMessages', enabled)
  }

  getPrintTechnicalMessages(): boolean {
    return this.getPreference<boolean>('printTechnicalMessages', false) ?? false
  }

  setHideTechnicalMessages(enabled: boolean): void {
    console.log('[PREFERENCES] Setting hide technical messages to:', enabled)
    this.setPreference('hideTechnicalMessages', enabled)
  }

  getHideTechnicalMessages(): boolean {
    return this.getPreference<boolean>('hideTechnicalMessages', true) ?? true
  }

  setHideWarningMessages(enabled: boolean): void {
    console.log('[PREFERENCES] Setting hide warning messages to:', enabled)
    this.setPreference('hideWarningMessages', enabled)
  }

  getHideWarningMessages(): boolean {
    return this.getPreference<boolean>('hideWarningMessages', false) ?? false
  }

  setAgentNotificationEnabled(enabled: boolean): void {
    console.log('[PREFERENCES] Setting agent notification enabled to:', enabled)
    this.setPreference('agentNotificationEnabled', enabled)
  }

  getAgentNotificationEnabled(): boolean {
    return this.getPreference<boolean>('agentNotificationEnabled', false) ?? false
  }

  setNotificationSoundEnabled(enabled: boolean): void {
    console.log('[PREFERENCES] Setting notification sound enabled to:', enabled)
    this.setPreference('notificationSoundEnabled', enabled)
  }

  getNotificationSoundEnabled(): boolean {
    return this.getPreference<boolean>('notificationSoundEnabled', true) ?? true
  }

  setBrowserNotificationEnabled(enabled: boolean): void {
    console.log('[PREFERENCES] Setting browser notification enabled to:', enabled)
    this.setPreference('browserNotificationEnabled', enabled)
  }

  getBrowserNotificationEnabled(): boolean {
    return this.getPreference<boolean>('browserNotificationEnabled', false) ?? false
  }
}
