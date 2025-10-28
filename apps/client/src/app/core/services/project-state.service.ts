import { inject, Injectable } from '@angular/core'
import { BehaviorSubject, combineLatest, of, shareReplay, switchMap, take } from 'rxjs'
import { map, tap } from 'rxjs/operators'
import { ProjectApiService } from './project-api.service'

/**
 * Service managing the currently selected project state.
 * Provides observables for reactive UI updates and fetches
 * full project details when a project is selected.
 */
@Injectable({
  providedIn: 'root',
})
export class ProjectStateService {
  private readonly selectedProjectIdSubject = new BehaviorSubject<string | null>(null)
  private readonly isLoadingSubject = new BehaviorSubject<boolean>(false)
  private readonly refreshTriggerSubject = new BehaviorSubject<void>(undefined)

  // Public observables
  isLoading$ = this.isLoadingSubject.asObservable()

  // Inject API service
  private readonly projectApi = inject(ProjectApiService)

  private readonly projectListCall = this.refreshTriggerSubject.pipe(
    switchMap(() => this.projectApi.listProjects()),
    shareReplay({ bufferSize: 1, refCount: true })
  )

  projectList$ = this.projectListCall.pipe(map((response) => response.projects))
  forcedProject$ = this.projectListCall.pipe(map((response) => response.forcedProject))

  selectedProject$ = combineLatest([this.selectedProjectIdSubject, this.forcedProject$]).pipe(
    switchMap(([projectId, forcedProject]) => {
      if (forcedProject) {
        return this.projectApi.getProject(forcedProject)
      } else if (!projectId) {
        return of(null)
      } else {
        return this.projectApi.getProject(projectId)
      }
    }),
    shareReplay({ bufferSize: 1, refCount: true })
  )

  /**
   * Select a project by name and fetch its details
   * @param projectName Project name to select
   * @returns Observable of project details
   */
  selectProject(projectName: string): void {
    this.forcedProject$
      .pipe(take(1))
      .subscribe((forcedProject) => this.selectedProjectIdSubject.next(forcedProject ?? projectName))
  }

  /**
   * Get the currently selected project name (synchronous)
   * @returns Current project name or null
   */
  getSelectedProjectId(): string | null {
    return this.selectedProjectIdSubject.value
  }

  /**
   * Clear the selected project
   */
  clearSelection(): void {
    console.log('[PROJECT-STATE] Clearing project selection')
    this.selectedProjectIdSubject.next(null)
  }

  /**
   * Create a new project
   * @param name Project name
   * @param path Project path
   * @returns Observable with success result
   */
  createProject(name: string, path: string) {
    this.isLoadingSubject.next(true)

    return this.projectApi.createProject(name, path).pipe(
      tap(() => {
        this.isLoadingSubject.next(false)
        // Trigger project list refresh
        this.refreshTriggerSubject.next()
      })
    )
  }
}
