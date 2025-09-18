import { Component, Input, Output, EventEmitter, ViewChild, ElementRef, AfterViewInit, OnChanges, SimpleChanges, OnInit, OnDestroy, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { BehaviorSubject, Observable } from 'rxjs'
import { DomSanitizer, SafeHtml } from '@angular/platform-browser'
import { marked } from 'marked'

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
export class ChoiceSelectComponent implements AfterViewInit, OnChanges, OnInit, OnDestroy {
  @Input() options: ChoiceOption[] = []
  @Input() set labelHtml(value: string | SafeHtml) {
    // Si c'est déjà du SafeHtml, on l'utilise directement
    if (typeof value === 'object') {
      this.renderedLabelSubject.next(value as SafeHtml)
    } else {
      // Sinon on fait le rendu markdown
      this.renderLabelMarkdown(value as string)
    }
  }
  @Input() isVisible: boolean = false
  
  @Output() choiceSelected = new EventEmitter<string>()
  
  @ViewChild('choiceSelect') selectElement!: ElementRef<HTMLSelectElement>
  
  selectedValue: string = ''
  
  // Observable pour le rendu asynchrone du label
  private renderedLabelSubject = new BehaviorSubject<SafeHtml>('')
  renderedLabel$: Observable<SafeHtml> = this.renderedLabelSubject.asObservable()
  
  // Modern Angular dependency injection
  private sanitizer = inject(DomSanitizer)
  
  ngOnInit(): void {
    // Initialization logic if needed
  }
  
  ngOnDestroy(): void {
    this.renderedLabelSubject.complete()
  }
  
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
  
  /**
   * Rendre le markdown du label de manière asynchrone
   */
  private async renderLabelMarkdown(label: string): Promise<void> {
    if (!label) {
      this.renderedLabelSubject.next(this.sanitizer.bypassSecurityTrustHtml(''))
      return
    }
    
    try {
      const html = await marked.parse(label)
      this.renderedLabelSubject.next(this.sanitizer.bypassSecurityTrustHtml(html))
    } catch (error) {
      console.error('[CHOICE-SELECT] Error parsing label markdown:', error)
      // En cas d'erreur, afficher le texte brut
      this.renderedLabelSubject.next(this.sanitizer.bypassSecurityTrustHtml(label))
    }
  }
  
  // TODO: Connect to CodayEventHandler to handle ChoiceEvent
  // TODO: Add voice synthesis integration
}