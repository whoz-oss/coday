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
  template: `
    <form 
      *ngIf="isVisible" 
      class="choice-form" 
      (ngSubmit)="onSubmit()"
    >
      <label class="choice-label" [innerHTML]="labelHtml"></label>
      
      <select 
        class="choice-select" 
        [(ngModel)]="selectedValue"
        name="choice"
        required
        #choiceSelect
      >
        <option 
          *ngFor="let option of options" 
          [value]="option.value"
        >
          {{ option.label }}
        </option>
      </select>
      
      <button type="submit" class="submit-btn">
        Submit Choice
      </button>
    </form>
  `,
  styles: [`
    .choice-form {
      padding: 1rem;
      background: #f8fafc;
      border: 1px solid #e2e8f0;
      border-radius: 8px;
      margin: 1rem 0;
    }
    
    .choice-label {
      display: block;
      margin-bottom: 0.5rem;
      font-weight: 500;
      color: #374151;
    }
    
    .choice-select {
      width: 100%;
      padding: 0.5rem;
      border: 1px solid #d1d5db;
      border-radius: 4px;
      margin-bottom: 1rem;
    }
    
    .submit-btn {
      background: #3b82f6;
      color: white;
      padding: 0.5rem 1rem;
      border: none;
      border-radius: 4px;
      cursor: pointer;
    }
    
    .submit-btn:hover {
      background: #2563eb;
    }
  `]
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