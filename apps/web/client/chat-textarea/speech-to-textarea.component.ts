import { getPreference } from '../utils/preferences'

export class SpeechToTextareaComponent {
  // Voice input properties
  private recognition: any = null
  private voiceButton: HTMLButtonElement | null = null
  private isRecording: boolean = false
  private sessionHadTranscript: boolean = false
  private pendingLineBreaksTimeout: number | null = null

  constructor(
    private readonly chatTextarea: HTMLTextAreaElement,
    private readonly submitButton: HTMLButtonElement
  ) {
    this.initializeVoiceInput()

    // Listen for voice language changes from options panel
    window.addEventListener('voiceLanguageChanged', (event: any) => {
      this.updateRecognitionLanguage()
    })
  }

  private initializeVoiceInput(): void {
    // Check if Speech Recognition is available
    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition
    console.log('speechRecognition', SpeechRecognition)
    if (!SpeechRecognition) return

    // Create recognition instance
    this.recognition = new SpeechRecognition()
    this.recognition.continuous = true // Permet des phrases longues sans coupure
    this.recognition.interimResults = true
    this.recognition.maxAlternatives = 1
    this.recognition.lang = this.getSelectedLanguage() // Utilise la langue sélectionnée

    // Paramètres avancés pour améliorer la capture de phrases longues
    // Ces paramètres ne sont pas standards mais supportés par certains navigateurs
    try {
      // Augmente le délai avant que la reconnaissance s'arrête automatiquement
      if ('grammars' in this.recognition) {
        // Certains navigateurs supportent des paramètres étendus
        this.recognition.serviceURI = undefined // Force l'utilisation du service par défaut
      }
    } catch (e) {
      // Les paramètres avancés ne sont pas supportés, on continue avec la config de base
      console.log('Paramètres avancés de reconnaissance vocale non supportés')
    }

    // Handle results
    this.recognition.onresult = (event: any) => {
      let finalTranscript = ''

      for (let i = event.resultIndex; i < event.results.length; i++) {
        const transcript = event.results[i][0].transcript
        if (event.results[i].isFinal) {
          finalTranscript += transcript
        }
      }

      if (finalTranscript) {
        // Post-traitement pour améliorer la ponctuation
        const processedTranscript = this.improveTranscriptPunctuation(finalTranscript.trim())
        this.sessionHadTranscript = true
        console.log('onresult ending, appending to textarea', this.isRecording)
        this.appendToTextarea(processedTranscript)
      }
    }

    this.recognition.onend = () => {
      this.isRecording = false
      console.log('Recognition ended. Was recording:', this.isRecording)
      this.updateVoiceButtonState()
    }

    this.recognition.onerror = (event: any) => {
      console.error('Speech recognition error:', event.error)
      console.log('Error details:', {
        error: event.error,
        message: event.message,
        timeStamp: event.timeStamp,
      })
      this.isRecording = false
      this.clearPendingLineBreaks() // Clear any pending timeouts
      this.updateVoiceButtonState()
    }

    // Événement pour déboguer les démarrages/arrêts
    this.recognition.onstart = () => {
      console.log('Recognition started successfully')
    }

    this.recognition.onspeechstart = () => {
      console.log('Speech detected')
    }

    this.recognition.onspeechend = () => {
      console.log('Speech ended')
    }

    this.createVoiceControls()
  }

  private createVoiceControls(): void {
    // Find the send button to place voice controls next to it
    const buttonContainer = this.submitButton.parentElement
    if (!buttonContainer) return

    // Create voice button (no language selector here anymore)
    this.voiceButton = document.createElement('button')
    this.voiceButton.type = 'button'
    this.voiceButton.className = 'voice-button'
    this.voiceButton.tabIndex = 0 // Permet le focus au clavier
    this.voiceButton.innerHTML = '🎤'
    this.voiceButton.title = 'Maintenir clic souris, touch, ou barre espace pour parler'
    this.voiceButton.style.marginRight = '8px'

    // Insert before send button
    buttonContainer.insertBefore(this.voiceButton, this.submitButton)

    // Mouse events for push-to-talk
    this.voiceButton.addEventListener('mousedown', (e) => {
      e.preventDefault()
      this.startRecording()
    })

    this.voiceButton.addEventListener('mouseup', () => {
      this.stopRecording()
    })

    this.voiceButton.addEventListener('mouseleave', () => {
      this.stopRecording()
    })

    // Touch events for mobile
    this.voiceButton.addEventListener('touchstart', (e) => {
      e.preventDefault()
      this.startRecording()
    })

    this.voiceButton.addEventListener('touchend', (e) => {
      e.preventDefault()
      this.stopRecording()
    })

    // Keyboard accessibility: Space bar = push-to-talk when button has focus
    this.voiceButton.addEventListener('keydown', (e: KeyboardEvent) => {
      if (e.code === 'Space' || e.key === ' ' || e.keyCode === 32) {
        e.preventDefault()
        // Démarre l'enregistrement seulement si ce n'est pas déjà en cours
        if (!this.isRecording) {
          this.startRecording()
        }
      }
    })

    this.voiceButton.addEventListener('keyup', (e: KeyboardEvent) => {
      if (e.code === 'Space' || e.key === ' ' || e.keyCode === 32) {
        e.preventDefault()
        this.stopRecording()
      }
    })
  }

