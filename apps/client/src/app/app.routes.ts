import { Route } from '@angular/router'
import { projectStateGuard } from './core/guards/project-state.guard'
import { threadStateGuard } from './core/guards/thread-state.guard'

export const appRoutes: Route[] = [
  {
    path: '',
    loadComponent: () =>
      import('./components/global-task-control/global-task-control.component').then(
        (m) => m.GlobalTaskControlComponent
      ),
  },
  {
    path: 'projects',
    loadComponent: () => import('./components/project-list/project-list.component').then((m) => m.ProjectListComponent),
  },
  {
    path: 'oauth/callback',
    loadComponent: () =>
      import('./components/oauth-callback/oauth-callback.component').then((m) => m.OAuthCallbackComponent),
  },
  {
    path: 'project/new',
    loadComponent: () => import('./components/project-new/project-new.component').then((m) => m.ProjectNewComponent),
  },
  {
    path: 'project/:projectName',
    loadComponent: () => import('./components/main-app/main-app.component').then((m) => m.MainAppComponent),
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
    path: 'project/:projectName/tasks',
    loadComponent: () => import('./components/task-control/task-control.component').then((m) => m.TaskControlComponent),
    canActivate: [projectStateGuard],
  },
  // Legacy redirect: /missions -> /tasks
  {
    path: 'project/:projectName/missions',
    redirectTo: 'project/:projectName/tasks',
  },
  {
    path: 'project/:projectName/thread/:threadId',
    loadComponent: () => import('./components/main-app/main-app.component').then((m) => m.MainAppComponent),
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
