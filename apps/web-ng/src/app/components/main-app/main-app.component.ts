import { Component, OnInit, OnDestroy, HostListener } from '@angular/core'
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
import { ImageUploadService } from '../../services/image-upload.service'
import { TabTitleService } from '../../services/tab-title.service'

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
export class MainAppComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()
  
  // State from services
  messages: ChatMessage[] = []
  isThinking: boolean = false
  currentChoice: {options: ChoiceOption[], label: string} | null = null
  connectionStatus: ConnectionStatus | null = null
  isConnected: boolean = false
  
  // Upload status
  uploadStatus: { message: string; isError: boolean } = { message: '', isError: false }
  clientId: string
  
  // Drag and drop state
  isDragOver: boolean = false

  constructor(
    private codayService: CodayService,
    private codayApiService: CodayApiService,
    private imageUploadService: ImageUploadService,
    private titleService: TabTitleService // Renommé pour éviter les conflits
  ) {
    this.clientId = this.codayApiService.getClientId()
    console.log('[MAIN-APP] Constructor - clientId:', this.clientId)
  }

  ngOnInit(): void {
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

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
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

  onPlayMessage(message: ChatMessage): void {
    // Extraire le texte du contenu riche pour le log
    const textContent = message.content
      .filter(content => content.type === 'text')
      .map(content => content.content)
      .join(' ')
    console.log('[VOICE] Play requested:', textContent)
    // TODO: Implement voice synthesis
  }

  onCopyMessage(message: ChatMessage): void {
    console.log('[COPY] Message copied:', message.id)
  }

  onStopRequested(): void {
    this.codayService.stop()
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
}