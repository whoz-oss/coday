import { Component, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { Router } from '@angular/router'
import { filter, take } from 'rxjs'
import { ProjectInfo } from '../../core/services/project-api.service'
import { ProjectStateService } from '../../core/services/project-state.service'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'

/**
 * Component for selecting a project from available projects.
 * This is the entry point of the application workflow.
 *
 * After a project is selected, navigates to /project/:projectName
 * to display the thread selection interface.
 */
@Component({
  selector: 'app-project-selection',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './project-selection.component.html',
  styleUrl: './project-selection.component.scss',
})
export class ProjectSelectionComponent {
  // State
  isLoading: boolean = false
  error: string | null = null

  // Inject services
  private readonly router = inject(Router)
  private readonly projectStateService = inject(ProjectStateService)
  projects = toSignal(this.projectStateService.projectList$)

  constructor() {
    // setup re-direction to the selected project as soon as selection comes
    this.projectStateService.selectedProject$
      .pipe(
        takeUntilDestroyed(),
        filter((selection) => !!selection),
        take(1)
      )
      .subscribe((selection) => {
        console.log(`🐼 navigate to ${selection.name}`)
        this.router.navigate(['/project', selection.name])
      })
  }

  /**
   * Select a project and navigate to thread selection
   * @param projectName Project name to select
   */
  selectProject(projectName: string): void {
    this.projectStateService.selectProject(projectName)
  }

  /**
   * Track by function for project list
   */
  trackByName(_index: number, project: ProjectInfo): string {
    return project.name
  }
}