  private async startRecording(): Promise<void> {
    if (!this.recognition || this.isRecording) return

    try {
      this.isRecording = true
      this.sessionHadTranscript = false // Reset at start of new session
      this.clearPendingLineBreaks() // Clear any pending timeouts from previous session
      this.updateVoiceButtonState()
      this.recognition.start()
    } catch (error) {
      console.error('Failed to start recording:', error)
      this.isRecording = false
      this.updateVoiceButtonState()
    }
  }

  private stopRecording(): void {
    if (!this.recognition || !this.isRecording) {
      console.log('stopRecording not recording')
      return
    }

    try {
      console.log('stopRecording recognition stop')
      this.recognition.stop()
      
      // Schedule line breaks after a short delay to allow for pending transcripts
      this.schedulePendingLineBreaks()
    } catch (error) {
      console.error('Failed to stop recording:', error)
    }
  }

  private updateVoiceButtonState(): void {
    if (!this.voiceButton) return

    if (this.isRecording) {
      this.voiceButton.classList.add('recording')
      this.voiceButton.innerHTML = '🔴'
      this.voiceButton.title = 'Recording... (release to stop)'
    } else {
      this.voiceButton.classList.remove('recording')
      this.voiceButton.innerHTML = '🎤'
      this.voiceButton.title = 'Maintenir clic souris, touch, ou barre espace pour parler'
    }
  }

  /**
   * Récupère la langue sélectionnée depuis les préférences globales
   */
  private getSelectedLanguage(): string {
    // Default to 'en-US' to match UI default
    return getPreference<string>('voiceLanguage', 'en-US') ?? 'en-US'
  }

  /**
   * Met à jour la langue de reconnaissance vocale
   */
  private updateRecognitionLanguage(): void {
    if (this.recognition) {
      const selectedLang = this.getSelectedLanguage()
      this.recognition.lang = selectedLang
      console.log('Language changed to:', selectedLang)
    }
  }

  /**
   * Améliore la ponctuation du texte transcrit
   */
  private improveTranscriptPunctuation(text: string): string {
    let improved = text

    // Ajoute un point à la fin si pas de ponctuation
    if (!/[.!?]$/.test(improved.trim())) {
      improved = improved.trim() + '.'
    }

    // Capitalise la première lettre
    improved = improved.charAt(0).toUpperCase() + improved.slice(1)

    // Nettoie les espaces multiples
    improved = improved.replace(/\s+/g, ' ')

    // Add a space at the end for natural separation
    improved = improved + ' '

    return improved
  }

  private appendToTextarea(text: string): void {
    const currentValue = this.chatTextarea.value
    const newValue = currentValue ? `${currentValue}${text}` : text
    this.chatTextarea.value = newValue
    this.chatTextarea.focus()

    // Move cursor to end
    const length = this.chatTextarea.value.length
    this.chatTextarea.setSelectionRange(length, length)
  }

  /**
   * Schedule line breaks to be added after a delay, allowing time for pending transcripts
   */
  private schedulePendingLineBreaks(): void {
    this.clearPendingLineBreaks() // Clear any existing timeout
    
    // Wait 500ms for any pending transcripts to arrive
    this.pendingLineBreaksTimeout = window.setTimeout(() => {
      if (this.sessionHadTranscript) {
        console.log('Adding line breaks after transcript session')
        this.appendToTextarea('\n\n')
        this.sessionHadTranscript = false // Reset for next session
      }
      this.pendingLineBreaksTimeout = null
    }, 500)
  }

  /**
   * Clear any pending line break timeouts
   */
  private clearPendingLineBreaks(): void {
    if (this.pendingLineBreaksTimeout) {
      clearTimeout(this.pendingLineBreaksTimeout)
      this.pendingLineBreaksTimeout = null
    }
  }
}
