import { Component, Input, Output, EventEmitter, OnChanges } from '@angular/core'
import { CommonModule } from '@angular/common'
import { FormsModule } from '@angular/forms'

export type ConfigType = 'user' | 'project'

/**
 * Pure presentation component for JSON editing
 * Does not handle API calls - parent component is responsible for data loading/saving
 * 
 * TODO: Migrate to Angular Signals in future iteration:
 * - Replace @Input() with input() signal inputs
 * - Replace @Output() with output() signal outputs
 * - See: https://angular.dev/guide/signals/inputs
 */
@Component({
  selector: 'app-json-editor',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './json-editor.component.html',
  styleUrl: './json-editor.component.scss'
})
export class JsonEditorComponent implements OnChanges {
  // TODO: Migrate to signal inputs when ready
  // configType = input<ConfigType>('user')
  // projectName = input<string | undefined>()
  // isOpen = input(false)
  // initialContent = input('')
  // isLoading = input(false)
  // isSaving = input(false)
  
  @Input() configType: ConfigType = 'user'
  @Input() projectName?: string
  @Input() isOpen = false
  @Input() initialContent = ''
  @Input() isLoading = false
  @Input() isSaving = false
  
  // TODO: Migrate to signal outputs when ready
  // isOpenChange = output<boolean>()
  // save = output<any>()
  // closeEditor = output<void>()
  
  @Output() isOpenChange = new EventEmitter<boolean>()
  @Output() save = new EventEmitter<any>()
  @Output() closeEditor = new EventEmitter<void>()

  jsonContent = ''
  errorMessage = ''

  /**
   * Update local content when initialContent changes
   */
  ngOnChanges(): void {
    if (this.initialContent) {
      this.jsonContent = this.initialContent
    }
  }

  /**
   * Validate and emit parsed JSON
   */
  onSave(): void {
    this.errorMessage = ''

    // Validate JSON syntax
    let parsedConfig: any
    try {
      parsedConfig = JSON.parse(this.jsonContent)
    } catch (error) {
      this.errorMessage = `Invalid JSON syntax: ${error instanceof Error ? error.message : 'Unknown error'}`
      return
    }

    // Emit parsed JSON object
    this.save.emit(parsedConfig)
  }

  /**
   * Cancel and close editor
   */
  onCancel(): void {
    this.errorMessage = ''
    this.closeEditor.emit()
  }

  /**
   * Get title based on config type
   */
  getTitle(): string {
    if (this.configType === 'user') {
      return 'User Configuration'
    }
    return `Project Configuration: ${this.projectName || 'Unknown'}`
  }
}
