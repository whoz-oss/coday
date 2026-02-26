import { Route } from '@angular/router'

export const AGENTOS_ROUTES: Route[] = [
  {
    path: '',
    loadComponent: () =>
      import('./components/hello-agentos/hello-agentos.component').then((m) => m.HelloAgentosComponent),
  },
]
