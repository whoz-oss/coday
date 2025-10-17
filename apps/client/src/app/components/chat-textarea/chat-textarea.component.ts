import {
  AfterViewInit,
  Component,
  ElementRef,
  EventEmitter,
  inject,
  Input,
  OnChanges,
  OnDestroy,
  OnInit,
  Output,
  SimpleChanges,
  ViewChild,
} from '@angular/core'
import { FormsModule } from '@angular/forms'
import { Subscription } from 'rxjs'
import { PreferencesService } from '../../services/preferences.service'
import { CodayService } from '../../core/services/coday.service'
import { MatIconButton } from '@angular/material/button'
import { MatIcon } from '@angular/material/icon'

@Component({
  selector: 'app-chat-textarea',
  standalone: true,
  imports: [FormsModule, MatIconButton, MatIcon],
  templateUrl: './chat-textarea.component.html',
  styleUrl: './chat-textarea.component.scss',
})
export class ChatTextareaComponent implements OnInit, OnDestroy, AfterViewInit, OnChanges {
  // Constants for textarea sizing
  private static readonly MIN_TEXTAREA_LINES = 2
  private static readonly MAX_TEXTAREA_LINES = 15
  @Input() isDisabled: boolean = false
  @Input() showWelcome: boolean = false
  @Input() isThinking: boolean = false
  @Input() isStarting: boolean = false
  @Input() isSessionInitializing: boolean = false
  @Output() messageSubmitted = new EventEmitter<string>()
  @Output() voiceRecordingToggled = new EventEmitter<boolean>()
  @Output() heightChanged = new EventEmitter<number>()
  @Output() stopRequested = new EventEmitter<void>()

  @ViewChild('messageInput', { static: true }) messageInput!: ElementRef<HTMLTextAreaElement>

  message: string = ''
  isRecording: boolean = false
  isFocused: boolean = false

  // Voice recognition properties
  private recognition: any = null
  private sessionHadTranscript: boolean = false
  private pendingLineBreaksTimeout: number | null = null

  // Enter behavior preference
  private useEnterToSend: boolean = false

  // Invite properties (kept minimal for default value handling)
  currentInvite: string = ''

  // Subscriptions management
  private subscriptions: Subscription[] = []

  // Thinking animation
  private thinkingPhrases: string[] = ['Thinking...', 'Processing request...', 'Working on it...']
  currentThinkingPhrase: string = 'Thinking...'
  private thinkingPhraseIndex: number = 0
  private thinkingInterval: number | null = null

  // Modern Angular dependency injection
  private preferencesService = inject(PreferencesService)
  private codayService = inject(CodayService)

  ngOnInit(): void {
    this.initializeVoiceInput()

    // Initialize Enter key behavior preference
    this.useEnterToSend = this.preferencesService.getEnterToSend()

    // Listen to voice language changes
    this.subscriptions.push(this.preferencesService.voiceLanguage$.subscribe(() => this.updateRecognitionLanguage()))

    // Listen to Enter key behavior changes
    this.subscriptions.push(
      this.preferencesService.enterToSend$.subscribe((useEnterToSend) => {
        this.useEnterToSend = useEnterToSend
      })
    )

    // Subscribe to InviteEvent changes for default value handling
    this.subscribeToInviteEvents()

    // Subscribe to message restoration after deletion
    this.subscribeToMessageRestore()
  }

  ngAfterViewInit(): void {
    // Set initial height after view initialization
    this.adjustTextareaHeight()
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['isThinking']) {
      if (changes['isThinking'].currentValue) {
        this.startThinkingAnimation()
      } else {
        this.stopThinkingAnimation()
      }
    }

