import { Route } from '@angular/router'
import { ProjectListComponent } from './components/project-list/project-list.component'
import { MainAppComponent } from './components/main-app/main-app.component'
import { OAuthCallbackComponent } from './components/oauth-callback/oauth-callback.component'
import { projectStateGuard } from './core/guards/project-state.guard'
import { threadStateGuard } from './core/guards/thread-state.guard'

export const appRoutes: Route[] = [
  { path: '', component: ProjectListComponent },
  { path: 'oauth/callback', component: OAuthCallbackComponent },
  {
    path: 'project/new',
    loadComponent: () => import('./components/project-new/project-new.component').then((m) => m.ProjectNewComponent),
  },
  {
    path: 'project/:projectName',
    component: MainAppComponent,
    canActivate: [projectStateGuard],
  },
  {
    path: 'project/:projectName/prompts',
    loadComponent: () => import('./components/prompt-list/prompt-list.component').then((m) => m.PromptListComponent),
    canActivate: [projectStateGuard],
  },
  {
    path: 'project/:projectName/schedulers',
    loadComponent: () =>
      import('./components/scheduler-list/scheduler-list.component').then((m) => m.SchedulerListComponent),
    canActivate: [projectStateGuard],
  },
  {
    path: 'project/:projectName/agents',
    loadComponent: () => import('./components/agent-list/agent-list.component').then((m) => m.AgentListComponent),
    canActivate: [projectStateGuard],
  },
  {
    path: 'project/:projectName/thread/:threadId',
    component: MainAppComponent,
    canActivate: [projectStateGuard, threadStateGuard],
  },
  {
    path: 'token-usage',
    loadComponent: () => import('./components/token-usage/token-usage.component').then((m) => m.TokenUsageComponent),
  },
  {
    path: 'agentos',
    loadChildren: () => import('@whoz-oss/agentos-ui').then((m) => m.AGENTOS_ROUTES),
  },
  { path: '**', redirectTo: '' },
]
