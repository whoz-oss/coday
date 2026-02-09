import { Component, computed, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { Router } from '@angular/router'
import { filter, take } from 'rxjs'
import { ProjectStateService } from '../../core/services/project-state.service'
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop'
import { ChoiceSelectComponent, ChoiceOption } from '../choice-select/choice-select.component'
import { WelcomeMessageComponent } from '../welcome-message'
import { ProjectCreateComponent } from '../project-create/project-create.component'
import { MatButton } from '@angular/material/button'

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
  imports: [CommonModule, ChoiceSelectComponent, WelcomeMessageComponent, ProjectCreateComponent, MatButton],
  templateUrl: './project-selection.component.html',
  styleUrl: './project-selection.component.scss',
})
export class ProjectSelectionComponent {
  // State
  isLoading: boolean = false
  error: string | null = null
  showCreateForm: boolean = false
  isCreating: boolean = false

  // Inject services
  private readonly router = inject(Router)
  private readonly projectStateService = inject(ProjectStateService)
  projects = toSignal(this.projectStateService.projectList$)
  forcedProject = toSignal(this.projectStateService.forcedProject$)

  // Transform projects to choice options
  // Non-volatile projects first, then volatile projects at the bottom, then "New project..." option
  projectOptions = computed<ChoiceOption[]>(() => {
    const projectList = this.projects()
    if (!projectList) return []

    // Separate volatile and non-volatile projects
    const nonVolatile = projectList.filter((p) => !p.volatile)
    const volatile = projectList.filter((p) => p.volatile)

    // Combine: non-volatile first, then volatile
    const sorted = [...nonVolatile, ...volatile]

    const options: ChoiceOption[] = sorted.map((project) => ({
      value: project.name,
      label: project.volatile ? `${project.name} 🔸temp` : project.name,
    }))

    // Add "New project..." option at the end
    if (projectList.length > 0) {
      options.push({ value: '__new_project__', label: 'New project...' })
    }

    return options
  })

  // Computed to determine if create button should be shown
  showCreateButton = computed(() => {
    const forced = this.forcedProject()
    return !forced // Button visible only if no forced project
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
        this.router.navigate(['project', selection.name])
      })
  }

  /**
   * Select a project and navigate to thread selection
   * @param projectName Project name to select
   */
  selectProject(projectName: string): void {
    // Handle special "New project..." option
    if (projectName === '__new_project__') {
      this.openCreateForm()
      return
    }

    this.projectStateService.selectProject(projectName)
  }

  /**
   * Open the project creation form
   */
  openCreateForm(): void {
    this.showCreateForm = true
  }

  /**
   * Close the project creation form
   */
  closeCreateForm(): void {
    this.showCreateForm = false
    this.isCreating = false
  }

  /**
   * Handle project creation
   * @param data Project name and path
   */
  onCreateProject(data: { name: string; path: string }): void {
    this.isCreating = true
    this.error = null

    this.projectStateService.createProject(data.name, data.path).subscribe({
      next: (response) => {
        console.log('[PROJECT-SELECTION] Project created:', response.message)
        this.isCreating = false
        this.closeCreateForm()
        // Auto-select the newly created project
        this.selectProject(data.name)
      },
      error: (err) => {
        console.error('[PROJECT-SELECTION] Error creating project:', err)
        this.error = err.error?.error || 'Failed to create project'
        this.isCreating = false
      },
    })
  }
}
