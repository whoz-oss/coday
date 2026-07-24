import { toSignal } from '@angular/core/rxjs-interop'
import { Route } from '@angular/router'
// Type-only import: erased at build time, so it adds no runtime dependency on the lazy
// agentos-ui chunk and keeps the eager bundle free of agentos-ui code.
import type { ThemePort } from '@whoz-oss/agentos-ui'
import { projectStateGuard } from './core/guards/project-state.guard'
import { threadStateGuard } from './core/guards/thread-state.guard'
import { ThemeService } from './core/services/theme.service'

export const appRoutes: Route[] = [
  {
    path: '',
    redirectTo: 'agentos',
    pathMatch: 'full',
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
    // Bridge the client ThemeService to agentos-ui's signal-based THEME_PORT via a small adapter,
    // so a single service still owns document[data-theme] across the legacy client and the AgentOS
    // UI. The adapter lives here (not on ThemeService) to keep that long-lived host service free of
    // agentos-only API. agentos-ui is lazy — its runtime token comes from the dynamic import (a
    // static import would bloat the eager bundle); ThemePort is a type-only import (erased at build).
    loadChildren: async () => {
      const { AGENTOS_ROUTES, THEME_PORT } = await import('@whoz-oss/agentos-ui')
      return [
        {
          path: '',
          providers: [
            {
              provide: THEME_PORT,
              // Built lazily on entering /agentos — i.e. after app startup — so getCurrentTheme()
              // already returns the resolved persisted theme (no initial flash).
              useFactory: (theme: ThemeService): ThemePort => ({
                theme: toSignal(theme.currentTheme$, { initialValue: theme.getCurrentTheme() }),
                setTheme: (mode) => theme.setTheme(mode),
              }),
              deps: [ThemeService],
            },
          ],
          children: AGENTOS_ROUTES,
        },
      ]
    },
  },
  { path: '**', redirectTo: 'agentos' },
]
