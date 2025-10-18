import { AfterViewInit, Component, ElementRef, HostListener, inject, OnDestroy, OnInit, ViewChild } from '@angular/core'
import { ActivatedRoute, Router } from '@angular/router'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { animate, style, transition, trigger } from '@angular/animations'

import { ChatHistoryComponent } from '../chat-history/chat-history.component'
import { ChatMessage } from '../chat-message/chat-message.component'
import { ChatTextareaComponent } from '../chat-textarea/chat-textarea.component'
import { ChoiceOption, ChoiceSelectComponent } from '../choice-select/choice-select.component'

import { CodayService } from '../../core/services/coday.service'
import { ConnectionStatus } from '../../core/services/event-stream.service'
// import { SessionStateService } from '../../core/services/session-state.service' // Disabled for new architecture
// ProjectStateService and ThreadStateService managed by route guards
import { ImageUploadService } from '../../services/image-upload.service'
import { TabTitleService } from '../../services/tab-title.service'
import { PreferencesService } from '../../services/preferences.service'

@Component({
  selector: 'app-main',
  standalone: true,
  imports: [ChatHistoryComponent, ChatTextareaComponent, ChoiceSelectComponent],
  templateUrl: './main-app.component.html',
  styleUrl: './main-app.component.scss',
  animations: [
    trigger('slideIn', [
      transition(':enter', [
        style({ transform: 'translateY(100%)', opacity: 0 }),
        animate('300ms ease-out', style({ transform: 'translateY(0)', opacity: 1 })),
      ]),
      transition(':leave', [animate('200ms ease-in', style({ transform: 'translateY(100%)', opacity: 0 }))]),
    ]),
  ],
})
export class MainAppComponent implements OnInit, OnDestroy, AfterViewInit {
  private destroy$ = new Subject<void>()

  @ViewChild('inputSection') inputSection!: ElementRef<HTMLElement>

  // State from services
  messages: ChatMessage[] = []
  isThinking: boolean = false
  isStartingFirstMessage: boolean = false
  currentChoice: { options: ChoiceOption[]; label: string } | null = null
  connectionStatus: ConnectionStatus | null = null
  isConnected: boolean = false
  isSessionInitializing: boolean = true
  userHasSentMessage: boolean = false
  showConnectionStatus: boolean = false
  private hasEverConnected: boolean = false

  // Input height management
  inputSectionHeight: number = 80 // Default height

  // Upload status
  uploadStatus: { message: string; isError: boolean } = { message: '', isError: false }

  // Drag and drop state
  isDragOver: boolean = false

  // Welcome message rotation
  welcomeMessages = [
    'Welcome to Coday', // English
    'Bienvenue sur Coday', // French
    'Bienvenido a Coday', // Spanish
    'Willkommen bei Coday', // German
    'Benvenuto su Coday', // Italian
    'Bem-vindo ao Coday', // Portuguese
    'Welkom bij Coday', // Dutch
    'Добро пожаловать в Coday', // Russian
    'Coday へようこそ', // Japanese
    '欢迎来到 Coday', // Chinese
    'Coday 에 오신 것을 환영합니다', // Korean
    'مرحبًا بك في Coday', // Arabic
  ]
  currentWelcomeIndex = 0
  currentWelcomeMessage = this.welcomeMessages[0]
  private welcomeRotationInterval?: number

  // Modern Angular dependency injection
  private codayService = inject(CodayService)
  // NOTE: SessionStateService disabled for new thread-based architecture
  // NOTE: ProjectStateService and ThreadStateService are now managed by route guards
  private imageUploadService = inject(ImageUploadService)
  private titleService = inject(TabTitleService) // Renamed to avoid conflicts
  private preferencesService = inject(PreferencesService)
  private route = inject(ActivatedRoute)
  private router = inject(Router)

  constructor() {
    console.log('[MAIN-APP] Using new thread-based architecture (no clientId needed)')
  }

