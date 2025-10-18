import { inject } from '@angular/core'
import { ActivatedRouteSnapshot, CanActivateFn } from '@angular/router'
import { lastValueFrom, take } from 'rxjs'
import { ProjectStateService } from '../services/project-state.service'

/**
 * Route guard that ensures the project state is synchronized with route parameters.
 *
 * This guard is essential for deep links: when a user navigates directly to
 * /project/:projectName/... the ProjectStateService needs to be informed
 * of the selected project.
 *
 * Usage: Add to routes that require a selected project:
 * ```
 * {
 *   path: 'project/:projectName/thread/:threadId',
 *   component: MainAppComponent,
 *   canActivate: [projectStateGuard]
 * }
 * ```
 */
export const projectStateGuard: CanActivateFn = async (route: ActivatedRouteSnapshot) => {
  const projectState = inject(ProjectStateService)
  const projectName = route.params['projectName']

  // If no project name in route, allow navigation (will be handled by component)
  if (!projectName) {
    console.log('[PROJECT GUARD] no project id in route')
    return false
  }

  // Check if project is already selected
  if (projectState.getSelectedProjectId() === projectName) {
    return true
  }

  projectState.selectProject(projectName)
  const currentProject = await lastValueFrom(projectState.selectedProject$.pipe(take(1)))
  const validity = currentProject?.name === projectName

  if (!validity) {
    console.log('[PROJECT GUARD] current project not matching the route')
  }
  return validity
}
