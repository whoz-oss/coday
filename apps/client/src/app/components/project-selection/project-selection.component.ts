import { Component, computed, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { Router } from '@angular/router'
import { filter, take } from 'rxjs'
import { ProjectStateService } from '../../core/services/project-state.service'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'
import { ChoiceSelectComponent, ChoiceOption } from '../choice-select/choice-select.component'
import { WelcomeMessageComponent } from '../welcome-message/welcome-message.component'

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
  imports: [CommonModule, ChoiceSelectComponent, WelcomeMessageComponent],
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

  // Transform projects to choice options
  projectOptions = computed<ChoiceOption[]>(() => {
    const projectList = this.projects()
    if (!projectList) return []

    return projectList.map((project) => ({
      value: project.name,
      label: project.name,
    }))
  })

  constructor() {
    // setup re-direction to the selected project as soon as selection comes
    // Only redirect if we're still on the project selection page
    this.projectStateService.selectedProject$
      .pipe(
        takeUntilDestroyed(),
        filter((selection) => !!selection),
        filter(() => {
          // Check only the path, ignoring query parameters
          const path = this.router.url.split('?')[0]
          return path === '/'
        }),
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
}