  ngOnInit(): void {
    // Get project and thread from route params
    const projectName = this.route.snapshot.params['projectName']
    const threadId = this.route.snapshot.params['threadId']
    
    console.log('[MAIN-APP] Initializing with project:', projectName, 'thread:', threadId)

    // Note: projectStateGuard and threadStateGuard have already selected
    // the project and thread before this component loads

    // Check if we have a first message from router state (implicit thread creation)
    const navigation = this.router.getCurrentNavigation()
    const firstMessage = navigation?.extras?.state?.['firstMessage'] as string | undefined

    // Setup print event listeners
    this.setupPrintHandlers()

    // Start welcome message rotation
    this.startWelcomeRotation()

    this.codayService.messages$.pipe(takeUntil(this.destroy$)).subscribe((messages) => {
      console.log('[MAIN-APP] Messages updated:', messages.length)
      this.messages = messages

      // If we have messages (e.g., from thread replay), hide welcome message
      if (messages.length > 0 && messages.some((message) => message.role === 'user') && !this.userHasSentMessage) {
        console.log('[MAIN-APP] Messages loaded from thread, hiding welcome message')
        this.userHasSentMessage = true
        this.stopWelcomeRotation()
      }

      // If messages are cleared (e.g., project change), reset welcome state
      if (messages.length === 0 && this.userHasSentMessage) {
        console.log('[MAIN-APP] Messages cleared, showing welcome message again')
        this.userHasSentMessage = false
        this.startWelcomeRotation()
      }
    })

    this.codayService.isThinking$.pipe(takeUntil(this.destroy$)).subscribe((isThinking) => {
      this.isThinking = isThinking
      // When backend thinking starts, clear the "Starting..." state
      if (isThinking) {
        this.isStartingFirstMessage = false
      }
    })

    this.codayService.currentChoice$.pipe(takeUntil(this.destroy$)).subscribe((choice) => {
      this.currentChoice = choice
    })

    // Session initialization disabled for new architecture
    // State is managed by ProjectStateService and ThreadStateService
    this.isSessionInitializing = false

    this.codayService.connectionStatus$.pipe(takeUntil(this.destroy$)).subscribe((status) => {
      this.connectionStatus = status
      this.isConnected = status.connected

      // Track if we've ever successfully connected
      if (status.connected && !this.hasEverConnected) {
        this.hasEverConnected = true
        // Once connected, allow showing disconnection messages immediately
        this.showConnectionStatus = true
      }

      // If not connected and we've never connected before,
      // wait a bit before showing the status (to avoid flash on startup)
      if (!status.connected && !this.hasEverConnected) {
        setTimeout(() => {
          // Only show if still not connected after delay
          if (!this.isConnected && !this.hasEverConnected) {
            this.showConnectionStatus = true
          }
        }, 2000) // 2 second delay before showing initial connection issues
      } else if (!status.connected && this.hasEverConnected) {
        // If we were connected and lost connection, show immediately
        this.showConnectionStatus = true
      } else if (status.connected) {
        // Hide the status message when connected
        this.showConnectionStatus = true // Keep true so it can show if disconnected later
      }
    })

    // Connect services (to avoid circular dependency)
    this.codayService.setTabTitleService(this.titleService)

    // Connect to the thread's event stream
    this.codayService.connectToThread(projectName, threadId)

    // If we have a first message (from implicit thread creation), send it
    if (firstMessage) {
      console.log('[MAIN-APP] Sending first message from implicit creation')
      // Wait a bit for the connection to be established
      setTimeout(() => {
        this.codayService.sendMessage(firstMessage)
      }, 500)
    }
  }

  ngAfterViewInit(): void {
    // Initial height measurement
    setTimeout(() => this.updateInputSectionHeight(), 100)
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()

    // Cleanup print handlers
    window.removeEventListener('beforeprint', this.handleBeforePrint)
    window.removeEventListener('afterprint', this.handleAfterPrint)

    // Cleanup welcome rotation
    this.stopWelcomeRotation()
  }

  private startWelcomeRotation(): void {
    // Only start if user hasn't sent a message yet
    if (!this.userHasSentMessage) {
      this.welcomeRotationInterval = window.setInterval(() => {
        this.currentWelcomeIndex = (this.currentWelcomeIndex + 1) % this.welcomeMessages.length
        this.currentWelcomeMessage = this.welcomeMessages[this.currentWelcomeIndex]
      }, 3000)
    }
  }

  private stopWelcomeRotation(): void {
    if (this.welcomeRotationInterval) {
      clearInterval(this.welcomeRotationInterval)
      this.welcomeRotationInterval = undefined
    }
  }

