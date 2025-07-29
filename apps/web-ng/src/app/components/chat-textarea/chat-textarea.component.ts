import { Component, Output, EventEmitter, Input, OnInit, OnDestroy, AfterViewInit, ViewChild, ElementRef } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { Subscription } from 'rxjs'
import { PreferencesService } from '../../services/preferences.service'

@Component({
  selector: 'app-chat-textarea',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './chat-textarea.component.html',
  styleUrl: './chat-textarea.component.scss'
})
export class ChatTextareaComponent implements OnInit, OnDestroy, AfterViewInit {
  // Constantes pour la taille du textarea
  private static readonly MIN_TEXTAREA_LINES = 2
  private static readonly MAX_TEXTAREA_LINES = 15
  @Input() isDisabled: boolean = false
  @Output() messageSubmitted = new EventEmitter<string>()
  @Output() voiceRecordingToggled = new EventEmitter<boolean>()
  
  @ViewChild('messageInput', { static: true }) messageInput!: ElementRef<HTMLTextAreaElement>
  
  message: string = ''
  isRecording: boolean = false
  
  // Voice recognition properties
  private recognition: any = null
  private sessionHadTranscript: boolean = false
  private pendingLineBreaksTimeout: number | null = null
  private voiceLanguageSubscription?: Subscription
  private enterToSendSubscription?: Subscription
  
  // Enter behavior preference
  private useEnterToSend: boolean = false
  
  constructor(private preferencesService: PreferencesService) {}
  
  ngOnInit(): void {
    this.initializeVoiceInput()
    
    // Initialiser la préférence du comportement de la touche Entrée
    this.useEnterToSend = this.preferencesService.getEnterToSend()
    
    // Écouter les changements de langue vocale
    this.voiceLanguageSubscription = this.preferencesService.voiceLanguage$.subscribe(
      () => this.updateRecognitionLanguage()
    )
    
    // Écouter les changements du comportement de la touche Entrée
    this.enterToSendSubscription = this.preferencesService.enterToSend$.subscribe(
      (useEnterToSend) => {
        this.useEnterToSend = useEnterToSend
        console.log('[CHAT-TEXTAREA] Enter to send preference changed to:', useEnterToSend)
      }
    )
  }
  
  ngAfterViewInit(): void {
    // Set initial height after view initialization
    this.adjustTextareaHeight()
  }
  
  ngOnDestroy(): void {
    if (this.voiceLanguageSubscription) {
      this.voiceLanguageSubscription.unsubscribe()
    }
    if (this.enterToSendSubscription) {
      this.enterToSendSubscription.unsubscribe()
    }
    this.clearPendingLineBreaks()
  }
  
  onKeyDown(event: KeyboardEvent) {
    if (event.key === 'Enter') {
      if (this.useEnterToSend) {
        // Mode: Entrée pour envoyer, Shift+Entrée pour nouvelle ligne
        if (!event.shiftKey && !event.metaKey && !event.ctrlKey) {
          event.preventDefault()
          this.sendMessage()
        }
        // Shift+Entrée : laisser le comportement par défaut (nouvelle ligne)
      } else {
        // Mode: Cmd/Ctrl+Entrée pour envoyer, Entrée pour nouvelle ligne
        const isMac = navigator.platform.toLowerCase().includes('mac')
        const correctModifier = isMac ? event.metaKey : event.ctrlKey
        
        if (correctModifier && !event.shiftKey) {
          event.preventDefault()
          this.sendMessage()
        }
        // Entrée simple : laisser le comportement par défaut (nouvelle ligne)
      }
    }
  }
  
  onInput() {
    // Adjust textarea height when content changes
    this.adjustTextareaHeight()
  }
  
  sendMessage() {
    if (this.message.trim() && !this.isDisabled) {
      this.messageSubmitted.emit(this.message.trim())
      this.message = ''
      // Reset height after clearing message
      setTimeout(() => this.adjustTextareaHeight(), 0)
    }
  }
  
  toggleVoiceRecording() {
    // Cette méthode est appelée par le clic simple sur le bouton
    // Mais on utilise maintenant le mode push-to-talk avec les événements mousedown/mouseup
    console.log('Toggle voice recording called - using push-to-talk mode instead')
  }
  
  // Méthodes pour le mode push-to-talk
  onVoiceButtonMouseDown(event: MouseEvent): void {
    event.preventDefault()
    this.startRecording()
  }
  
  onVoiceButtonMouseUp(): void {
    this.stopRecording()
  }
  
