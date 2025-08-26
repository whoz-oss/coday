import { Component, Input, Output, EventEmitter, ViewChild, ElementRef, AfterViewInit, OnChanges, SimpleChanges } from '@angular/core'
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
export class ChoiceSelectComponent implements AfterViewInit, OnChanges {
  @Input() options: ChoiceOption[] = []
  @Input() labelHtml: string = ''
  @Input() isVisible: boolean = false
  
  @Output() choiceSelected = new EventEmitter<string>()
  
  @ViewChild('choiceSelect') selectElement!: ElementRef<HTMLSelectElement>
  
  selectedValue: string = ''
  
  ngAfterViewInit(): void {
    // Focus automatique quand le composant devient visible
    if (this.isVisible && this.selectElement) {
      setTimeout(() => this.focusSelect(), 100)
    }
  }
  
  ngOnChanges(changes: SimpleChanges): void {
    // Focus automatique quand isVisible passe à true
    if (changes['isVisible']) {
      console.log('[CHOICE-SELECT] isVisible changed:', changes['isVisible'].previousValue, '->', changes['isVisible'].currentValue)
      if (changes['isVisible'].currentValue && !changes['isVisible'].previousValue) {
        console.log('[CHOICE-SELECT] Component becoming visible, setting focus...')
        setTimeout(() => this.focusSelect(), 150) // Délai un peu plus long pour l'animation
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
    if (this.selectedValue) {
      console.log('[CHOICE-SELECT] Choice selected:', this.selectedValue)
      this.choiceSelected.emit(this.selectedValue)
      this.selectedValue = '' // Reset pour la prochaine utilisation
    } else {
      console.warn('[CHOICE-SELECT] No choice selected')
    }
  }
  
  onSelectChange() {
    console.log('[CHOICE-SELECT] Selection changed to:', this.selectedValue)
  }
  
  // TODO: Connect to CodayEventHandler to handle ChoiceEvent
  // TODO: Add voice synthesis integration
  // TODO: Add markdown parsing for labels
}