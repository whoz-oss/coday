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
    loadComponent: () =>
      import('./features/placeholders/sandboxes-placeholder.component').then((m) => m.SandboxesPlaceholderComponent),
  },
  {
    path: 'sessions/:runId',
    loadComponent: () => import('./features/session/session-page.component').then((m) => m.SessionPageComponent),
  },
  {
    path: 'historique',
    loadComponent: () =>
      import('./features/placeholders/history-placeholder.component').then((m) => m.HistoryPlaceholderComponent),
  },
  { path: '**', redirectTo: 'sandboxes' },
]
