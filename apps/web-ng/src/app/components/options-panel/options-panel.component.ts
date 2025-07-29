import { Component, OnInit, OnDestroy } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { PreferencesService } from '../../services/preferences.service'

interface VoiceLanguageOption {
  code: string
  label: string
  flag: string
}

@Component({
  selector: 'app-options-panel',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './options-panel.component.html',
  styleUrl: './options-panel.component.scss'
})
export class OptionsPanelComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()
  
  isVisible = false
  selectedVoiceLanguage = 'en-US'
  useEnterToSend = false
  
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
    { code: 'ar-SA', label: 'العربية', flag: '🇸🇦' }
  ]
  
  constructor(private preferencesService: PreferencesService) {}
  
  ngOnInit(): void {
    // Initialiser avec la langue actuelle
    this.selectedVoiceLanguage = this.preferencesService.getVoiceLanguage()
    
    // Initialiser avec le comportement actuel de la touche Entrée
    this.useEnterToSend = this.preferencesService.getEnterToSend()
    
    // Écouter les changements de langue pour synchroniser l'affichage
    this.preferencesService.voiceLanguage$
      .pipe(takeUntil(this.destroy$))
      .subscribe(language => {
        this.selectedVoiceLanguage = language
      })
      
    // Écouter les changements du comportement de la touche Entrée
    this.preferencesService.enterToSend$
      .pipe(takeUntil(this.destroy$))
      .subscribe(useEnterToSend => {
        this.useEnterToSend = useEnterToSend
      })
  }
  
  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }
  
  togglePanel(): void {
    this.isVisible = !this.isVisible
  }
  
  closePanel(): void {
    this.isVisible = false
  }
  
  onVoiceLanguageChange(): void {
    console.log('[OPTIONS] Voice language changed to:', this.selectedVoiceLanguage)
    this.preferencesService.setVoiceLanguage(this.selectedVoiceLanguage)
  }
  
  onEnterToSendChange(): void {
    console.log('[OPTIONS] Enter to send changed to:', this.useEnterToSend)
    this.preferencesService.setEnterToSend(this.useEnterToSend)
  }
  
  onPanelClick(event: Event): void {
    // Empêcher la fermeture du panneau quand on clique à l'intérieur
    event.stopPropagation()
  }
  
  onBackdropClick(): void {
    // Fermer le panneau quand on clique en dehors
    this.closePanel()
  }
}