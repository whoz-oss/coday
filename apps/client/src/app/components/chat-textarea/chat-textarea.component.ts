import {
  AfterViewInit,
  Component,
  ElementRef,
  EventEmitter,
  HostListener,
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
import { AgentApiService, AgentAutocomplete } from '../../core/services/agent-api.service'
import { PromptApiService, PromptAutocomplete } from '../../core/services/prompt-api.service'
import { ProjectStateService } from '../../core/services/project-state.service'
import { HighlightPipe } from '../../pipes/highlight.pipe'

@Component({
  selector: 'app-chat-textarea',
  standalone: true,
  imports: [FormsModule, MatIconButton, MatIcon, HighlightPipe],
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

  // Internal state for stopping
  isStopping: boolean = false
  // Local flag to immediately disable textarea when message is sent
  isLocallyDisabled: boolean = false
  @Output() filesPasted = new EventEmitter<File[]>()
  @Output() messageSubmitted = new EventEmitter<string>()
  @Output() voiceRecordingToggled = new EventEmitter<boolean>()
  @Output() heightChanged = new EventEmitter<number>()
  @Output() stopRequested = new EventEmitter<void>()

  @ViewChild('messageInput', { static: true }) messageInput!: ElementRef<HTMLTextAreaElement>
  @ViewChild('fileInput') fileInput?: ElementRef<HTMLInputElement>

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
  private readonly thinkingPhrases: string[] = ['Processing request...', 'Thinking...', 'Working on it...']
  currentThinkingPhrase: string = 'Processing request...'
  private thinkingPhraseIndex: number = 0
  private thinkingInterval: number | null = null

  // Autocomplete state
  autocompleteVisible = false
  autocompleteTrigger: '@' | '/' | null = null
  autocompleteItems: Array<{ name: string; description: string }> = []
  selectedAutocompleteIndex = 0

  // Modern Angular dependency injection
  private readonly preferencesService = inject(PreferencesService)
  private readonly codayService = inject(CodayService)
  private readonly agentApiService = inject(AgentApiService)
  private readonly promptApiService = inject(PromptApiService)
  private readonly projectStateService = inject(ProjectStateService)

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

    // Autofocus textarea on initial load if not disabled
    // Use requestAnimationFrame to ensure DOM is ready
    requestAnimationFrame(() => {
      setTimeout(() => this.focusTextarea(), 200)
    })
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['isThinking']) {
      if (changes['isThinking'].currentValue) {
        this.startThinkingAnimation()
      } else {
        this.stopThinkingAnimation()
        // Reset stopping state when thinking ends
        this.isStopping = false
        // Reset local disable flag when thinking ends
        this.isLocallyDisabled = false
        // Autofocus textarea when thinking mode ends
        // Use requestAnimationFrame + timeout to ensure disabled attribute is removed
        if (changes['isThinking'].previousValue) {
          console.log('[CHAT-TEXTAREA] Thinking ended, scheduling focus')
          requestAnimationFrame(() => {
            setTimeout(() => this.focusTextarea(), 100)
          })
        }
      }
    }

    // Autofocus when session initialization completes
    if (changes['isSessionInitializing']) {
      if (!changes['isSessionInitializing'].currentValue && changes['isSessionInitializing'].previousValue) {
        console.log('[CHAT-TEXTAREA] Session initialization completed, scheduling focus')
        // Reset local disable flag when session initialization ends
        this.isLocallyDisabled = false
        requestAnimationFrame(() => {
          setTimeout(() => this.focusTextarea(), 100)
        })
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
    // Handle autocomplete navigation if visible
    if (this.autocompleteVisible) {
      switch (event.key) {
        case 'ArrowDown':
          event.preventDefault()
          this.selectedAutocompleteIndex = Math.min(
            this.selectedAutocompleteIndex + 1,
            this.autocompleteItems.length - 1
          )
          this.scrollSelectedItemIntoView()
          return

        case 'ArrowUp':
          event.preventDefault()
          this.selectedAutocompleteIndex = Math.max(this.selectedAutocompleteIndex - 1, 0)
          this.scrollSelectedItemIntoView()
          return

        case 'Tab':
        case 'Enter':
          // If we haven't typed a space after the trigger yet, complete the item
          if (!this.message.includes(' ')) {
            event.preventDefault()
            this.selectAutocompleteItem()
            return
          }
          // Otherwise, let Enter send the message normally (fall through)
          break

        case 'Escape':
          event.preventDefault()
          this.hideAutocomplete()
          return
      }
    }

    // Normal Enter key handling for sending messages
    if (event.key === 'Enter') {
      if (this.useEnterToSend) {
        // Mode: Enter to send, Shift+Enter for new line
        if (!event.shiftKey && !event.metaKey && !event.ctrlKey) {
          event.preventDefault()
          // Allow sending via keyboard even with empty message
          this.sendMessage(true)
        }
        // Shift+Enter: allow default behavior (new line)
      } else {
        // Mode: Cmd/Ctrl+Enter to send, Enter for new line
        const isMac = navigator.platform.toLowerCase().includes('mac')
        const correctModifier = isMac ? event.metaKey : event.ctrlKey

        if (correctModifier && !event.shiftKey) {
          event.preventDefault()
          // Allow sending via keyboard even with empty message
          this.sendMessage(true)
        }
        // Simple Enter: allow default behavior (new line)
      }
    }
  }

  onInput() {
    // Adjust textarea height when content changes
    this.adjustTextareaHeight()

    // Check for autocomplete triggers
    this.checkForAutocomplete()
  }

  @HostListener('paste', ['$event'])
  onPaste(event: ClipboardEvent): void {
    const items = event.clipboardData?.items
    if (!items) {
      return
    }

    const fileItems = Array.from(items).filter((item) => item.kind === 'file')
    if (!fileItems.length) {
      return
    }

    event.preventDefault()

    const files = fileItems.reduce<File[]>((acc, item) => {
      const file = item.getAsFile()
      return file ? [...acc, file] : acc
    }, [])

    if (files.length) {
      this.filesPasted.emit(files)
    }
  }

  /**
   * Trigger the hidden file input click
   */
  triggerFileUpload(): void {
    console.log('[CHAT-TEXTAREA] Triggering file upload')
    this.fileInput?.nativeElement?.click()
  }

  /**
   * Handle file input change event
   */
  onFileInputChange(event: Event): void {
    const input = event.target as HTMLInputElement
    const files = input.files

    if (!files || files.length === 0) {
      console.log('[CHAT-TEXTAREA] No files selected')
      return
    }

    console.log('[CHAT-TEXTAREA] Files selected:', files.length)
    const fileArray = Array.from(files)
    this.filesPasted.emit(fileArray)

    // Reset the input so the same file can be selected again
    input.value = ''
  }

  /**
   * Handle clicks outside the autocomplete popup to close it
   */
  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.autocompleteVisible) return

    const target = event.target as HTMLElement
    const popup = document.querySelector('.autocomplete-popup')
    const textarea = this.messageInput?.nativeElement

    // Close if click is outside both popup and textarea
    if (popup && !popup.contains(target) && textarea && !textarea.contains(target)) {
      this.hideAutocomplete()
    }
  }

  sendMessage(allowEmpty: boolean = false) {
    // Allow sending empty messages only via keyboard shortcut (allowEmpty=true)
    // Button click always requires non-empty message (allowEmpty=false, default)
    const messageToSend = this.message.trim()
    const canSend = !this.isDisabled && !this.isLocallyDisabled && (allowEmpty || messageToSend)

    if (canSend) {
      // Immediately set local disable flag to prevent any further input
      this.isLocallyDisabled = true

      // Send the trimmed message (can be empty string if allowEmpty=true)
      this.messageSubmitted.emit(messageToSend)
      this.message = ''

      // Reset height after clearing message
      setTimeout(() => this.adjustTextareaHeight(), 0)
    }
  }

  onStopClick() {
    this.isStopping = true
    this.stopRequested.emit()
  }

  toggleRecording(): void {
    if (this.isRecording) {
      this.stopRecording()
    } else {
      this.startRecording()
    }
  }

  private initializeVoiceInput(): void {
    // Check if Speech Recognition is available
    const SpeechRecognition = (window as any).SpeechRecognition ?? (window as any).webkitSpeechRecognition
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
    this.message = currentValue ? `${currentValue}${text}` : text

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
    const containerHeight = textarea.parentElement?.offsetHeight ?? newHeight + 32
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
      // Show "Stopping..." if stop button was clicked
      if (this.isStopping) {
        return 'Stopping...'
      }
      // Show "Starting..." for first message, then rotate phrases
      return this.isStarting ? 'Processing request...' : this.currentThinkingPhrase
    } else if (this.showWelcome) {
      return 'How can I help you today?'
    } else {
      return `Type '@' for agents, '/' for commands, or just your message...`
    }
  }

  /**
   * Start the thinking phrase animation
   */
  private startThinkingAnimation(): void {
    this.thinkingPhraseIndex = 0
    this.currentThinkingPhrase = this.thinkingPhrases[0] ?? 'Thinking...'

    this.thinkingInterval = window.setInterval(() => {
      // Move to next phrase only if not at the last one
      if (this.thinkingPhraseIndex < this.thinkingPhrases.length - 1) {
        this.thinkingPhraseIndex++
        this.currentThinkingPhrase = this.thinkingPhrases[this.thinkingPhraseIndex] ?? 'Thinking...'
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

    // Reset local disable flag immediately when an invite arrives
    // This allows the user to respond without waiting for thinking state to clear
    this.isLocallyDisabled = false

    // Set default value if provided
    if (defaultValue) {
      this.message = defaultValue
      setTimeout(() => this.adjustTextareaHeight(), 0)
    }

    // Focus on textarea after a short delay to ensure disabled attribute is removed
    setTimeout(() => {
      if (this.messageInput?.nativeElement) {
        this.messageInput.nativeElement.focus()
      }
    }, 50)
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

  /**
   * Focus the textarea
   */
  private focusTextarea(): void {
    const element = this.messageInput?.nativeElement
    console.log('[CHAT-TEXTAREA] focusTextarea called', {
      hasElement: !!element,
      isDisabled: this.isDisabled,
      isThinking: this.isThinking,
      elementDisabled: element?.disabled,
      isSessionInitializing: this.isSessionInitializing,
    })

    if (element && !element.disabled) {
      element.focus()
      console.log('[CHAT-TEXTAREA] Textarea focused successfully')
    } else {
      console.log('[CHAT-TEXTAREA] Focus blocked - element disabled or not available')
    }
  }

  /**
   * Check if the message starts with @ and show autocomplete
   * Note: Commands with / are disabled for now
   */
  private checkForAutocomplete(): void {
    // Don't trim here - we need to detect trailing spaces
    const text = this.message

    // Check if starts with @ (agents)
    if (text.startsWith('@')) {
      const afterTrigger = text.substring(1)
      // If there's a space after @, close the autocomplete
      if (afterTrigger.includes(' ')) {
        this.hideAutocomplete()
        return
      }
      this.showAgentAutocomplete(afterTrigger)
      return
    }

    // Check if starts with / (prompts/commands)
    if (text.startsWith('/')) {
      const afterTrigger = text.substring(1)
      // If there's a space after /, close the autocomplete
      if (afterTrigger.includes(' ')) {
        this.hideAutocomplete()
        return
      }
      this.showPromptAutocomplete(afterTrigger)
      return
    }

    // No trigger found, hide autocomplete
    this.hideAutocomplete()
  }

  /**
   * Show agent autocomplete filtered by query
   * Filtering is done client-side from cached agent list
   */
  private showAgentAutocomplete(query: string): void {
    this.autocompleteTrigger = '@'

    try {
      // Get current project name
      const projectName = this.projectStateService.getSelectedProjectIdOrThrow()

      // Get filtered agents (uses cache if available)
      this.agentApiService.getAgentsAutocomplete(projectName, query).subscribe({
        next: (agents: AgentAutocomplete[]) => {
          this.autocompleteItems = agents
          this.autocompleteVisible = agents.length > 0
          this.selectedAutocompleteIndex = 0
        },
        error: (error) => {
          console.error('[AUTOCOMPLETE] Error loading agents:', error)
          this.hideAutocomplete()
        },
      })
    } catch (error) {
      console.warn('[AUTOCOMPLETE] No project selected, cannot load agents')
      this.hideAutocomplete()
    }
  }

  /**
   * Show prompt autocomplete filtered by query
   * Filtering is done client-side from API response
   */
  private showPromptAutocomplete(query: string): void {
    this.autocompleteTrigger = '/'

    try {
      // Get current project name
      const projectName = this.projectStateService.getSelectedProjectIdOrThrow()

      // Get filtered prompts
      this.promptApiService.getPromptsAutocomplete(projectName, query).subscribe({
        next: (prompts: PromptAutocomplete[]) => {
          this.autocompleteItems = prompts
          this.autocompleteVisible = prompts.length > 0
          this.selectedAutocompleteIndex = 0
        },
        error: (error) => {
          console.error('[AUTOCOMPLETE] Error loading prompts:', error)
          this.hideAutocomplete()
        },
      })
    } catch (error) {
      console.warn('[AUTOCOMPLETE] No project selected, cannot load prompts')
      this.hideAutocomplete()
    }
  }

  /**
   * Hide the autocomplete popup
   */
  private hideAutocomplete(): void {
    this.autocompleteVisible = false
    this.autocompleteItems = []
    this.selectedAutocompleteIndex = 0
  }

  /**
   * Select the currently highlighted autocomplete item
   */
  private selectAutocompleteItem(): void {
    const item = this.autocompleteItems[this.selectedAutocompleteIndex]
    if (!item) return

    let cursorPosition: number | null = null

    // Build the completed text based on trigger type
    if (this.autocompleteTrigger === '/') {
      // For slash commands (prompts), append parameter format if available
      const promptItem = item as any // Cast to access parameterFormat
      const paramFormat = promptItem.parameterFormat
      console.log(promptItem)

      if (paramFormat === undefined) {
        // No parameters: just command name
        this.message = `/${item.name}`
      } else if (paramFormat === '') {
        // Trailing parameter: add space for user to type
        this.message = `/${item.name} `
      } else {
        // Structured parameters: add format with placeholders
        this.message = `/${item.name} ${paramFormat}`
        // Position cursor inside first empty quotes (key1="HERE")
        const firstQuotePos = this.message.indexOf('=""')
        if (firstQuotePos !== -1) {
          cursorPosition = firstQuotePos + 2 // Position between the quotes
        }
      }
    } else {
      // For @ (agents), just add name + space
      this.message = `@${item.name} `
    }

    this.hideAutocomplete()

    // Keep focus on textarea and position cursor
    setTimeout(() => {
      if (this.messageInput?.nativeElement) {
        this.messageInput.nativeElement.focus()

        if (cursorPosition !== null) {
          // Position cursor at specific location (inside first quotes)
          this.messageInput.nativeElement.setSelectionRange(cursorPosition, cursorPosition)
        } else {
          // Place cursor at end (default behavior)
          const length = this.message.length
          this.messageInput.nativeElement.setSelectionRange(length, length)
        }
      }
    }, 0)
  }

  /**
   * Select an autocomplete item by clicking on it
   */
  selectAutocompleteItemByClick(index: number): void {
    this.selectedAutocompleteIndex = index
    this.selectAutocompleteItem()
  }

  /**
   * Get the current query being typed (for display in header)
   */
  getAutocompleteQuery(): string {
    return this.message.substring(1).split(' ')[0] ?? ''
  }

  /**
   * Get parameter format for a prompt item (slash commands only)
   */
  getParameterFormat(item: any): string | undefined {
    return item.parameterFormat
  }

  /**
   * Check if item has parameter format defined
   */
  hasParameterFormat(item: any): boolean {
    return this.autocompleteTrigger === '/' && item.parameterFormat !== undefined
  }

  /**
   * Scroll the selected autocomplete item into view
   * Called after keyboard navigation to ensure selected item is visible
   */
  private scrollSelectedItemIntoView(): void {
    // Use setTimeout to ensure DOM is updated before scrolling
    setTimeout(() => {
      const popup = document.querySelector('.autocomplete-popup')
      if (!popup) return

      const selectedItem = popup.querySelector('.autocomplete-item.selected')
      if (!selectedItem) return

      // Scroll the selected item into view within the popup
      selectedItem.scrollIntoView({
        behavior: 'smooth',
        block: 'nearest',
        inline: 'nearest',
      })
    }, 0)
  }
}
