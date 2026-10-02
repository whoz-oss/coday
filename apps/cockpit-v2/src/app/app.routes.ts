import { Routes } from '@angular/router'

/**
 * Cockpit V2 client-side routes.
 *
 * Only the three top-level screens are routed for now, each pointing at a
 * minimal placeholder component. The full port of the mockups'
 * `sandboxes-page`, `session-page` and `history-page` components (with their
 * `agent-timeline`, `event-log`, `sandbox-card`, … children) is reserved for the
 * next waves.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'sandboxes' },
  {
    path: 'sandboxes',
    loadComponent: () =>
      import('./features/placeholders/sandboxes-placeholder.component').then((m) => m.SandboxesPlaceholderComponent),
  },
  {
    path: 'sessions/:runId',
    loadComponent: () =>
      import('./features/placeholders/session-placeholder.component').then((m) => m.SessionPlaceholderComponent),
  },
  {
    path: 'historique',
    loadComponent: () => import('./features/history/history-page.component').then((m) => m.HistoryPageComponent),
  },
  { path: '**', redirectTo: 'sandboxes' },
]
