import {
  Component,
  Input,
  OnInit,
  OnDestroy,
  OnChanges,
  SimpleChanges,
  AfterViewChecked,
  ElementRef,
  ViewChild,
  inject,
} from '@angular/core'
import { Router } from '@angular/router'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'

import { ChatHistoryComponent } from '../chat-history/chat-history.component'
import { ChatMessage } from '../chat-message/chat-message.component'
import { ChatTextareaComponent } from '../chat-textarea/chat-textarea.component'
import { ChoiceOption, ChoiceSelectComponent } from '../choice-select/choice-select.component'
import { FileExchangeDrawerComponent } from '../file-exchange-drawer/file-exchange-drawer.component'
import { MatSidenavModule } from '@angular/material/sidenav'
import { MatIconModule } from '@angular/material/icon'
import { MatButtonModule } from '@angular/material/button'
import { MatBadgeModule } from '@angular/material/badge'

import { CodayService } from '../../core/services/coday.service'
import { ConnectionStatus } from '../../core/services/event-stream.service'
import { PreferencesService } from '../../services/preferences.service'
import { TabTitleService } from '../../services/tab-title.service'
import { ThreadStateService } from '../../core/services/thread-state.service'
import { ImageUploadService } from '../../services/image-upload.service'
import { FileExchangeStateService } from '../../core/services/file-exchange-state.service'

/**
 * ThreadComponent - Dedicated component for displaying and interacting with a conversation thread
 *
 * This component is responsible for:
 * - Establishing SSE connection to a specific thread
 * - Displaying message history
 * - Handling user input and message submission
 * - Managing conversation state (thinking, choices, invites)
 * - Handling drag & drop for image uploads
 * - Print functionality
 *
 * Prerequisites:
 * - A thread must exist (threadId must be provided)
 * - Project must be selected (projectName must be provided)
 * - Thread state guards have already validated the thread exists
 */
@Component({
  selector: 'app-thread',
  standalone: true,
  imports: [
    ChatHistoryComponent,
    ChatTextareaComponent,
    ChoiceSelectComponent,
    FileExchangeDrawerComponent,
    MatSidenavModule,
    MatIconModule,
    MatButtonModule,
    MatBadgeModule,
  ],
  templateUrl: './thread.component.html',
  styleUrl: './thread.component.scss',
})
export class ThreadComponent implements OnInit, OnDestroy, OnChanges, AfterViewChecked {
  // Required inputs - thread identification
  @Input({ required: true }) projectName!: string
  @Input({ required: true }) threadId!: string

  private readonly destroy$ = new Subject<void>()

  @ViewChild('inputSection') inputSection!: ElementRef<HTMLElement>

  // State from services
  messages: ChatMessage[] = []
  streamingText: string = ''
  isThinking: boolean = false
  currentChoice: { options: ChoiceOption[]; label: string } | null = null
  connectionStatus: ConnectionStatus | null = null
  isConnected: boolean = false
  showConnectionStatus: boolean = false
  private hasEverConnected: boolean = false

  // Input height management
  inputSectionHeight: number = 80 // Default height

  // Upload status
  uploadStatus: { message: string; isError: boolean } = { message: '', isError: false }

  // Drag and drop state
  isDragOver: boolean = false

  // File exchange drawer state
  isFileDrawerOpen: boolean = false

  // Connect to file exchange state for file count
  get fileCount(): number {
    return this.fileExchangeState.fileCount()
  }

  // First message from implicit thread creation
  private pendingFirstMessage: string | null = null
  private subscriptions: any[] = []

  // Modern Angular dependency injection
  private readonly codayService = inject(CodayService)
  private readonly preferencesService = inject(PreferencesService)
  private readonly titleService = inject(TabTitleService)
  private readonly elementRef = inject(ElementRef)
  private readonly router = inject(Router)
  private readonly threadState = inject(ThreadStateService)
  private readonly imageUploadService = inject(ImageUploadService)
  private readonly fileExchangeState = inject(FileExchangeStateService)

  ngOnInit(): void {
    console.log('[THREAD] Initializing with project:', this.projectName, 'thread:', this.threadId)

    // Validate required inputs
    if (!this.projectName || !this.threadId) {
      console.error('[THREAD] Missing required inputs: projectName or threadId')
      return
    }

    // Setup print event listeners
    this.setupPrintHandlers()

    // Initialize the thread connection
    this.initializeThreadConnection()
  }

