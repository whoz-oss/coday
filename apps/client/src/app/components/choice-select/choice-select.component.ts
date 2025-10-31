import {
  AfterViewInit,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
  ViewChild,
  inject,
} from '@angular/core'
import { FormsModule } from '@angular/forms'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import { BehaviorSubject, Observable } from 'rxjs'
import { AsyncPipe } from '@angular/common'
import { MatIconModule } from '@angular/material/icon'
import { MatButtonModule } from '@angular/material/button'
import { MarkdownService } from '../../services/markdown.service'

export interface ChoiceOption {
  value: string
  label: string
  disabled?: boolean
}

@Component({
  selector: 'app-choice-select',
  standalone: true,
  imports: [FormsModule, AsyncPipe, MatIconModule, MatButtonModule],
  templateUrl: './choice-select.component.html',
  styleUrl: './choice-select.component.scss',
})
export class ChoiceSelectComponent implements AfterViewInit, OnChanges, OnDestroy {
  @Input() options: ChoiceOption[] = []
  @Input() set labelHtml(value: string | SafeHtml) {
    // If it's already SafeHtml, use it directly
    if (typeof value === 'object') {
      this.renderedLabelSubject.next(value as SafeHtml)
    } else {
      // Otherwise render as markdown
      this.renderLabelMarkdown(value as string)
    }
  }
  @Input() isVisible: boolean = false

  @Output() choiceSelected = new EventEmitter<string>()

  @ViewChild('choiceSelect') selectElement!: ElementRef<HTMLSelectElement>

  selectedValue: string = ''
  isFocused = false

  // Observable for asynchronous label rendering
  private renderedLabelSubject = new BehaviorSubject<SafeHtml>('')
  renderedLabel$: Observable<SafeHtml> = this.renderedLabelSubject.asObservable()

  // Modern Angular dependency injection
  private sanitizer = inject(DomSanitizer)
  private markdownService = inject(MarkdownService)

  ngOnDestroy(): void {
    this.renderedLabelSubject.complete()
  }

  ngAfterViewInit(): void {
    // Auto-focus when component becomes visible
    if (this.isVisible && this.selectElement) {
      setTimeout(() => this.focusSelect(), 100)
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    // Auto-focus when isVisible changes to true
    if (changes['isVisible']) {
      console.log(
        '[CHOICE-SELECT] isVisible changed:',
        changes['isVisible'].previousValue,
        '->',
        changes['isVisible'].currentValue
      )
      if (changes['isVisible'].currentValue && !changes['isVisible'].previousValue) {
        console.log('[CHOICE-SELECT] Component becoming visible, setting focus...')
        setTimeout(() => this.focusSelect(), 150) // Slightly longer delay for animation
      }
    }
  }

  private focusSelect(): void {
    if (this.selectElement?.nativeElement) {
      this.selectElement.nativeElement.focus()
      console.log('[CHOICE-SELECT] Focus set on select element')
    }
  }

  onSubmit() {
    if (this.selectedValue && this.selectedValue !== '__separator__') {
      console.log('[CHOICE-SELECT] Choice selected:', this.selectedValue)
      this.choiceSelected.emit(this.selectedValue)
      this.selectedValue = '' // Reset for next use
    } else {
      console.warn('[CHOICE-SELECT] No choice selected or separator selected')
    }
  }

  onSelectChange() {
    console.log('[CHOICE-SELECT] Selection changed to:', this.selectedValue)
  }

  /**
   * Render label markdown asynchronously
   */
  private async renderLabelMarkdown(label: string): Promise<void> {
    if (!label) {
      this.renderedLabelSubject.next(this.sanitizer.bypassSecurityTrustHtml(''))
      return
    }

    try {
      const html = await this.markdownService.parse(label)
      this.renderedLabelSubject.next(this.sanitizer.bypassSecurityTrustHtml(html))
    } catch (error) {
      console.error('[CHOICE-SELECT] Error parsing label markdown:', error)
      // On error, display raw text
      this.renderedLabelSubject.next(this.sanitizer.bypassSecurityTrustHtml(label))
    }
  }

  // TODO: Connect to CodayEventHandler to handle ChoiceEvent
  // TODO: Add voice synthesis integration
}
