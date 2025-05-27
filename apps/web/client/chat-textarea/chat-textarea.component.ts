import { CodayEvent, InviteEvent } from '@coday/coday-events'
import { CodayEventHandler } from '../utils/coday-event-handler'
import { getPreference } from '../utils/preferences'

export class ChatTextareaComponent implements CodayEventHandler {
  private chatForm: HTMLFormElement
  private chatTextarea: HTMLTextAreaElement
  private chatLabel: HTMLLabelElement
  private submitButton: HTMLButtonElement
  private inviteEvent: InviteEvent | undefined
  private readonly os: 'mac' | 'non-mac'

  // Command history for arrow key navigation
  private promptHistory: string[] = []
  private historyIndex: number = -1
  private tempInput: string = ''

  // Voice input properties
  private recognition: any | null = null
  private voiceButton: HTMLButtonElement | null = null
  private isRecording: boolean = false

  /**
   * Detect operating system for keyboard shortcuts
   */
  private detectOS(): 'mac' | 'non-mac' {
    return navigator.platform.toLowerCase().includes('mac') ? 'mac' : 'non-mac'
  }

  /**
   * Update the send button label based on preferences and OS
   */
  private updateSendButtonLabel(): void {
    const useEnterToSend = getPreference<boolean>('useEnterToSend', false)

    if (!this.submitButton) return

    if (useEnterToSend) {
      this.submitButton.innerHTML = 'SEND <br/><br/>enter'
    } else {
      if (this.os === 'mac') {
        this.submitButton.innerHTML = 'SEND <br/><br/>⌘ + enter'
      } else {
        this.submitButton.innerHTML = 'SEND <br/><br/>Ctrl + enter'
      }
    }
  }

  constructor(private postEvent: (event: CodayEvent) => Promise<Response>) {
    // Detect OS once during initialization
    this.os = this.detectOS()

    this.chatForm = document.getElementById('chat-form') as HTMLFormElement
    this.chatTextarea = document.getElementById('chat-input') as HTMLTextAreaElement
    this.chatLabel = document.getElementById('chat-label') as HTMLLabelElement
    this.submitButton = document.getElementById('send-button') as HTMLButtonElement

    this.chatForm.onsubmit = async (event) => {
      event.preventDefault()
      await this.submit()
    }

    this.chatTextarea.addEventListener('keydown', async (e) => {
      // Disable history navigation if a default value is set
      if (this.inviteEvent?.defaultValue) return

      const useEnterToSend = getPreference<boolean>('useEnterToSend', false)

      // Handle Enter key for submission
      if (useEnterToSend) {
        // When Enter to Send is enabled, only handle plain Enter (without Shift)
        if (e.key === 'Enter' && !e.shiftKey && !e.metaKey && !e.ctrlKey) {
          e.preventDefault()
          await this.submit()
        }
      } else {
        // When Enter to Send is disabled, use OS-specific shortcuts
        if (e.key === 'Enter' && ((this.os === 'mac' && e.metaKey) || (this.os === 'non-mac' && e.ctrlKey))) {
          e.preventDefault()
          await this.submit()
        }
      }

      // Handle Up/Down arrow keys for history navigation
      if (e.key === 'ArrowUp') {
        // Only navigate history if cursor is at the first line
        if (this.isCursorAtFirstLine()) {
          e.preventDefault()
          this.navigateHistory('up')
        }
      } else if (e.key === 'ArrowDown') {
        // Only navigate history if cursor is at the last line
        if (this.isCursorAtLastLine()) {
          e.preventDefault()
          this.navigateHistory('down')
        }
      }
    })

    // Update the button label initially
    this.updateSendButtonLabel()

    // Initialize voice input
    this.initializeVoiceInput()

    // Listen for preference changes
    window.addEventListener('storage', (event) => {
      if (event.key === 'coday-preferences') {
        this.updateSendButtonLabel()
      }
    })

    // Listen for voice language changes from options panel
    window.addEventListener('voiceLanguageChanged', (event: any) => {
      this.updateRecognitionLanguage()
    })
  }

  handle(event: CodayEvent): void {
    if (event instanceof InviteEvent) {
      this.inviteEvent = event
      // Parse markdown for the chat label
      const parsed = marked.parse(this.inviteEvent.invite)
      if (parsed instanceof Promise) {
        parsed.then((html) => (this.chatLabel.innerHTML = html))
      } else {
        this.chatLabel.innerHTML = parsed
      }
      this.chatForm.style.display = 'block'
      this.chatTextarea.focus()
      if (this.inviteEvent.defaultValue) {
        console.log(`handling defaultValue: ${this.inviteEvent.defaultValue}`)
        this.chatTextarea.value = this.inviteEvent.defaultValue
      }
    }
  }

