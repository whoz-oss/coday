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
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import { BehaviorSubject, Observable, Subject } from 'rxjs'
import { AsyncPipe } from '@angular/common'
import { MatIconModule } from '@angular/material/icon'
import { MarkdownService } from '../../services/markdown.service'

export interface ChoiceOption {
  value: string
  label: string
  disabled?: boolean
}

@Component({
  selector: 'app-choice-select',
  standalone: true,
  imports: [AsyncPipe, MatIconModule],
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

  @ViewChild('buttonContainer') buttonContainer!: ElementRef<HTMLDivElement>

  highlightedIndex: number = 0
  searchText: string = ''
  private searchTimeout?: ReturnType<typeof setTimeout>
  private destroy$ = new Subject<void>()

  // Observable for asynchronous label rendering
  private renderedLabelSubject = new BehaviorSubject<SafeHtml>('')
  renderedLabel$: Observable<SafeHtml> = this.renderedLabelSubject.asObservable()

  // Modern Angular dependency injection
  private sanitizer = inject(DomSanitizer)
  private markdownService = inject(MarkdownService)

  ngOnDestroy(): void {
    this.renderedLabelSubject.complete()
    this.destroy$.next()
    this.destroy$.complete()
    if (this.searchTimeout) {
      clearTimeout(this.searchTimeout)
    }
  }

  ngAfterViewInit(): void {
    // Auto-focus when component becomes visible
    if (this.isVisible && this.buttonContainer) {
      setTimeout(() => this.focusContainer(), 100)
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
        this.highlightedIndex = 0 // Reset to first option
        setTimeout(() => this.focusContainer(), 150)
      }
    }

    // Reset highlighted index if options change
    if (changes['options'] && !changes['options'].firstChange) {
      this.highlightedIndex = 0
    }
  }

  private focusContainer(): void {
    if (this.buttonContainer?.nativeElement) {
      this.buttonContainer.nativeElement.focus()
      console.log('[CHOICE-SELECT] Focus set on button container')
    }
  }

  onKeyDown(event: KeyboardEvent): void {
    const selectableOptions = this.options.filter((opt) => !opt.disabled)

    if (selectableOptions.length === 0) return

    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault()
        this.moveHighlight(1)
        break

      case 'ArrowUp':
        event.preventDefault()
        this.moveHighlight(-1)
        break

      case 'Enter':
      case ' ':
        event.preventDefault()
        this.selectHighlighted()
        break

      case 'Home':
        event.preventDefault()
        this.highlightFirst()
        break

      case 'End':
        event.preventDefault()
        this.highlightLast()
        break

      case 'Escape':
        event.preventDefault()
        // Could emit a cancel event if needed
        break

      default:
        // Type-ahead search
        if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
          this.handleTypeAhead(event.key)
        }
        break
    }
  }

  private moveHighlight(direction: 1 | -1): void {
    const selectableIndices = this.options
      .map((opt, idx) => ({ opt, idx }))
      .filter(({ opt }) => !opt.disabled)
      .map(({ idx }) => idx)

    if (selectableIndices.length === 0) return

    const currentSelectableIdx = selectableIndices.findIndex((idx) => idx === this.highlightedIndex)
    const nextSelectableIdx = currentSelectableIdx + direction

    if (nextSelectableIdx >= 0 && nextSelectableIdx < selectableIndices.length) {
      const newIndex = selectableIndices[nextSelectableIdx]
      if (newIndex !== undefined) {
        this.highlightedIndex = newIndex
        this.scrollToHighlighted()
      }
    } else if (direction === 1 && nextSelectableIdx >= selectableIndices.length) {
      // Wrap to first
      const firstIndex = selectableIndices[0]
      if (firstIndex !== undefined) {
        this.highlightedIndex = firstIndex
        this.scrollToHighlighted()
      }
    } else if (direction === -1 && nextSelectableIdx < 0) {
      // Wrap to last
      const lastIndex = selectableIndices[selectableIndices.length - 1]
      if (lastIndex !== undefined) {
        this.highlightedIndex = lastIndex
        this.scrollToHighlighted()
      }
    }
  }

  private highlightFirst(): void {
    const firstSelectable = this.options.findIndex((opt) => !opt.disabled)
    if (firstSelectable >= 0) {
      this.highlightedIndex = firstSelectable
      this.scrollToHighlighted()
    }
  }

  private highlightLast(): void {
    const selectableIndices = this.options
      .map((opt, idx) => ({ opt, idx }))
      .filter(({ opt }) => !opt.disabled)
      .map(({ idx }) => idx)

    if (selectableIndices.length > 0) {
      const lastIndex = selectableIndices[selectableIndices.length - 1]
      if (lastIndex !== undefined) {
        this.highlightedIndex = lastIndex
        this.scrollToHighlighted()
      }
    }
  }

  private scrollToHighlighted(): void {
    setTimeout(() => {
      const highlightedElement = document.getElementById('choice-option-' + this.highlightedIndex)
      if (highlightedElement) {
        highlightedElement.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
      }
    }, 0)
  }

  private selectHighlighted(): void {
    const option = this.options[this.highlightedIndex]
    if (option && !option.disabled) {
      this.onOptionClick(option.value)
    }
  }

  private handleTypeAhead(char: string): void {
    this.searchText += char.toLowerCase()

    // Find first matching option
    const matchIndex = this.options.findIndex(
      (opt) => !opt.disabled && opt.label.toLowerCase().startsWith(this.searchText)
    )

    if (matchIndex >= 0) {
      this.highlightedIndex = matchIndex
      this.scrollToHighlighted()
    }

    // Clear search text after 1 second
    if (this.searchTimeout) {
      clearTimeout(this.searchTimeout)
    }
    this.searchTimeout = setTimeout(() => {
      this.searchText = ''
    }, 1000)
  }

  onOptionClick(value: string): void {
    console.log('[CHOICE-SELECT] Choice selected:', value)
    this.choiceSelected.emit(value)
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
