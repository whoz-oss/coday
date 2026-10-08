import { Routes } from '@angular/router'

/**
 * Cockpit V2 client-side routes.
 *
 * The workstreams screen groups the real Factory runs by namespace; the session
 * screen is fully ported (session-page with its agent-timeline and event-log
 * children). The legacy `/sandboxes` path is kept as a redirect for bookmarks.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'workstreams' },
  { path: 'sandboxes', redirectTo: 'workstreams', pathMatch: 'full' },
  {
    path: 'workstreams',
    loadComponent: () =>
      import('./features/workstreams/workstreams-page.component').then((m) => m.WorkstreamsPageComponent),
  },
  {
    path: 'lancer',
    loadComponent: () => import('./features/launch/launch-page.component').then((m) => m.LaunchPageComponent),
  },
  {
    path: 'sessions/:runId',
    loadComponent: () => import('./features/session/session-page.component').then((m) => m.SessionPageComponent),
  },
  {
    path: 'historique',
    loadComponent: () => import('./features/history/history-page.component').then((m) => m.HistoryPageComponent),
  },
  {
    path: 'workflows',
    loadComponent: () => import('./features/workflows/workflows-page.component').then((m) => m.WorkflowsPageComponent),
  },
  {
    path: 'workflows/:type/:version',
    loadComponent: () =>
      import('./features/workflows/workflow-detail-page.component').then((m) => m.WorkflowDetailPageComponent),
  },
  {
    path: 'reglages',
    loadComponent: () => import('./features/admin/admin-page.component').then((m) => m.AdminPageComponent),
  },
  { path: '**', redirectTo: 'workstreams' },
]