  ngOnChanges(changes: SimpleChanges): void {
    console.log('[THREAD] ngOnChanges called', changes)

    // Detect when threadId changes (user navigating to different thread)
    if (changes['threadId']) {
      console.log('[THREAD] threadId changed:', {
        firstChange: changes['threadId'].firstChange,
        previousValue: changes['threadId'].previousValue,
        currentValue: changes['threadId'].currentValue,
      })

      if (!changes['threadId'].firstChange) {
        console.log('[THREAD] Reconnecting to new thread')
        // Reconnect to the new thread
        this.initializeThreadConnection()
      }
    }
  }

  /**
   * Initialize or reinitialize the thread connection
   */
  private initializeThreadConnection(): void {
    console.log('[THREAD] Initializing connection for thread:', this.threadId)

    // Initialize file exchange state for this thread
    this.fileExchangeState.initializeForThread(this.projectName, this.threadId)

    // Subscribe to conversation state
    this.codayService.messages$.pipe(takeUntil(this.destroy$)).subscribe((messages) => {
      console.log('[THREAD] Messages updated:', messages.length)
      this.messages = messages
    })

    this.codayService.streamingText$.pipe(takeUntil(this.destroy$)).subscribe((streamingText) => {
      this.streamingText = streamingText
    })

    this.codayService.isThinking$.pipe(takeUntil(this.destroy$)).subscribe((isThinking) => {
      this.isThinking = isThinking
    })

    this.codayService.currentChoice$.pipe(takeUntil(this.destroy$)).subscribe((choice) => {
      this.currentChoice = choice
    })

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

    // Listen for thread update events to refresh the thread list
    this.codayService.threadUpdateEvent$.pipe(takeUntil(this.destroy$)).subscribe((updateEvent) => {
      if (updateEvent) {
        console.log('[THREAD] Thread update event received:', updateEvent)
        // Refresh the thread list to show the updated name
        this.threadState.refreshThreadList()
      }
    })

    // Reset messages when switching threads
    this.codayService.resetMessages()

    // Connect services (to avoid circular dependency)
    this.codayService.setTabTitleService(this.titleService)

    // Connect to the thread's event stream
    console.log('[THREAD] Connecting to thread event stream')
    this.codayService.connectToThread(this.projectName, this.threadId)

    // Check if we have a first message from router state (implicit thread creation)
    // Try both getCurrentNavigation() and history.state as fallback
    const navigation = this.router.getCurrentNavigation()
    let firstMessage = navigation?.extras?.state?.['firstMessage'] as string | undefined

    // Fallback: check window.history.state if navigation is null
    if (!firstMessage && window.history.state?.['firstMessage']) {
      firstMessage = window.history.state['firstMessage'] as string
      console.log('[THREAD] Retrieved first message from history.state')
    }

    // If we have a first message, wait for the first InviteEvent before sending it
    if (firstMessage) {
      console.log('[THREAD] First message pending:', firstMessage.substring(0, 50) + '...')
      this.pendingFirstMessage = firstMessage

      // Immediately set thinking state to prevent showing agent selection or other UI
      // This will be maintained until the actual ThinkingEvent arrives from the server
      this.codayService.setThinkingForPendingMessage()

      this.subscribeToFirstInvite()
    } else {
      console.log('[THREAD] No first message found in navigation state')
    }
  }

  ngAfterViewChecked(): void {
    // Initial height measurement
    if (this.inputSection?.nativeElement) {
      const height = this.inputSection.nativeElement.offsetHeight
      if (height !== this.inputSectionHeight && height > 0) {
        this.inputSectionHeight = height
      }
    }
  }

  ngOnDestroy(): void {
    console.log('[THREAD] Destroying component')
    this.destroy$.next()
    this.destroy$.complete()

    // Clear file exchange state
    this.fileExchangeState.clear()

    // Clean up subscriptions
    this.subscriptions.forEach((sub) => sub.unsubscribe())
    this.subscriptions = []

    // Cleanup print handlers
    window.removeEventListener('beforeprint', this.handleBeforePrint)
    window.removeEventListener('afterprint', this.handleAfterPrint)
  }

