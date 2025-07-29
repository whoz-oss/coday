import { Component, Input, Output, EventEmitter } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'

export interface ChoiceOption {
  value: string
  label: string
}

@Component({
  selector: 'app-choice-select',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './choice-select.component.html',
  styleUrl: './choice-select.component.scss'
})
export class ChoiceSelectComponent {
  @Input() options: ChoiceOption[] = []
  @Input() labelHtml: string = ''
  @Input() isVisible: boolean = false
  
  @Output() choiceSelected = new EventEmitter<string>()
  
  selectedValue: string = ''
  
  onSubmit() {
    if (this.selectedValue) {
      this.choiceSelected.emit(this.selectedValue)
      this.isVisible = false
    }
  }
  
  // TODO: Connect to CodayEventHandler to handle ChoiceEvent
  // TODO: Add voice synthesis integration
  // TODO: Add markdown parsing for labels
}