  onMessageSubmitted(message: string): void {
    console.log('[MAIN-APP] Sending message:', message)

    // Mark that user has sent their first message
    if (!this.userHasSentMessage) {
      this.userHasSentMessage = true
      this.stopWelcomeRotation()
      this.isStartingFirstMessage = true
    }

    // Immediately put textarea in thinking mode for every message
    // The backend ThinkingEvent will take over when it arrives
    this.isThinking = true

    this.codayService.sendMessage(message)
  }

  onVoiceToggled(isRecording: boolean): void {
    console.log('[VOICE] Recording:', isRecording)
    // TODO: Implement speech-to-text
  }

  onChoiceSelected(choice: string): void {
    console.log('choosing selected', choice)
    this.codayService.sendChoice(choice)
  }

  onCopyMessage(message: ChatMessage): void {
    console.log('[COPY] Message copied:', message.id)
  }

  onStopRequested(): void {
    this.codayService.stop()
  }

  onInputHeightChanged(height: number): void {
    console.log('[MAIN-APP] Input height changed:', height)
    this.inputSectionHeight = height
  }

  private updateInputSectionHeight(): void {
    if (this.inputSection?.nativeElement) {
      const height = this.inputSection.nativeElement.offsetHeight
      if (height !== this.inputSectionHeight) {
        console.log('[MAIN-APP] Input section height updated:', height)
        this.inputSectionHeight = height
      }
    }
  }

  // Drag and Drop Event Handlers for the entire application
  @HostListener('dragenter', ['$event'])
  onDragEnter(event: DragEvent): void {
    event.preventDefault()
    if (this.imageUploadService.hasImageFiles(event.dataTransfer)) {
      console.log('[MAIN-APP] Image files detected - showing drag overlay')
      this.isDragOver = true
    }
  }

  @HostListener('dragover', ['$event'])
  onDragOver(event: DragEvent): void {
    event.preventDefault()
    if (this.imageUploadService.hasImageFiles(event.dataTransfer)) {
      event.dataTransfer!.dropEffect = 'copy'
    }
  }

  @HostListener('dragleave', ['$event'])
  onDragLeave(event: DragEvent): void {
    // Only remove drag-over state if we're leaving the document body
    if (event.target === document.body || !document.body.contains(event.relatedTarget as Node)) {
      console.log('[MAIN-APP] Leaving application area - hiding drag overlay')
      this.isDragOver = false
    }
  }

  @HostListener('drop', ['$event'])
  async onDrop(event: DragEvent): Promise<void> {
    event.preventDefault()
    this.isDragOver = false

    console.log('[MAIN-APP] Files dropped:', event.dataTransfer?.files?.length || 0)

    // TODO: Adapt image upload for thread-based architecture
    this.showUploadError('Image upload not yet implemented in new architecture')
    return

    const files = this.imageUploadService.filterImageFiles(event.dataTransfer?.files || [])

    if (files.length === 0) {
      this.showUploadError('No valid image files found')
      return
    }

    console.log('[MAIN-APP] Uploading', files.length, 'image(s)')

    // Image upload disabled for now in new architecture
    this.showUploadError('Image upload not yet implemented')
  }

  private showUploadError(message: string): void {
    this.uploadStatus = { message, isError: true }
    // Auto-hide error message after 5 seconds
    setTimeout(() => {
      this.uploadStatus = { message: '', isError: false }
    }, 5000)
  }

  // Print handling
  private setupPrintHandlers(): void {
    window.addEventListener('beforeprint', this.handleBeforePrint)
    window.addEventListener('afterprint', this.handleAfterPrint)
  }

  private handleBeforePrint = (): void => {
    console.log('[PRINT] Before print event triggered')
    const printTechnicalMessages = this.preferencesService.getPrintTechnicalMessages()
    console.log('[PRINT] Print technical messages:', printTechnicalMessages)

    if (printTechnicalMessages) {
      document.body.classList.add('print-include-technical')
    } else {
      document.body.classList.remove('print-include-technical')
    }
  }

  private handleAfterPrint = (): void => {
    console.log('[PRINT] After print event triggered')
    // Clean up the class after printing
    document.body.classList.remove('print-include-technical')
  }
}
