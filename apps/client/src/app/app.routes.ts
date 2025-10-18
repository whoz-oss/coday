import { Route } from '@angular/router'
import { ProjectSelectionComponent } from './components/project-selection/project-selection.component'
import { ThreadSelectionComponent } from './components/thread-selection/thread-selection.component'
import { MainAppComponent } from './components/main-app/main-app.component'

export const appRoutes: Route[] = [
  { path: '', component: ProjectSelectionComponent },
  { path: 'project/:projectName', component: ThreadSelectionComponent },
  { path: 'project/:projectName/thread/:threadId', component: MainAppComponent },
  { path: '**', redirectTo: '' },
]