  onMessageSubmitted(message: string): void {
    console.log('[THREAD] Sending message:', message)
    this.codayService.sendMessage(message)
  }

  onVoiceToggled(isRecording: boolean): void {
    console.log('[VOICE] Recording:', isRecording)
    // TODO: Implement speech-to-text
  }

  onChoiceSelected(choice: string): void {
    console.log('[THREAD] Choice selected:', choice)
    this.codayService.sendChoice(choice)
  }

  onCopyMessage(message: ChatMessage): void {
    console.log('[COPY] Message copied:', message.id)
  }

  onStopRequested(): void {
    this.threadState.stop()
  }

  onInputHeightChanged(height: number): void {
    console.log('[THREAD] Input height changed:', height)
    this.inputSectionHeight = height
  }

  onFilesPasted(files: File[]): void {
    console.log('[THREAD] File(s) pasted:', files.length)

    const imageFiles = this.imageUploadService.filterImageFiles(files)
    const otherFiles = files.filter((f) => !f.type.startsWith('image/'))

    if (imageFiles.length) {
      this.uploadFilesSequentially(imageFiles, 0)
    }
    if (otherFiles.length) {
      void this.uploadFilesToExchangeSpace(otherFiles)
    }
    if (!imageFiles.length && !otherFiles.length) {
      this.showUploadError('No supported files found')
    }
  }

  // File drawer methods
  toggleFileDrawer(): void {
    console.log('[THREAD] Toggling file drawer')
    this.isFileDrawerOpen = !this.isFileDrawerOpen
  }

  closeFileDrawer(): void {
    console.log('[THREAD] Closing file drawer')
    this.isFileDrawerOpen = false
  }

