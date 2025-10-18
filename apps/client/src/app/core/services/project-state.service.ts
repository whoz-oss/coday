import { Injectable, inject } from '@angular/core'
import { BehaviorSubject, Observable } from 'rxjs'
import { tap } from 'rxjs/operators'
import { ProjectApiService, ProjectDetails } from './project-api.service'

/**
 * Service managing the currently selected project state.
 * Provides observables for reactive UI updates and fetches
 * full project details when a project is selected.
 */
@Injectable({
  providedIn: 'root',
})
export class ProjectStateService {
  private selectedProjectSubject = new BehaviorSubject<ProjectDetails | null>(null)
  private isLoadingSubject = new BehaviorSubject<boolean>(false)

  // Public observables
  selectedProject$ = this.selectedProjectSubject.asObservable()
  isLoading$ = this.isLoadingSubject.asObservable()

  // Inject API service
  private projectApi = inject(ProjectApiService)

  /**
   * Select a project by name and fetch its details
   * @param projectName Project name to select
   * @returns Observable of project details
   */
  selectProject(projectName: string): Observable<ProjectDetails> {
    this.isLoadingSubject.next(true)

    return this.projectApi.getProject(projectName).pipe(
      tap({
        next: (project) => {
          console.log('[PROJECT-STATE] Project loaded:', project.name)
          this.selectedProjectSubject.next(project)
          this.isLoadingSubject.next(false)
        },
        error: (error) => {
          console.error('[PROJECT-STATE] Error loading project:', error)
          this.isLoadingSubject.next(false)
          // Keep previous selection on error
        },
      })
    )
  }

  /**
   * Get the currently selected project (synchronous)
   * @returns Current project or null
   */
  getSelectedProject(): ProjectDetails | null {
    return this.selectedProjectSubject.value
  }

  /**
   * Get the currently selected project name (synchronous)
   * @returns Current project name or null
   */
  getSelectedProjectName(): string | null {
    return this.selectedProjectSubject.value?.name || null
  }

  /**
   * Clear the selected project
   */
  clearSelection(): void {
    console.log('[PROJECT-STATE] Clearing project selection')
    this.selectedProjectSubject.next(null)
  }

  /**
   * Check if a project is currently selected
   */
  hasSelection(): boolean {
    return this.selectedProjectSubject.value !== null
  }
}