  /**
   * Check if cursor is at the first line of text
   */
  private isCursorAtFirstLine(): boolean {
    const text = this.chatTextarea.value
    const cursorPos = this.chatTextarea.selectionStart

    // If cursor is at position 0, it's definitely at the first line
    if (cursorPos === 0) return true

    // Otherwise, check if there are any newline characters before the cursor
    const textBeforeCursor = text.substring(0, cursorPos)
    return textBeforeCursor.indexOf('\n') === -1
  }

  /**
   * Check if cursor is at the last line of text
   */
  private isCursorAtLastLine(): boolean {
    const text = this.chatTextarea.value
    const cursorPos = this.chatTextarea.selectionStart

    // If cursor is at the end, it's definitely at the last line
    if (cursorPos === text.length) return true

    // Otherwise, check if there are any newline characters after the cursor
    const textAfterCursor = text.substring(cursorPos)
    return textAfterCursor.indexOf('\n') === -1
  }

  /**
   * Navigate through command history using up/down arrows
   */
  private navigateHistory(direction: 'up' | 'down'): void {
    // If history is empty, do nothing
    if (this.promptHistory.length === 0) return

    // On first up arrow press, save current input
    if (direction === 'up' && this.historyIndex === -1) {
      this.tempInput = this.chatTextarea.value
    }

    if (direction === 'up' && this.historyIndex < this.promptHistory.length - 1) {
      // Move up in history
      this.historyIndex++
      this.chatTextarea.value = this.promptHistory[this.promptHistory.length - 1 - this.historyIndex]
      this.moveCursorToEnd()
    } else if (direction === 'down' && this.historyIndex > -1) {
      // Move down in history
      this.historyIndex--

      if (this.historyIndex === -1) {
        // We've reached the end of history, restore the temporary input
        this.chatTextarea.value = this.tempInput
      } else {
        this.chatTextarea.value = this.promptHistory[this.promptHistory.length - 1 - this.historyIndex]
      }
      this.moveCursorToEnd()
    }
  }

  /**
   * Move cursor to the end of the textarea
   */
  private moveCursorToEnd(): void {
    const length = this.chatTextarea.value.length
    this.chatTextarea.setSelectionRange(length, length)
  }

  private async submit(): Promise<void> {
    const inputValue = this.chatTextarea.value.trim()
    const answer = this.inviteEvent?.buildAnswer(inputValue)
    this.chatTextarea.value = ''

    if (!answer) {
      return
    }

    try {
      this.chatForm.style.display = 'none'
      const response = await this.postEvent(answer)

      if (response.ok) {
        // Add to history if not empty and not a duplicate of the last entry
        if (
          inputValue &&
          (this.promptHistory.length === 0 || this.promptHistory[this.promptHistory.length - 1] !== inputValue)
        ) {
          this.promptHistory.push(inputValue)
        }

        this.historyIndex = -1 // Reset history index
        this.tempInput = ''
      } else {
        this.chatForm.style.display = 'block'
        this.chatTextarea.focus()
        console.error('Failed to send message.')
      }
    } catch (error) {
      console.error('Error occurred while sending message:', error)
    }
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
        this.appendToTextarea(processedTranscript)
      }
    }

    this.recognition.onend = () => {
      console.log('Recognition ended. Was recording:', this.isRecording)
      this.isRecording = false
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
      console.log('Speech ended - this might cause automatic stop')
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
      this.appendToTextarea('\n\n') // add paragraph separation
      return
    }

    try {
      this.recognition.stop()
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

    // Remplace certains mots-clés par de la ponctuation (optionnel)
    improved = improved.replace(/\bpoint\b/gi, '.')
    improved = improved.replace(/\bvirgule\b/gi, ',')
    improved = improved.replace(/\bpoint d'interrogation\b/gi, '?')
    improved = improved.replace(/\bpoint d'exclamation\b/gi, '!')
    improved = improved.replace(/\bdeux points\b/gi, ':')
    improved = improved.replace(/\bpoint virgule\b/gi, ';')

    // Nettoie les espaces multiples
    improved = improved.replace(/\s+/g, ' ')

    return improved
  }

  private appendToTextarea(text: string): void {
    const currentValue = this.chatTextarea.value
    const newValue = currentValue ? `${currentValue} ${text}` : text
    this.chatTextarea.value = newValue
    this.chatTextarea.focus()

    // Move cursor to end
    const length = this.chatTextarea.value.length
    this.chatTextarea.setSelectionRange(length, length)
  }
}
