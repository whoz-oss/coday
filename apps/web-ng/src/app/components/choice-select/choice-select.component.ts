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
      background: var(--color-bg-secondary, #f8fafc);
      border: 1px solid var(--color-border, #e2e8f0);
      border-radius: 8px;
      margin: 1rem 0;
      box-shadow: var(--color-shadow, rgba(0, 0, 0, 0.1)) 0 2px 4px;
    }
    
    .choice-label {
      display: block;
      margin-bottom: 0.5rem;
      font-weight: 500;
      color: var(--color-text, #374151);
    }
    
    .choice-select {
      width: 100%;
      padding: 0.75rem;
      border: 1px solid var(--color-border, #aeaeae);
      border-radius: 4px;
      margin-bottom: 1rem;
      background: var(--color-input-bg, #ffffff);
      color: var(--color-text, #282a36);
      font-size: 1rem;
      
      &:focus {
        outline: none;
        border-color: var(--color-primary, #7064fb);
        box-shadow: 0 0 0 2px rgba(112, 100, 251, 0.1);
      }
    }
    
    .submit-btn {
      background: var(--color-primary, #7064fb);
      color: var(--color-text-inverse, #ffffff);
      padding: 0.75rem 1.5rem;
      border: none;
      border-radius: 4px;
      cursor: pointer;
      font-weight: 500;
      transition: background-color 0.2s ease;
      
      &:hover {
        background: var(--color-primary-hover, #ff79c6);
      }
      
      &:disabled {
        background: var(--color-text-secondary, #6272a4);
        cursor: not-allowed;
      }
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