  onVoiceButtonMouseLeave(): void {
    this.stopRecording()
  }
  
  onVoiceButtonTouchStart(event: TouchEvent): void {
    event.preventDefault()
    this.startRecording()
  }
  
  onVoiceButtonTouchEnd(event: TouchEvent): void {
    event.preventDefault()
    this.stopRecording()
  }
  
  onVoiceButtonKeyDown(event: KeyboardEvent): void {
    if (event.code === 'Space' || event.key === ' ' || event.keyCode === 32) {
      event.preventDefault()
      if (!this.isRecording) {
        this.startRecording()
      }
    }
  }
  
  onVoiceButtonKeyUp(event: KeyboardEvent): void {
    if (event.code === 'Space' || event.key === ' ' || event.keyCode === 32) {
      event.preventDefault()
      this.stopRecording()
    }
  }
  
  private initializeVoiceInput(): void {
    // Vérifier si Speech Recognition est disponible
    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition
    console.log('[SPEECH] speechRecognition', SpeechRecognition)
    if (!SpeechRecognition) {
      console.warn('[SPEECH] Speech Recognition API not available')
      return
    }

    // Créer une instance de reconnaissance
    this.recognition = new SpeechRecognition()
    this.recognition.continuous = true // Permet de longues phrases ininterrompues
    this.recognition.interimResults = true
    this.recognition.maxAlternatives = 1
    this.recognition.lang = this.getSelectedLanguage()

    // Paramètres avancés pour la capture de longues phrases
    try {
      if ('grammars' in this.recognition) {
        this.recognition.serviceURI = undefined // Forcer l'utilisation du service par défaut
      }
    } catch (e) {
      console.log('[SPEECH] Advanced speech recognition parameters not supported')
    }

    // Gérer les résultats
    this.recognition.onresult = (event: any) => {
      let finalTranscript = ''

      for (let i = event.resultIndex; i < event.results.length; i++) {
        const transcript = event.results[i][0].transcript
        if (event.results[i].isFinal) {
          finalTranscript += transcript
        }
      }

      if (finalTranscript) {
        // Post-traitement pour la ponctuation
        const processedTranscript = this.improveTranscriptPunctuation(finalTranscript.trim())
        this.sessionHadTranscript = true
        console.log('[SPEECH] onresult ending, appending to textarea', this.isRecording)
        this.appendToTextarea(processedTranscript)
      }
    }

    this.recognition.onend = () => {
      console.log('[SPEECH] Recognition ended. Was recording:', this.isRecording)
      this.isRecording = false
      this.voiceRecordingToggled.emit(this.isRecording)
    }

    this.recognition.onerror = (event: any) => {
      console.error('[SPEECH] Speech recognition error:', event.error)
      console.log('[SPEECH] Error details:', {
        error: event.error,
        message: event.message,
        timeStamp: event.timeStamp,
      })
      this.isRecording = false
      this.clearPendingLineBreaks()
      this.voiceRecordingToggled.emit(this.isRecording)
    }

    // Événements de débogage
    this.recognition.onstart = () => {
      console.log('[SPEECH] Recognition started successfully')
    }

    this.recognition.onspeechstart = () => {
      console.log('[SPEECH] Speech detected')
    }

    this.recognition.onspeechend = () => {
      console.log('[SPEECH] Speech ended')
    }
  }

  private async startRecording(): Promise<void> {
    if (!this.recognition || this.isRecording) return

    try {
      this.isRecording = true
      this.sessionHadTranscript = false // Réinitialiser au début d'une nouvelle session
      this.clearPendingLineBreaks() // Effacer les timeouts en attente de la session précédente
      this.voiceRecordingToggled.emit(this.isRecording)
      this.recognition.start()
      console.log('[SPEECH] Started recording')
    } catch (error) {
      console.error('[SPEECH] Failed to start recording:', error)
      this.isRecording = false
      this.voiceRecordingToggled.emit(this.isRecording)
    }
  }

  private stopRecording(): void {
    if (!this.recognition || !this.isRecording) {
      console.log('[SPEECH] stopRecording not recording')
      return
    }

    try {
      console.log('[SPEECH] stopRecording recognition stop')
      this.recognition.stop()

      // Programmer les sauts de ligne après un court délai pour permettre les transcriptions en attente
      this.schedulePendingLineBreaks()
    } catch (error) {
      console.error('[SPEECH] Failed to stop recording:', error)
    }
  }

