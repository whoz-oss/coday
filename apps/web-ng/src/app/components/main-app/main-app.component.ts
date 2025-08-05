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

@Component({
  selector: 'app-main',
  standalone: true,
  imports: [CommonModule, ChatHistoryComponent, ChatTextareaComponent, ChoiceSelectComponent],
  templateUrl: './main-app.component.html',
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
  ],
  styles: [`
    :host {
      display: block;
      height: 100vh;
      overflow: hidden;
    }

    .app {
      height: 100%;
      display: flex;
      flex-direction: column;
      background: var(--color-bg, #f8f8f2);
      color: var(--color-text, #282a36);
      transition: all 0.2s ease;
    }

    .app.drag-over {
      border: 3px dashed var(--color-primary, #007acc);
      background-color: var(--color-bg-secondary, #f1f1f1);
      opacity: 0.9;
    }

    .connection-status {
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      z-index: 1001; /* Au-dessus du FAB */
      padding: 0.5rem 1rem;
      text-align: center;
      font-size: 0.9rem;
      background: var(--color-error, #ff5555);
      color: var(--color-text-inverse, #ffffff);
      border-bottom: 1px solid var(--color-border, #aeaeae);
    }

    /* Le header est maintenant un FAB flottant - pas d'espace réservé */
    app-header {
      /* Pas de flex-shrink car le header est flottant */
    }

    .chat-wrapper {
      flex: 1;
      min-height: 0; /* Important pour flex */
      overflow-y: auto;
      overflow-x: hidden;
      background: var(--color-input-bg, #ffffff);
      /* Pas de padding-top : le contenu peut passer sous le FAB avec l'effet de dégradé */
      
      /* Force scrollbar visibility on all platforms */
      &::-webkit-scrollbar {
        width: 12px;
      }
      
      &::-webkit-scrollbar-track {
        background: var(--color-bg-secondary, #f1f1f1);
        border: 1px solid var(--color-border, #e5e7eb);
      }
      
      &::-webkit-scrollbar-thumb {
        background: var(--color-text-secondary, #888);
        border-radius: 6px;
        border: 2px solid transparent;
        background-clip: padding-box;
        
        &:hover {
          background: var(--color-text, #555);
        }
      }
      
      /* Firefox */
      scrollbar-width: thin;
      scrollbar-color: var(--color-text-secondary, #888) var(--color-bg-secondary, #f1f1f1);
    }

    .input-section {
      flex-shrink: 0;
      border-top: 1px solid var(--color-border, #e5e7eb);
      background: var(--color-bg, #f8f8f2);
      position: relative;
      overflow: hidden;
    }

    .upload-status {
      position: fixed;
      bottom: 100px;
      left: 50%;
      transform: translateX(-50%);
      z-index: 1000;
      display: flex;
      align-items: center;
      padding: 8px 16px;
      background: var(--color-bg-secondary, #f1f1f1);
      border: 1px solid var(--color-border, #e5e7eb);
      border-radius: 6px;
      font-size: 0.9em;
      color: var(--color-text-secondary, #666);
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
      max-width: 300px;
      text-align: center;
    }

    .upload-status.error {
      background: #ffebee;
      color: #c62828;
      border-color: #ffcdd2;
    }

    [data-theme="dark"] .upload-status {
      background: var(--color-bg-secondary, #2a2a2a);
      color: var(--color-text-secondary, #ccc);
      border-color: var(--color-border, #444);
    }

    [data-theme="dark"] .upload-status.error {
      background: #4a2c2a;
      color: #ef5350;
      border-color: #6d4c41;
    }
  `]
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
    private imageUploadService: ImageUploadService
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