  // Drag and Drop Event Handlers for image uploads
  onDragEnter(event: DragEvent): void {
    event.preventDefault()
    // Check if dragged items contain images
    if (event.dataTransfer?.types.includes('Files')) {
      console.log('[THREAD] Image files detected - showing drag overlay')
      this.isDragOver = true
    }
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault()
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'copy'
    }
  }

  onDragLeave(event: DragEvent): void {
    // Only remove drag-over state if we're leaving the component area
    const element = this.elementRef.nativeElement
    if (!element.contains(event.relatedTarget as Node)) {
      console.log('[THREAD] Leaving component area - hiding drag overlay')
      this.isDragOver = false
    }
  }

  onDrop(event: DragEvent): void {
    event.preventDefault()
    this.isDragOver = false

    console.log('[THREAD] Files dropped:', event.dataTransfer?.files?.length || 0)

    const files = event.dataTransfer?.files
    if (!files || files.length === 0) {
      console.log('[THREAD] No files in drop event')
      return
    }

    // Separate images from other files
    const imageFiles = this.imageUploadService.filterImageFiles(files)
    const otherFiles = Array.from(files).filter((file) => !file.type.startsWith('image/'))

    console.log('[THREAD] Image files:', imageFiles.length, 'Other files:', otherFiles.length)

    // Upload images (existing behavior - added to conversation)
    if (imageFiles.length > 0) {
      this.uploadFilesSequentially(imageFiles, 0)
    }

    // Upload other files to exchange space
    if (otherFiles.length > 0) {
      this.uploadFilesToExchangeSpace(otherFiles)
    }

    // Show error if no valid files
    if (imageFiles.length === 0 && otherFiles.length === 0) {
      this.showUploadError('No supported files found')
    }
  }

  /**
   * Upload files sequentially (one after another)
   */
  private uploadFilesSequentially(files: File[], index: number): void {
    if (index >= files.length) {
      return // All files uploaded
    }

    const file = files[index]
    if (!file) {
      console.error('[THREAD] File at index', index, 'is undefined')
      return
    }

    console.log('[THREAD] Uploading file:', file.name)
    this.uploadStatus = { message: `Uploading ${file.name}...`, isError: false }

    this.imageUploadService.uploadImage(file, this.projectName, this.threadId).subscribe({
      next: (result) => {
        if (result.success) {
          console.log('[THREAD] Upload successful:', file.name)
          this.uploadStatus = {
            message: `✓ ${file.name} uploaded (${(result.processedSize! / 1024).toFixed(1)} KB)`,
            isError: false,
          }
          // Auto-hide success message after 3 seconds
          setTimeout(() => {
            if (!this.uploadStatus.isError) {
              this.uploadStatus = { message: '', isError: false }
            }
          }, 3000)
          // Upload next file
          this.uploadFilesSequentially(files, index + 1)
        } else {
          console.error('[THREAD] Upload failed:', file.name, result.error)
          this.showUploadError(`Failed to upload ${file.name}: ${result.error}`)
          // Stop on error
        }
      },
      error: (error) => {
        console.error('[THREAD] Upload error:', file.name, error)
        this.showUploadError(`Failed to upload ${file.name}: ${error.message || 'Unknown error'}`)
        // Stop on error
      },
    })
  }

  private showUploadError(message: string): void {
    this.uploadStatus = { message, isError: true }
    // Auto-hide error message after 5 seconds
    setTimeout(() => {
      this.uploadStatus = { message: '', isError: false }
    }, 5000)
  }

  /**
   * Upload non-image files to the exchange space
   */
  private async uploadFilesToExchangeSpace(files: File[]): Promise<void> {
    for (const file of files) {
      console.log('[THREAD] Uploading to exchange space:', file.name)
      this.uploadStatus = { message: `Uploading ${file.name}...`, isError: false }

      const result = await this.fileExchangeState.uploadFile(file)

      if (result.success) {
        console.log('[THREAD] Upload to exchange space successful:', file.name)
        this.uploadStatus = {
          message: `✓ ${file.name} uploaded to exchange space`,
          isError: false,
        }

        // Note: Backend already adds a message to the conversation,
        // so we don't need to send one here

        // Auto-hide success message after 3 seconds
        setTimeout(() => {
          if (!this.uploadStatus.isError) {
            this.uploadStatus = { message: '', isError: false }
          }
        }, 3000)
      } else {
        console.error('[THREAD] Upload to exchange space failed:', file.name, result.error)
        this.showUploadError(`Failed to upload ${file.name}: ${result.error}`)
        break // Stop on error
      }
    }
  }

  /**
   * Subscribe to the first InviteEvent to send the pending first message
   */
  private subscribeToFirstInvite(): void {
    // First, check if there's already an InviteEvent available (timing issue)
    const existingInvite = this.codayService.getCurrentInviteEvent()
    if (existingInvite && this.pendingFirstMessage) {
      console.log('[THREAD] InviteEvent already available, sending pending message immediately')
      const messageToSend = this.pendingFirstMessage
      this.pendingFirstMessage = null
      this.codayService.sendMessage(messageToSend)
      return // Don't subscribe if we already sent the message
    }

    // Otherwise, subscribe to wait for the InviteEvent
    console.log('[THREAD] Subscribing to wait for InviteEvent')
    this.subscriptions.push(
      this.codayService.currentInviteEvent$.subscribe((inviteEvent) => {
        if (inviteEvent && this.pendingFirstMessage) {
          console.log('[THREAD] Received InviteEvent via subscription, sending pending message')
          const messageToSend = this.pendingFirstMessage
          this.pendingFirstMessage = null // Clear to avoid sending twice

          // Send the message - it will use the InviteEvent to build proper AnswerEvent
          this.codayService.sendMessage(messageToSend)
        }
      })
    )
  }

  // Print handling
  private setupPrintHandlers(): void {
    window.addEventListener('beforeprint', this.handleBeforePrint)
    window.addEventListener('afterprint', this.handleAfterPrint)
  }

  private readonly handleBeforePrint = (): void => {
    console.log('[PRINT] Before print event triggered')
    const printTechnicalMessages = this.preferencesService.getPrintTechnicalMessages()
    console.log('[PRINT] Print technical messages:', printTechnicalMessages)

    if (printTechnicalMessages) {
      document.body.classList.add('print-include-technical')
    } else {
      document.body.classList.remove('print-include-technical')
    }
  }

  private readonly handleAfterPrint = (): void => {
    console.log('[PRINT] After print event triggered')
    // Clean up the class after printing
    document.body.classList.remove('print-include-technical')
  }
}
