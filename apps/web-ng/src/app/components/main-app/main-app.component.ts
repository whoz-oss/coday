import { Component, OnInit, OnDestroy, HostListener, ViewChild, ElementRef, AfterViewInit, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { trigger, transition, style, animate } from '@angular/animations'


import { ChatHistoryComponent } from '../chat-history/chat-history.component'
import { ChatMessage } from '../chat-message/chat-message.component'
import { ChatTextareaComponent } from '../chat-textarea/chat-textarea.component'
import { ChoiceSelectComponent, ChoiceOption } from '../choice-select/choice-select.component'

import { CodayService } from '../../core/services/coday.service'
import { CodayApiService } from '../../core/services/coday-api.service'
import { ConnectionStatus } from '../../core/services/event-stream.service'
import { SessionStateService } from '../../core/services/session-state.service'
import { ImageUploadService } from '../../services/image-upload.service'
import { TabTitleService } from '../../services/tab-title.service'
import { PreferencesService } from '../../services/preferences.service'

@Component({
  selector: 'app-main',
  standalone: true,
  imports: [CommonModule, ChatHistoryComponent, ChatTextareaComponent, ChoiceSelectComponent],
  templateUrl: './main-app.component.html',
  styleUrl: './main-app.component.scss',
  animations: [
    trigger('slideIn', [
      transition(':enter', [
        style({ transform: 'translateY(100%)', opacity: 0 }),
        animate('300ms ease-out', style({ transform: 'translateY(0)', opacity: 1 }))
      ]),
      transition(':leave', [
        animate('200ms ease-in', style({ transform: 'translateY(100%)', opacity: 0 }))
      ])
    ])
  ]
})
export class MainAppComponent implements OnInit, OnDestroy, AfterViewInit {
  private destroy$ = new Subject<void>()
  
  @ViewChild('inputSection') inputSection!: ElementRef<HTMLElement>
  
  // State from services
  messages: ChatMessage[] = []
  isThinking: boolean = false
  currentChoice: {options: ChoiceOption[], label: string} | null = null
  connectionStatus: ConnectionStatus | null = null
  isConnected: boolean = false
  
  // Input height management
  inputSectionHeight: number = 80 // Default height
  
  // Upload status
  uploadStatus: { message: string; isError: boolean } = { message: '', isError: false }
  clientId: string
  
  // Drag and drop state
  isDragOver: boolean = false

  // Modern Angular dependency injection
  private codayService = inject(CodayService)
  private codayApiService = inject(CodayApiService)
  private sessionStateService = inject(SessionStateService) // Injection pour initialiser le service
  private imageUploadService = inject(ImageUploadService)
  private titleService = inject(TabTitleService) // Renommé pour éviter les conflits
  private preferencesService = inject(PreferencesService)
  
  constructor() {
    this.clientId = this.codayApiService.getClientId()
    console.log('[MAIN-APP] Constructor - clientId:', this.clientId)
    console.log('[MAIN-APP] SessionStateService injected and will initialize:', !!this.sessionStateService)
  }

  ngOnInit(): void {
    // Setup print event listeners
    this.setupPrintHandlers()
    
    this.codayService.messages$
      .pipe(takeUntil(this.destroy$))
      .subscribe(messages => {
        console.log('[MAIN-APP] Messages updated:', messages.length)
        this.messages = messages
      })

    this.codayService.isThinking$
      .pipe(takeUntil(this.destroy$))
      .subscribe(isThinking => {
        this.isThinking = isThinking
      })

    this.codayService.currentChoice$
      .pipe(takeUntil(this.destroy$))
      .subscribe(choice => {
        this.currentChoice = choice
      })

    this.codayService.connectionStatus$
      .pipe(takeUntil(this.destroy$))
      .subscribe(status => {
        this.connectionStatus = status
        this.isConnected = status.connected
      })

    // Connecter les services (pour éviter la dépendance circulaire)
    this.codayService.setTabTitleService(this.titleService)
    
    // Start the Coday service
    this.codayService.start()
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
  }

  onMessageSubmitted(message: string): void {
    console.log('[MAIN-APP] Sending message:', message)
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
    
    if (!this.clientId) {
      this.showUploadError('No client ID available for upload')
      return
    }
    
    const files = this.imageUploadService.filterImageFiles(event.dataTransfer?.files || [])
    
    if (files.length === 0) {
      this.showUploadError('No valid image files found')
      return
    }

    console.log('[MAIN-APP] Uploading', files.length, 'image(s)')
    
    // Upload each image file
    for (const file of files) {
      try {
        this.showUploadStatus(`Uploading ${file.name}...`)
        const result = await this.imageUploadService.uploadImage(file, this.clientId)
        
        if (result.success) {
          this.showUploadSuccess(`${file.name} uploaded successfully`)
        } else {
          this.showUploadError(`Failed to upload ${file.name}: ${result.error}`)
        }
      } catch (error) {
        console.error('[MAIN-APP] Upload error:', error)
        this.showUploadError(`Failed to upload ${file.name}: ${error instanceof Error ? error.message : 'Unknown error'}`)
      }
    }
  }
  
  private showUploadStatus(message: string): void {
    this.uploadStatus = { message, isError: false }
  }
  
  private showUploadSuccess(message: string): void {
    this.uploadStatus = { message: `✅ ${message}`, isError: false }
    // Auto-hide success message after 3 seconds
    setTimeout(() => {
      this.uploadStatus = { message: '', isError: false }
    }, 3000)
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