  private getSelectedLanguage(): string {
    return this.preferencesService.getVoiceLanguage()
  }

  private updateRecognitionLanguage(): void {
    if (this.recognition) {
      const selectedLang = this.getSelectedLanguage()
      this.recognition.lang = selectedLang
      console.log('[SPEECH] Language changed to:', selectedLang)
    }
  }

  private improveTranscriptPunctuation(text: string): string {
    let improved = text

    // Terminer les phrases
    if (!/[.!?]$/.test(improved.trim())) {
      improved = improved.trim() + '.'
    }

    // Mettre en majuscule la première lettre
    improved = improved.charAt(0).toUpperCase() + improved.slice(1)

    // Supprimer les espaces multiples
    improved = improved.replace(/\s+/g, ' ')

    // Ajouter un espace à la fin pour une séparation naturelle
    improved = improved + ' '

    return improved
  }

  private appendToTextarea(text: string): void {
    const currentValue = this.message
    const newValue = currentValue ? `${currentValue}${text}` : text
    this.message = newValue
    
    // Focus sur le textarea et placer le curseur à la fin
    if (this.messageInput?.nativeElement) {
      this.messageInput.nativeElement.focus()
      const length = this.message.length
      this.messageInput.nativeElement.setSelectionRange(length, length)
    }
    
    // Adjust height after programmatic content change
    setTimeout(() => this.adjustTextareaHeight(), 0)
  }

  private schedulePendingLineBreaks(): void {
    this.clearPendingLineBreaks() // Effacer tout timeout existant

    // Attendre 500ms pour que les transcriptions en attente arrivent
    this.pendingLineBreaksTimeout = window.setTimeout(() => {
      if (this.sessionHadTranscript) {
        console.log('[SPEECH] Adding line breaks after transcript session')
        this.appendToTextarea('\n\n')
        this.sessionHadTranscript = false // Réinitialiser pour la prochaine session
      }
      this.pendingLineBreaksTimeout = null
    }, 500)
  }

  private clearPendingLineBreaks(): void {
    if (this.pendingLineBreaksTimeout) {
      clearTimeout(this.pendingLineBreaksTimeout)
      this.pendingLineBreaksTimeout = null
    }
  }
  
  /**
   * Adjusts the textarea height based on content
   * Sets min-height equivalent to MIN_TEXTAREA_LINES rows and max-height equivalent to MAX_TEXTAREA_LINES rows
   */
  private adjustTextareaHeight(): void {
    const textarea = this.messageInput?.nativeElement
    if (!textarea) return
    
    // Calculate line height (approximately 1.5em based on CSS)
    const style = window.getComputedStyle(textarea)
    const fontSize = parseFloat(style.fontSize)
    const lineHeight = parseFloat(style.lineHeight) || fontSize * 1.5
    
    // Define min and max heights in pixels using constants
    const minHeight = lineHeight * ChatTextareaComponent.MIN_TEXTAREA_LINES + parseFloat(style.paddingTop) + parseFloat(style.paddingBottom)
    const maxHeight = lineHeight * ChatTextareaComponent.MAX_TEXTAREA_LINES + parseFloat(style.paddingTop) + parseFloat(style.paddingBottom)
    
    // Reset height to auto to get the actual scroll height
    textarea.style.height = 'auto'
    
    // Calculate the new height based on scroll height
    let newHeight = textarea.scrollHeight
    
    // Apply min/max constraints
    if (newHeight < minHeight) {
      newHeight = minHeight
    } else if (newHeight > maxHeight) {
      newHeight = maxHeight
    }
    
    // Set the new height
    textarea.style.height = `${newHeight}px`
  }
  
  /**
   * Obtient le raccourci clavier actuel pour l'envoi de message
   */
  getSendButtonShortcut(): string {
    if (this.useEnterToSend) {
      return 'Enter'
    } else {
      const isMac = navigator.platform.toLowerCase().includes('mac')
      return isMac ? '⌘+Enter' : 'Ctrl+Enter'
    }
  }
  
  /**
   * Obtient le tooltip du bouton d'envoi
   */
  getSendButtonTooltip(): string {
    if (this.useEnterToSend) {
      return 'Send message (Enter) - Shift+Enter for new line'
    } else {
      const isMac = navigator.platform.toLowerCase().includes('mac')
      const shortcut = isMac ? 'Cmd+Enter' : 'Ctrl+Enter'
      return `Send message (${shortcut}) - Enter for new line`
    }
  }
  
  // TODO: Add file upload drag & drop
}