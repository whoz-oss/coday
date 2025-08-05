import { Injectable, OnDestroy } from '@angular/core'
import { Subject, BehaviorSubject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { PreferencesService } from './preferences.service'

export interface VoiceInfo {
  name: string
  lang: string
  localService: boolean
  default: boolean
  displayName: string
}

@Injectable({
  providedIn: 'root'
})
export class VoiceSynthesisService implements OnDestroy {
  private destroy$ = new Subject<void>()
  private voices: Promise<SpeechSynthesisVoice[]> | undefined
  private currentUtterance: SpeechSynthesisUtterance | null = null
  private currentOnEndCallback: (() => void) | null = null
  
  // Observable pour l'état de lecture global
  private speakingSubject = new BehaviorSubject<boolean>(false)
  public speaking$ = this.speakingSubject.asObservable()
  
  private volume: number = 0.8
  private rate: number = 1.2
  private language: string = 'en-US'
  private selectedVoice: SpeechSynthesisVoice | undefined

  constructor(private preferencesService: PreferencesService) {
    this.initializeSpeechSynthesis()
    this.subscribeToPreferences()
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
    this.stopSpeech()
  }

  private initializeSpeechSynthesis(): void {
    if (!('speechSynthesis' in window)) {
      console.warn('[VOICE-SERVICE] Speech synthesis not available')
      this.voices = new Promise((resolve) => resolve([]))
      return
    }

    this.voices = new Promise((resolve, reject) => {
      const immediateVoices = speechSynthesis.getVoices()
      if (immediateVoices.length) {
        resolve(immediateVoices)
      } else {
        const handle = setTimeout(() => {
          reject('no voices found')
        }, 2000)
        speechSynthesis.addEventListener('voiceschanged', () => {
          const delayedVoices = speechSynthesis.getVoices()
          if (delayedVoices.length) {
            clearTimeout(handle)
            resolve(delayedVoices)
          }
        })
      }
    })

    console.log('[VOICE-SERVICE] Speech synthesis initialized')
  }

  private subscribeToPreferences(): void {
    // Volume
    this.preferencesService.voiceVolume$
      .pipe(takeUntil(this.destroy$))
      .subscribe(volume => {
        this.volume = volume
        console.log('[VOICE-SERVICE] Volume updated to:', volume)
      })

    // Speed
    this.preferencesService.voiceRate$
      .pipe(takeUntil(this.destroy$))
      .subscribe(rate => {
        this.rate = rate
        console.log('[VOICE-SERVICE] Rate updated to:', rate)
      })

    // Language
    this.preferencesService.voiceLanguage$
      .pipe(takeUntil(this.destroy$))
      .subscribe(language => {
        this.language = language
        console.log('[VOICE-SERVICE] Language updated to:', language)
        this.updateSelectedVoiceForLanguage()
      })

    this.preferencesService.selectedVoice$
      .pipe(takeUntil(this.destroy$))
      .subscribe(voice => {
        this.setSelectedVoice(voice)
      })

    this.volume = this.preferencesService.getVoiceVolume()
    this.rate = this.preferencesService.getVoiceRate()
    this.language = this.preferencesService.getVoiceLanguage()
    this.setSelectedVoice(this.preferencesService.getSelectedVoice())
  }

  isReady(): boolean {
    return 'speechSynthesis' in window && this.selectedVoice !== undefined
  }

  isSpeaking(): boolean {
    return speechSynthesis.speaking
  }

  async speak(text: string, onEnd?: () => void): Promise<boolean> {
    if (!this.selectedVoice) {
      console.warn('[VOICE-SERVICE] No voice selected')
      return false
    }

    if (!text.trim()) {
      console.warn('[VOICE-SERVICE] No text to speak')
      return false
    }

    // Nettoyer le markdown pour la synthèse vocale
    const cleanText = this.extractPlainText(text)
    
    if (!cleanText.trim()) {
      console.warn('[VOICE-SERVICE] No text to speak after markdown cleaning')
      return false
    }

    this.stopSpeech()

    try {
      const utterance = new SpeechSynthesisUtterance(cleanText)
      utterance.voice = this.selectedVoice
      utterance.lang = this.selectedVoice.lang
      utterance.volume = this.volume
      utterance.rate = this.rate

      this.currentOnEndCallback = onEnd || null

      if (onEnd) {
        utterance.onend = () => {
          if (this.currentOnEndCallback === onEnd) {
            this.currentOnEndCallback = null
            onEnd()
          }
        }
        utterance.onerror = () => {
          if (this.currentOnEndCallback === onEnd) {
            this.currentOnEndCallback = null
            onEnd()
          }
        }
      }

      this.currentUtterance = utterance
      
      // Mettre à jour l'état global au démarrage
      this.speakingSubject.next(true)
      
      // Ajouter les callbacks pour mettre à jour l'état global
      const originalOnEnd = utterance.onend
      const originalOnError = utterance.onerror
      
      utterance.onend = (event) => {
        this.speakingSubject.next(false)
        if (originalOnEnd) originalOnEnd.call(utterance, event)
      }
      
      utterance.onerror = (event) => {
        this.speakingSubject.next(false)
        if (originalOnError) originalOnError.call(utterance, event)
      }
      
      speechSynthesis.speak(utterance)
      
      console.log('[VOICE-SERVICE] Started speaking:', text.substring(0, 50) + '...')
      return true
    } catch (error) {
      console.error('[VOICE-SERVICE] Error speaking:', error)
      return false
    }
  }

  stopSpeech(): void {
    if (this.currentUtterance || speechSynthesis.speaking) {
      speechSynthesis.cancel()
      this.currentUtterance = null
      
      // Exécuter le callback avant de le nettoyer
      if (this.currentOnEndCallback) {
        const callback = this.currentOnEndCallback
        this.currentOnEndCallback = null
        callback()
      }
      
      // Mettre à jour l'état global
      this.speakingSubject.next(false)
      
      console.log('[VOICE-SERVICE] Speech stopped')
    }
  }

  ding(): void {
    try {
      const audioContext = new (window.AudioContext || (window as any).webkitAudioContext)()
      const oscillator = audioContext.createOscillator()
      const gainNode = audioContext.createGain()

      oscillator.connect(gainNode)
      gainNode.connect(audioContext.destination)

      oscillator.frequency.setValueAtTime(800, audioContext.currentTime) // 800Hz tone
      gainNode.gain.setValueAtTime(0.1, audioContext.currentTime) // Low volume
      gainNode.gain.exponentialRampToValueAtTime(0.01, audioContext.currentTime + 0.1)

      oscillator.start(audioContext.currentTime)
      oscillator.stop(audioContext.currentTime + 0.1)
      
      console.log('[VOICE-SERVICE] Notification sound played')
    } catch (error) {
      console.error('[VOICE-SERVICE] Notification sound failed:', error)
    }
  }

  async getVoicesForLanguage(): Promise<VoiceInfo[]> {
    try {
      const langCode = this.language.slice(0, 2)
      const allVoices = await this.voices
      
      if (!allVoices || allVoices.length === 0) {
        console.warn('[VOICE-SERVICE] No voices available on system')
        return []
      }

      const matchingVoices = allVoices
        .filter((voice) => voice.lang.toLowerCase().startsWith(langCode))
        .sort((a, b) => {
          // Local voices first
          if (a.localService && !b.localService) return -1
          if (!a.localService && b.localService) return 1
          return a.name.localeCompare(b.name)
        })

      console.log('[VOICE-SERVICE] Found matching voices:', matchingVoices.length, 'for language:', langCode)
      
      return matchingVoices.map((voice) => ({
        name: voice.name,
        lang: voice.lang,
        localService: voice.localService,
        default: voice.default,
        displayName: this.formatVoiceDisplayName(voice),
      }))
    } catch (error) {
      console.error('[VOICE-SERVICE] Error getting voices for language:', error)
      return []
    }
  }

  private formatVoiceDisplayName(voice: SpeechSynthesisVoice): string {
    const localMarker = voice.localService ? '🏠' : '☁️'
    const defaultMarker = voice.default ? ' ⭐' : ''
    return `${localMarker} ${voice.name} (${voice.lang})${defaultMarker}`
  }

  private async setSelectedVoice(voice: string | null): Promise<void> {
    if (!voice) {
      this.selectedVoice = undefined
      return
    }

    const [voiceName, voiceLang] = voice.split('|')

    if (!voiceName || !voiceLang) {
      this.selectedVoice = undefined
      return
    }

    const foundVoice = (await this.voices)?.find((v) => v.name === voiceName && v.lang === voiceLang)

    if (foundVoice) {
      this.selectedVoice = foundVoice
      console.log('[VOICE-SERVICE] Selected voice:', foundVoice.name, foundVoice.lang)
    } else {
      this.selectedVoice = undefined
      console.warn('[VOICE-SERVICE] Voice not found:', voiceName, voiceLang)
    }
  }

  private async updateSelectedVoiceForLanguage(): Promise<void> {
    const availableVoices = await this.getVoicesForLanguage()
    
    if (availableVoices.length === 0) {
      console.warn('[VOICE-SERVICE] No voices available for language:', this.language)
      this.selectedVoice = undefined
      this.preferencesService.setSelectedVoice(null)
      return
    }

    // Vérifier si la voix actuellement sélectionnée est compatible avec la nouvelle langue
    const currentSelectedVoice = this.preferencesService.getSelectedVoice()
    
    if (currentSelectedVoice) {
      const [currentVoiceName, currentVoiceLang] = currentSelectedVoice.split('|')
      const currentLangCode = currentVoiceLang?.slice(0, 2)
      const newLangCode = this.language.slice(0, 2)
      
      // Si la voix actuelle est compatible avec la nouvelle langue, la garder
      if (currentLangCode === newLangCode) {
        const isStillAvailable = availableVoices.some(v => 
          v.name === currentVoiceName && v.lang === currentVoiceLang
        )
        
        if (isStillAvailable) {
          console.log('[VOICE-SERVICE] Keeping current voice for language:', currentSelectedVoice)
          return
        }
      }
    }

    // Seulement si aucune voix compatible n'est sélectionnée, auto-sélectionner la première
    const firstVoice = availableVoices[0]
    if (firstVoice) {
      const voiceId = `${firstVoice.name}|${firstVoice.lang}`
      
      this.preferencesService.setSelectedVoice(voiceId)
      
      console.log('[VOICE-SERVICE] Auto-selected voice for language change:', firstVoice.displayName)
    }
  }

  testSelectedVoice(): void {
    setTimeout(() => {
      if (!this.selectedVoice) {
        console.log('[VOICE-SERVICE] No voice selected for test')
        return
      }
      
      this.stopSpeech()
      
      const testText = this.getTestText()
      if (!testText) {
        return
      }

      this.speak(testText)
    }, 100)
  }

  private getTestText(): string {
    const langCode = this.language.slice(0, 2)
    
    const testTexts: Record<string, string> = {
      fr: 'Bonjour, ceci est un test de la voix sélectionnée.',
      en: 'Hello, this is a test of the selected voice.',
      es: 'Hola, esta es una prueba de la voz seleccionada.',
      de: 'Hallo, dies ist ein Test der ausgewählten Stimme.',
      it: 'Ciao, questo è un test della voce selezionata.',
      pt: 'Olá, este é um teste da voz selecionada.',
      ja: 'こんにちは、これは選択された音声のテストです。',
      ko: '안녕하세요, 이것은 선택된 음성의 테스트입니다.',
      zh: '你好，这是所选语音的测试。',
      ru: 'Привет, это тест выбранного голоса.',
      ar: 'مرحبا، هذا اختبار للصوت المحدد.',
    }

    return testTexts[langCode] || testTexts['en'] || 'Hello, this is a test.'
  }
  
  /**
   * Extraire le texte brut d'un contenu markdown pour la synthèse vocale
   * (porté de l'ancienne application)
   */
  extractPlainText(text: string): string {
    let processed = text
      // Remove emojis (Unicode ranges for emojis) + specific stars
      .replace(/[\u{1F600}-\u{1F64F}]|[\u{1F300}-\u{1F5FF}]|[\u{1F680}-\u{1F6FF}]|[\u{1F1E0}-\u{1F1FF}]|[\u{2600}-\u{26FF}]|[\u{2700}-\u{27BF}]|⭐/gu, '')
      
      // Remove code blocks
      .replace(/```[\s\S]*?```/g, 'code block')
      .replace(/`([^`]+)`/g, '$1') // Inline code
      
      // Remove markdown formatting
      .replace(/\*\*\*(.*?)\*\*\*/g, '$1') // Bold italic
      .replace(/\*\*(.*?)\*\*/g, '$1') // Bold
      .replace(/\*(.*?)\*/g, '$1') // Italic
      .replace(/~~(.*?)~~/g, '$1') // Strikethrough
      
      // Remove headers
      .replace(/#{1,6}\s*(.*)/g, '$1')
      
      // Replace links with just the text
      .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
      
      // Remove standalone URLs
      .replace(/https?:\/\/[^\s]+/g, 'link')
      
      // Remove HTML entities
      .replace(/&nbsp;/g, ' ')
      .replace(/&amp;/g, 'and')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"')
    
    // Add punctuation for natural pauses BEFORE removing line breaks
    processed = this.addNaturalPunctuation(processed)
    
    return processed
      // Clean up multiple spaces and trim
      .replace(/\s+/g, ' ')
      .trim()
  }
  
  /**
   * Ajouter une ponctuation naturelle pour les pauses vocales
   */
  private addNaturalPunctuation(text: string): string {
    return text
      // Normalize multiple line breaks
      .replace(/\n+/g, '\n')
      .split('\n')
      .map(line => this.addPeriodIfNeeded(line.trim()))
      .filter(line => line.length > 0)
      .join(' ') // Join with spaces for speech synthesis
  }
  
  /**
   * Ajouter un point à la fin d'une ligne si nécessaire
   */
  private addPeriodIfNeeded(line: string): string {
    if (!line) return ''
    
    // If line doesn't end with punctuation, add a period
    if (!/[.!?;:]$/.test(line)) {
      return line + '.'
    }
    return line
  }
}