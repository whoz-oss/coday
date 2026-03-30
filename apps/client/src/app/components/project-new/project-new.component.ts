import { Component, inject } from '@angular/core'
import { Router } from '@angular/router'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { ProjectStateService } from '../../core/services/project-state.service'
import { TextInputComponent } from '../text-input/text-input.component'

/**
 * Full-page component for creating a new project.
 *
 * Route: /project/new
 *
 * On success, auto-selects the created project and navigates to it.
 * Cancel navigates back to the project selection page.
 */
@Component({
  selector: 'app-project-new',
  standalone: true,
  imports: [MatButtonModule, MatIconModule, TextInputComponent],
  templateUrl: './project-new.component.html',
  styleUrl: './project-new.component.scss',
})
export class ProjectNewComponent {
  private readonly router = inject(Router)
  private readonly projectStateService = inject(ProjectStateService)

  protected projectName: string = ''
  protected projectPath: string = ''
  protected isCreating: boolean = false
  protected errorMessage: string | null = null

  protected isValid(): boolean {
    return this.projectName.trim() !== '' && this.projectPath.trim() !== ''
  }

  protected onCreate(): void {
    if (!this.isValid()) {
      this.errorMessage = 'Both name and path are required'
      return
    }

    this.isCreating = true
    this.errorMessage = null

    this.projectStateService.createProject(this.projectName.trim(), this.projectPath.trim()).subscribe({
      next: () => {
        const name = this.projectName.trim()
        this.isCreating = false
        this.projectStateService.selectProject(name)
        this.router.navigate(['project', name])
      },
      error: (err) => {
        this.errorMessage = err.error?.error ?? 'Failed to create project'
        this.isCreating = false
      },
    })
  }

  protected onCancel(): void {
    this.router.navigate(['/'])
  }
}
