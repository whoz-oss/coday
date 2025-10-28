import { AfterViewInit, Component, ElementRef, EventEmitter, Input, Output, ViewChild } from '@angular/core'
import { FormsModule } from '@angular/forms'
import { CommonModule } from '@angular/common'

/**
 * Generic text input component with label and hint support.
 * Reusable across the application for simple text input needs.
 */
@Component({
  selector: 'app-text-input',
  standalone: true,
  imports: [FormsModule, CommonModule],
  templateUrl: './text-input.component.html',
  styleUrl: './text-input.component.scss',
})
export class TextInputComponent implements AfterViewInit {
  @Input() label: string = ''
  @Input() placeholder: string = ''
  @Input() hint: string = ''
  @Input() isDisabled: boolean = false
  @Input() value: string = ''
  @Input() autoFocus: boolean = true

  @Output() valueChange = new EventEmitter<string>()
  @Output() enterPressed = new EventEmitter<string>()

  @ViewChild('inputElement') inputElement!: ElementRef<HTMLInputElement>

  inputValue: string = ''

  ngAfterViewInit(): void {
    this.inputValue = this.value
    if (this.autoFocus) {
      setTimeout(() => this.inputElement?.nativeElement.focus(), 100)
    }
  }

  onValueChange(value: string): void {
    this.valueChange.emit(value)
  }

  onEnter(): void {
    if (this.inputValue.trim()) {
      this.enterPressed.emit(this.inputValue.trim())
    }
  }

  /**
   * Public method to focus the input programmatically
   */
  focus(): void {
    this.inputElement?.nativeElement.focus()
  }
}
