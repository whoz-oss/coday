import { Component, OnInit, OnDestroy, inject } from '@angular/core'
import { CommonModule } from '@angular/common'
import { Router } from '@angular/router'
import { Subject } from 'rxjs'
import { takeUntil } from 'rxjs/operators'
import { ProjectApiService, ProjectInfo } from '../../core/services/project-api.service'
import { ProjectStateService } from '../../core/services/project-state.service'

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
export class ProjectSelectionComponent implements OnInit, OnDestroy {
  private destroy$ = new Subject<void>()

  // State
  projects: ProjectInfo[] = []
  isLoading: boolean = true
  error: string | null = null

  // Inject services
  private projectApi = inject(ProjectApiService)
  private projectState = inject(ProjectStateService)
  private router = inject(Router)

  ngOnInit(): void {
    this.loadProjects()
  }

  ngOnDestroy(): void {
    this.destroy$.next()
    this.destroy$.complete()
  }

  /**
   * Load available projects from API
   */
  private loadProjects(): void {
    this.isLoading = true
    this.error = null

    this.projectApi
      .listProjects()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (response) => {
          console.log('[PROJECT-SELECTION] Projects loaded:', response.projects.length)
          this.projects = response.projects
          this.isLoading = false

          // Auto-select if only one project available
          if (this.projects.length === 1 && this.projects[0]) {
            console.log('[PROJECT-SELECTION] Only one project, auto-selecting')
            this.selectProject(this.projects[0].name)
          }
        },
        error: (error) => {
          console.error('[PROJECT-SELECTION] Error loading projects:', error)
          this.error = 'Failed to load projects. Please try again.'
          this.isLoading = false
        },
      })
  }

  /**
   * Select a project and navigate to thread selection
   * @param projectName Project name to select
   */
  selectProject(projectName: string): void {
    console.log('[PROJECT-SELECTION] Selecting project:', projectName)

    // Update project state (fetches full project details)
    this.projectState
      .selectProject(projectName)
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (project) => {
          console.log('[PROJECT-SELECTION] Project state updated, navigating to:', project.name)
          // Navigate to thread selection for this project
          this.router.navigate(['/project', project.name])
        },
        error: (error) => {
          console.error('[PROJECT-SELECTION] Error selecting project:', error)
          this.error = `Failed to select project: ${error.message || 'Unknown error'}`
        },
      })
  }

  /**
   * Retry loading projects after error
   */
  retry(): void {
    this.loadProjects()
  }

  /**
   * Track by function for project list
   */
  trackByName(_index: number, project: ProjectInfo): string {
    return project.name
  }
}
