import { Route } from '@angular/router'

/**
 * Cockpit V2 client-side routes.
 *
 * The shell component renders the shared header and a router outlet; feature
 * views are lazily loaded into that outlet.
 */
export const appRoutes: Route[] = [
  {
    path: '',
    loadComponent: () => import('./features/dashboard/dashboard.component').then((m) => m.DashboardComponent),
  },
  { path: '**', redirectTo: '' },
]
