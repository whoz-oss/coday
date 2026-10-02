import { Routes } from '@angular/router'

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'sandboxes' },
  {
    path: 'sandboxes',
    loadComponent: () => import('./features/sandboxes/sandboxes-page.component').then((m) => m.SandboxesPageComponent),
  },
  {
    path: 'sessions/:runId',
    loadComponent: () => import('./features/session/session-page.component').then((m) => m.SessionPageComponent),
  },
  {
    path: 'historique',
    loadComponent: () => import('./features/history/history-page.component').then((m) => m.HistoryPageComponent),
  },
  { path: '**', redirectTo: 'sandboxes' },
]
