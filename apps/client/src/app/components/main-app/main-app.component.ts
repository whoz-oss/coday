import { AfterViewInit, Component, ElementRef, inject, OnDestroy, OnInit, ViewChild } from '@angular/core'
import { DOCUMENT } from '@angular/common'
import { ActivatedRoute, Router } from '@angular/router'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { animate, style, transition, trigger } from '@angular/animations'

import { ChatTextareaComponent } from '../chat-textarea/chat-textarea.component'
import { ThreadComponent } from '../thread/thread.component'
import { WelcomeMessageComponent } from '../welcome-message'
import { SidenavComponent } from '../sidenav'

import { CodayService } from '../../core/services/coday.service'
import { TabTitleService } from '../../services/tab-title.service'
import { PreferencesService } from '../../services/preferences.service'
import { ThreadApiService } from '../../core/services/thread-api.service'
import { AgentNotificationService } from '../../services/agent-notification.service'
import { FirstMessageStateService } from '../../core/services/first-message-state.service'
import { WINDOW } from '../../core/tokens/window'

@Component({
  selector: 'app-main',
  standalone: true,
  imports: [ChatTextareaComponent, ThreadComponent, WelcomeMessageComponent, SidenavComponent],
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

  // Route parameters
  projectName: string = ''
  threadId: string | null = null

  // State for welcome view (when no thread selected)
  isSessionInitializing: boolean = false
  isStartingFirstMessage: boolean = false

  // Input height management for welcome view
  inputSectionHeight: number = 80 // Default height

  // Modern Angular dependency injection
  private readonly window = inject(WINDOW)
  private readonly document = inject(DOCUMENT)
  private codayService = inject(CodayService)
  private titleService = inject(TabTitleService)
  private preferencesService = inject(PreferencesService)
  private route = inject(ActivatedRoute)
  private router = inject(Router)
  private threadApiService = inject(ThreadApiService)
  private agentNotificationService = inject(AgentNotificationService)
  private firstMessageState = inject(FirstMessageStateService)

  constructor() {
    console.log('[MAIN-APP] Using new thread-based architecture (no clientId needed)')
  }

  ngOnInit(): void {
    // Setup agent notification service to monitor task completion
    this.agentNotificationService.setup()

    // Setup print event listeners (for welcome view)
    this.setupPrintHandlers()

    // Subscribe to route parameter changes to detect thread navigation
    this.route.params.pipe(takeUntil(this.destroy$)).subscribe((params) => {
      const newProjectName = params['projectName']
      const newThreadId = params['threadId'] || null

      console.log('[MAIN-APP] Route params updated:', {
        project: newProjectName,
        thread: newThreadId,
        previousProject: this.projectName,
        previousThread: this.threadId,
      })

      // Update properties - this will trigger change detection in ThreadComponent
      this.projectName = newProjectName
      this.threadId = newThreadId

      // If no thread, initialize welcome view
      if (!this.threadId) {
        console.log('[MAIN-APP] No thread selected - showing welcome view')
        this.initializeWelcomeView()
      } else {
        console.log('[MAIN-APP] Thread selected - ThreadComponent will display')
      }
    })
  }

  /**
   * Initialize the welcome view when no thread is selected
   */
  private initializeWelcomeView(): void {
    // Connect services (to avoid circular dependency)
    this.codayService.setTabTitleService(this.titleService)
  }

  ngAfterViewInit(): void {
    // Initial height measurement
    setTimeout(() => this.updateInputSectionHeight(), 100)
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()

    // Cleanup print handlers
    this.window.removeEventListener('beforeprint', this.handleBeforePrint)
    this.window.removeEventListener('afterprint', this.handleAfterPrint)
  }

  /**
   * Handle message submission from welcome view (implicit thread creation)
   */
  onMessageSubmitted(message: string): void {
    console.log('[MAIN-APP] First message submitted from welcome view:', message)

    // Immediately disable textarea and show starting state
    this.isSessionInitializing = true
    this.isStartingFirstMessage = true

    // Create thread WITHOUT a name - the backend will auto-generate a name from the first message
    this.threadApiService.createThread(this.projectName).subscribe({
      next: (response) => {
        console.log('[MAIN-APP] Thread created:', response.thread.id)

        // Store the first message in the state service
        this.firstMessageState.setPendingFirstMessage(message)

        // Navigate to the new thread
        this.router.navigate(['project', this.projectName, 'thread', response.thread.id])

        // Note: We don't reset isSessionInitializing here because we're navigating away
        // The ThreadComponent will take over and show its own thinking state
      },
      error: (error) => {
        console.error('[MAIN-APP] Failed to create thread:', error)
        // Reset states on error to allow user to try again
        this.isSessionInitializing = false
        this.isStartingFirstMessage = false
        // TODO: Show error message to user
        alert('Failed to create conversation: ' + (error.message || 'Unknown error'))
      },
    })
  }

  onVoiceToggled(isRecording: boolean): void {
    console.log('[VOICE] Recording in welcome view:', isRecording)
    // TODO: Implement speech-to-text for welcome view
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

  // Note: Drag and drop is now handled by ThreadComponent when a thread is active
  // Welcome view doesn't support drag and drop for now

  // Print handling
  private setupPrintHandlers(): void {
    this.window.addEventListener('beforeprint', this.handleBeforePrint)
    this.window.addEventListener('afterprint', this.handleAfterPrint)
  }

  private handleBeforePrint = (): void => {
    console.log('[PRINT] Before print event triggered')
    const printTechnicalMessages = this.preferencesService.getPrintTechnicalMessages()
    console.log('[PRINT] Print technical messages:', printTechnicalMessages)

    if (printTechnicalMessages) {
      this.document.body.classList.add('print-include-technical')
    } else {
      this.document.body.classList.remove('print-include-technical')
    }
  }

  private handleAfterPrint = (): void => {
    console.log('[PRINT] After print event triggered')
    // Clean up the class after printing
    this.document.body.classList.remove('print-include-technical')
  }
}
