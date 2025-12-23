import { Route } from '@angular/router'
import { ProjectSelectionComponent } from './components/project-selection/project-selection.component'
import { MainAppComponent } from './components/main-app/main-app.component'
import { OAuthCallbackComponent } from './components/oauth-callback/oauth-callback.component'
import { projectStateGuard } from './core/guards/project-state.guard'
import { threadStateGuard } from './core/guards/thread-state.guard'

export const appRoutes: Route[] = [
  { path: '', component: ProjectSelectionComponent },
  { path: 'oauth/callback', component: OAuthCallbackComponent },
  {
    path: 'project/:projectName',
    component: MainAppComponent,
    canActivate: [projectStateGuard],
  },
  {
    path: 'project/:projectName/thread/:threadId',
    component: MainAppComponent,
    canActivate: [projectStateGuard, threadStateGuard],
  },
  { path: '**', redirectTo: '' },
]
