import { Routes } from '@angular/router'

/**
 * Cockpit V2 client-side routes.
 *
 * The sandboxes and history screens still point at minimal placeholder
 * components; the session screen is fully ported (session-page with its
 * agent-timeline and event-log children). The remaining ports of the mockups'
 * `sandboxes-page` and `history-page` components are reserved for the next
 * waves.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'sandboxes' },
  {
    path: 'sandboxes',
    loadComponent: () => import('./features/sandboxes/sandboxes-page.component').then((m) => m.SandboxesPageComponent),
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
    loadComponent: () => import('./features/admin/admin-page.component').then((m) => m.AdminPageComponent),
  },
  { path: 'reglages', redirectTo: 'workflows', pathMatch: 'full' },
  { path: '**', redirectTo: 'sandboxes' },
]