    // Restart animation when transitioning from Starting to normal thinking
    // Only restart if we're still at the first phrase (index 0)
    if (
      changes['isStarting'] &&
      !changes['isStarting'].currentValue &&
      changes['isStarting'].previousValue &&
      this.isThinking
    ) {
      if (this.thinkingPhraseIndex === 0) {
        console.log('[CHAT-TEXTAREA] Transitioning from Starting to Thinking at index 0 - restarting animation')
        this.stopThinkingAnimation()
        this.startThinkingAnimation()
      } else {
        console.log(
          '[CHAT-TEXTAREA] Transitioning from Starting to Thinking at index',
          this.thinkingPhraseIndex,
          '- continuing without restart'
        )
      }
    }
  }

  ngOnDestroy(): void {
    // Clean up all subscriptions
    this.subscriptions.forEach((sub) => sub.unsubscribe())
    this.subscriptions = []

    this.clearPendingLineBreaks()
    this.stopThinkingAnimation()
  }

  onKeyDown(event: KeyboardEvent) {
    if (event.key === 'Enter') {
      if (this.useEnterToSend) {
        // Mode: Enter to send, Shift+Enter for new line
        if (!event.shiftKey && !event.metaKey && !event.ctrlKey) {
          event.preventDefault()
          this.sendMessage()
        }
        // Shift+Enter: allow default behavior (new line)
      } else {
        // Mode: Cmd/Ctrl+Enter to send, Enter for new line
        const isMac = navigator.platform.toLowerCase().includes('mac')
        const correctModifier = isMac ? event.metaKey : event.ctrlKey

        if (correctModifier && !event.shiftKey) {
          event.preventDefault()
          this.sendMessage()
        }
        // Simple Enter: allow default behavior (new line)
      }
    }
  }

  onInput() {
    // Adjust textarea height when content changes
    this.adjustTextareaHeight()
  }

  sendMessage() {
    if (!this.isDisabled) {
      this.messageSubmitted.emit(this.message.trim())
      this.message = ''

      // Reset height after clearing message
      setTimeout(() => this.adjustTextareaHeight(), 0)
    }
  }

  onStopClick() {
    this.stopRequested.emit()
  }

  toggleVoiceRecording() {
    // This method is called by simple button click
    // But we now use push-to-talk mode with mousedown/mouseup events
    console.log('Toggle voice recording called - using push-to-talk mode instead')
  }

  // Methods for push-to-talk mode
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
    // Check if Speech Recognition is available
    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition
    console.log('[SPEECH] speechRecognition', SpeechRecognition)
    if (!SpeechRecognition) {
      console.warn('[SPEECH] Speech Recognition API not available')
      return
    }

    // Create recognition instance
    this.recognition = new SpeechRecognition()
    this.recognition.continuous = true // Allow long uninterrupted phrases
    this.recognition.interimResults = true
    this.recognition.maxAlternatives = 1
    this.recognition.lang = this.getSelectedLanguage()

    // Advanced parameters for long phrase capture
    try {
      if ('grammars' in this.recognition) {
        this.recognition.serviceURI = undefined // Force use of default service
      }
    } catch (e) {
      console.log('[SPEECH] Advanced speech recognition parameters not supported')
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
        // Post-processing for punctuation
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

    // Debug events
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
      this.sessionHadTranscript = false // Reset at beginning of new session
      this.clearPendingLineBreaks() // Clear pending timeouts from previous session
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

      // Schedule line breaks after short delay to allow pending transcripts
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

    // End sentences
    if (!/[.!?]$/.test(improved.trim())) {
      improved = improved.trim() + '.'
    }

    // Capitalize first letter
    improved = improved.charAt(0).toUpperCase() + improved.slice(1)

    // Remove multiple spaces
    improved = improved.replace(/\s+/g, ' ')

    // Add space at the end for natural separation
    improved = improved + ' '

    return improved
  }

  private appendToTextarea(text: string): void {
    const currentValue = this.message
    const newValue = currentValue ? `${currentValue}${text}` : text
    this.message = newValue

    // Focus textarea and place cursor at end
    if (this.messageInput?.nativeElement) {
      this.messageInput.nativeElement.focus()
      const length = this.message.length
      this.messageInput.nativeElement.setSelectionRange(length, length)
    }

    // Adjust height after programmatic content change
    setTimeout(() => this.adjustTextareaHeight(), 0)
  }

  private schedulePendingLineBreaks(): void {
    this.clearPendingLineBreaks() // Clear any existing timeout

    // Wait 500ms for pending transcriptions to arrive
    this.pendingLineBreaksTimeout = window.setTimeout(() => {
      if (this.sessionHadTranscript) {
        console.log('[SPEECH] Adding line breaks after transcript session')
        this.appendToTextarea('\n\n')
        this.sessionHadTranscript = false // Reset for next session
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
    const minHeight =
      lineHeight * ChatTextareaComponent.MIN_TEXTAREA_LINES +
      parseFloat(style.paddingTop) +
      parseFloat(style.paddingBottom)
    const maxHeight =
      lineHeight * ChatTextareaComponent.MAX_TEXTAREA_LINES +
      parseFloat(style.paddingTop) +
      parseFloat(style.paddingBottom)

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

    // Emit height change to parent
    const containerHeight = textarea.parentElement?.offsetHeight || newHeight + 32
    this.heightChanged.emit(containerHeight)
  }

  /**
   * Get current keyboard shortcut for sending message
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
   * Get send button tooltip
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

  /**
   * Get placeholder text for textarea
   * Uses invite text if available, otherwise shows default messages
   */
  getPlaceholder(): string {
    if (this.isSessionInitializing) {
      return 'Initializing session...'
    } else if (this.isThinking) {
      // Show "Starting..." for first message, then rotate phrases
      return this.isStarting ? 'Starting...' : this.currentThinkingPhrase
    } else if (this.showWelcome) {
      return 'How can I help you today?'
    } else if (this.currentInvite) {
      return 'Type your message here...'
    } else {
      return 'Type your prompt here'
    }
  }

  /**
   * Start the thinking phrase animation
   */
  private startThinkingAnimation(): void {
    this.thinkingPhraseIndex = 0
    this.currentThinkingPhrase = this.thinkingPhrases[0] || 'Thinking...'

    this.thinkingInterval = window.setInterval(() => {
      // Move to next phrase only if not at the last one
      if (this.thinkingPhraseIndex < this.thinkingPhrases.length - 1) {
        this.thinkingPhraseIndex++
        this.currentThinkingPhrase = this.thinkingPhrases[this.thinkingPhraseIndex] || 'Thinking...'
      }
      // If we're at the last phrase, stay there (no change)
    }, 2000) // Change phrase every 2 seconds
  }

  /**
   * Stop the thinking phrase animation
   */
  private stopThinkingAnimation(): void {
    if (this.thinkingInterval) {
      clearInterval(this.thinkingInterval)
      this.thinkingInterval = null
    }
    this.thinkingPhraseIndex = 0
    this.currentThinkingPhrase = 'Thinking...'
  }

  /**
   * Subscribe to InviteEvent changes in CodayService
   */
  private subscribeToInviteEvents(): void {
    this.subscriptions.push(
      this.codayService.currentInviteEvent$.subscribe((inviteEvent) => {
        if (inviteEvent) {
          this.handleInviteEvent(inviteEvent.invite, inviteEvent.defaultValue)
        } else {
          this.currentInvite = ''
        }
      })
    )
  }

  /**
   * Handle an InviteEvent
   */
  private handleInviteEvent(invite: string, defaultValue?: string): void {
    this.currentInvite = invite

    // Set default value if provided
    if (defaultValue) {
      this.message = defaultValue
      setTimeout(() => this.adjustTextareaHeight(), 0)
    }

    // Focus on textarea
    if (this.messageInput?.nativeElement) {
      this.messageInput.nativeElement.focus()
    }
  }

  /**
   * Subscribe to message restoration after deletion
   */
  private subscribeToMessageRestore(): void {
    this.subscriptions.push(
      this.codayService.messageToRestore$.subscribe((content) => {
        if (content.trim()) {
          console.log('[CHAT-TEXTAREA] Restoring message content:', content.substring(0, 50) + '...')
          this.restoreMessageContent(content)
        }
      })
    )
  }

  /**
   * Restore deleted message content to textarea
   * @param content The text content to restore
   */
  private restoreMessageContent(content: string): void {
    // Set the content in the textarea
    this.message = content

    // Focus the textarea and place cursor at the end
    if (this.messageInput?.nativeElement) {
      this.messageInput.nativeElement.focus()

      // Use setTimeout to ensure the value is set before positioning cursor
      setTimeout(() => {
        const textarea = this.messageInput.nativeElement
        const length = this.message.length
        textarea.setSelectionRange(length, length)
      }, 0)
    }

    // Adjust textarea height to fit the restored content
    setTimeout(() => this.adjustTextareaHeight(), 10)
  }
}
