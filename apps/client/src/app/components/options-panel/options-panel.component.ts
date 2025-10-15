import { Component, OnInit, OnDestroy, inject } from '@angular/core'
import { FormsModule } from '@angular/forms'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { PreferencesService } from '../../services/preferences.service'
import { VoiceSynthesisService, VoiceInfo } from '../../services/voice-synthesis.service'

interface VoiceLanguageOption {
  code: string
  label: string
  flag: string
}

@Component({
  selector: 'app-options-panel',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './options-panel.component.html',
  styleUrl: './options-panel.component.scss',
})
export class OptionsPanelComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()

  selectedVoiceLanguage = 'en-US'
  useEnterToSend = false
  printTechnicalMessages = false

  voiceAnnounceEnabled = false
  voiceMode: 'speech' | 'notification' = 'speech'
  voiceReadFullText = false
  voiceVolume = 80
  voiceRate = 120

  availableVoices: VoiceInfo[] = []
  selectedVoiceId: string | null = null
  loadingVoices = false

  voiceLanguageOptions: VoiceLanguageOption[] = [
    { code: 'fr-FR', label: 'Français', flag: '🇫🇷' },
    { code: 'en-US', label: 'English (US)', flag: '🇺🇸' },
    { code: 'en-GB', label: 'English (UK)', flag: '🇬🇧' },
    { code: 'es-ES', label: 'Español', flag: '🇪🇸' },
    { code: 'de-DE', label: 'Deutsch', flag: '🇩🇪' },
    { code: 'it-IT', label: 'Italiano', flag: '🇮🇹' },
    { code: 'pt-BR', label: 'Português', flag: '🇧🇷' },
    { code: 'ja-JP', label: '日本語', flag: '🇯🇵' },
    { code: 'ko-KR', label: '한국어', flag: '🇰🇷' },
    { code: 'zh-CN', label: '中文 (简体)', flag: '🇨🇳' },
    { code: 'ru-RU', label: 'Русский', flag: '🇷🇺' },
    { code: 'ar-SA', label: 'العربية', flag: '🇸🇦' },
  ]

  // Modern Angular dependency injection
  private preferencesService = inject(PreferencesService)
  private voiceSynthesisService = inject(VoiceSynthesisService)

  ngOnInit(): void {
    this.selectedVoiceLanguage = this.preferencesService.getVoiceLanguage()
    this.useEnterToSend = this.preferencesService.getEnterToSend()
    this.printTechnicalMessages = this.preferencesService.getPrintTechnicalMessages()
    this.voiceAnnounceEnabled = this.preferencesService.getVoiceAnnounceEnabled()
    this.voiceMode = this.preferencesService.getVoiceMode()
    this.voiceReadFullText = this.preferencesService.getVoiceReadFullText()
    this.voiceVolume = Math.round(this.preferencesService.getVoiceVolume() * 100)
    this.voiceRate = Math.round(this.preferencesService.getVoiceRate() * 100)
    this.selectedVoiceId = this.preferencesService.getSelectedVoice()

    this.loadAvailableVoices()

    this.preferencesService.voiceLanguage$.pipe(takeUntil(this.destroy$)).subscribe((language) => {
      this.selectedVoiceLanguage = language
    })

    this.preferencesService.enterToSend$.pipe(takeUntil(this.destroy$)).subscribe((useEnterToSend) => {
      this.useEnterToSend = useEnterToSend
    })

    this.preferencesService.printTechnicalMessages$
      .pipe(takeUntil(this.destroy$))
      .subscribe((printTechnicalMessages) => {
        this.printTechnicalMessages = printTechnicalMessages
      })

    this.preferencesService.voiceAnnounceEnabled$.pipe(takeUntil(this.destroy$)).subscribe((enabled) => {
      this.voiceAnnounceEnabled = enabled
    })

    this.preferencesService.voiceMode$.pipe(takeUntil(this.destroy$)).subscribe((mode) => {
      this.voiceMode = mode
    })

    this.preferencesService.voiceReadFullText$.pipe(takeUntil(this.destroy$)).subscribe((enabled) => {
      this.voiceReadFullText = enabled
    })

    this.preferencesService.voiceVolume$.pipe(takeUntil(this.destroy$)).subscribe((volume) => {
      this.voiceVolume = Math.round(volume * 100)
    })

    this.preferencesService.voiceRate$.pipe(takeUntil(this.destroy$)).subscribe((rate) => {
      this.voiceRate = Math.round(rate * 100)
    })

    this.preferencesService.selectedVoice$.pipe(takeUntil(this.destroy$)).subscribe((voice) => {
      this.selectedVoiceId = voice
    })

    this.preferencesService.voiceLanguage$.pipe(takeUntil(this.destroy$)).subscribe(() => {
      this.loadAvailableVoices()
    })
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }

  onVoiceLanguageChange(): void {
    console.log('[OPTIONS] Voice language changed to:', this.selectedVoiceLanguage)
    this.preferencesService.setVoiceLanguage(this.selectedVoiceLanguage)
  }

  onEnterToSendChange(): void {
    console.log('[OPTIONS] Enter to send changed to:', this.useEnterToSend)
    this.preferencesService.setEnterToSend(this.useEnterToSend)
  }

  onPrintTechnicalMessagesChange(): void {
    console.log('[OPTIONS] Print technical messages changed to:', this.printTechnicalMessages)
    this.preferencesService.setPrintTechnicalMessages(this.printTechnicalMessages)
  }

  onVoiceAnnounceEnabledChange(): void {
    console.log('[OPTIONS] Voice announce enabled changed to:', this.voiceAnnounceEnabled)
    this.preferencesService.setVoiceAnnounceEnabled(this.voiceAnnounceEnabled)
  }

  onVoiceModeChange(): void {
    console.log('[OPTIONS] Voice mode changed to:', this.voiceMode)
    this.preferencesService.setVoiceMode(this.voiceMode)
  }

  onVoiceReadFullTextChange(): void {
    console.log('[OPTIONS] Voice read full text changed to:', this.voiceReadFullText)
    this.preferencesService.setVoiceReadFullText(this.voiceReadFullText)
  }

  onVoiceVolumeChange(): void {
    const normalizedVolume = this.voiceVolume / 100
    console.log('[OPTIONS] Voice volume changed to:', normalizedVolume)
    this.preferencesService.setVoiceVolume(normalizedVolume)
  }

  onVoiceRateChange(): void {
    const normalizedRate = this.voiceRate / 100
    console.log('[OPTIONS] Voice rate changed to:', normalizedRate)
    this.preferencesService.setVoiceRate(normalizedRate)
  }

  async loadAvailableVoices(): Promise<void> {
    this.loadingVoices = true
    try {
      this.availableVoices = await this.voiceSynthesisService.getVoicesForLanguage()
      console.log('[OPTIONS] Loaded', this.availableVoices.length, 'voices for current language')
    } catch (error) {
      console.error('[OPTIONS] Error loading voices:', error)
      this.availableVoices = []
    } finally {
      this.loadingVoices = false
    }
  }

  onSelectedVoiceChange(): void {
    console.log('[OPTIONS] Selected voice changed to:', this.selectedVoiceId)
    this.preferencesService.setSelectedVoice(this.selectedVoiceId)
  }

  testVoice(): void {
    console.log('[OPTIONS] Testing voice with current settings')
    this.voiceSynthesisService.testSelectedVoice()
  }
}
