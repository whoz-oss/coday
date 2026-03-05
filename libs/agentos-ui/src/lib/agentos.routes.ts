import { Route } from '@angular/router'
import { agentosReadyGuard } from './guards/agentos-ready.guard'

export const AGENTOS_ROUTES: Route[] = [
  {
    path: '',
    loadComponent: () =>
      import('./components/agentos-shell/agentos-shell.component').then((m) => m.AgentosShellComponent),
    children: [
      {
        path: '',
        loadComponent: () => import('./components/layout/layout.component').then((m) => m.LayoutComponent),
        children: [
          {
            path: 'namespaces',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/namespace-list/namespace-list.component').then((m) => m.NamespaceListComponent),
          },
          {
            path: ':namespaceId/cases',
            canActivate: [agentosReadyGuard],
            loadComponent: () => import('./components/case-list/case-list.component').then((m) => m.CaseListComponent),
          },
          {
            path: ':namespaceId/cases/:caseId',
            canActivate: [agentosReadyGuard],
            loadComponent: () => import('./components/case-chat/case-chat.component').then((m) => m.CaseChatComponent),
          },
          {
            path: '',
            redirectTo: 'namespaces',
            pathMatch: 'full',
          },
        ],
      },
    ],
  },
]
