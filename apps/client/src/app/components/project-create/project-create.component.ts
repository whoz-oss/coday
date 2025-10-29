import { Component, EventEmitter, Input, Output, ViewChild } from '@angular/core'
import { CommonModule } from '@angular/common'
import { TextInputComponent } from '../text-input/text-input.component'

/**
 * Component for creating a new project.
 * Provides a form with project name and path inputs.
 */
@Component({
  selector: 'app-project-create',
  standalone: true,
  imports: [CommonModule, TextInputComponent],
  templateUrl: './project-create.component.html',
  styleUrl: './project-create.component.scss',
})
export class ProjectCreateComponent {
  @Input() isCreating: boolean = false
  @Output() create = new EventEmitter<{ name: string; path: string }>()
  @Output() cancelled = new EventEmitter<void>()

  @ViewChild('nameInput') nameInput!: TextInputComponent
  @ViewChild('pathInput') pathInput!: TextInputComponent

  projectName: string = ''
  projectPath: string = ''
  errorMessage: string = ''

  isValid(): boolean {
    return this.projectName.trim() !== '' && this.projectPath.trim() !== ''
  }

  onNameEnter(): void {
    // When Enter is pressed on name field, focus path field
    if (this.projectName.trim()) {
      setTimeout(() => this.pathInput?.focus(), 50)
    }
  }

  onCreate(): void {
    if (this.isValid()) {
      this.errorMessage = ''
      this.create.emit({
        name: this.projectName.trim(),
        path: this.projectPath.trim(),
      })
    } else {
      this.errorMessage = 'Both name and path are required'
    }
  }

  onCancel(): void {
    this.cancelled.emit()
  }
}
