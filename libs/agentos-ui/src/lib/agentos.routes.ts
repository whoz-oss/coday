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
            path: 'namespaces/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/namespace-form/namespace-form.component').then((m) => m.NamespaceFormComponent),
          },
          {
            path: 'namespaces/:namespaceId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/namespace-form/namespace-form.component').then((m) => m.NamespaceFormComponent),
          },
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
            path: ':namespaceId/integrations/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/integration-form/integration-form.component').then(
                (m) => m.IntegrationFormComponent
              ),
          },
          {
            path: ':namespaceId/integrations/:integrationId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/integration-form/integration-form.component').then(
                (m) => m.IntegrationFormComponent
              ),
          },
          {
            path: ':namespaceId/integrations',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/namespace-integrations/namespace-integrations.component').then(
                (m) => m.NamespaceIntegrationsComponent
              ),
          },
          {
            path: ':namespaceId/llm-configs/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/namespace-llm-configs/namespace-llm-configs.component').then(
                (m) => m.NamespaceLlmConfigsComponent
              ),
          },
          {
            path: ':namespaceId/llm-configs/:llmConfigId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/namespace-llm-configs/namespace-llm-configs.component').then(
                (m) => m.NamespaceLlmConfigsComponent
              ),
          },
          {
            path: ':namespaceId/llm-configs',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/namespace-llm-configs/namespace-llm-configs.component').then(
                (m) => m.NamespaceLlmConfigsComponent
              ),
          },
          {
            path: ':namespaceId/llm-models/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/namespace-llm-models/namespace-llm-models.component').then(
                (m) => m.NamespaceLlmModelsComponent
              ),
          },
          {
            path: ':namespaceId/llm-models/:modelId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/namespace-llm-models/namespace-llm-models.component').then(
                (m) => m.NamespaceLlmModelsComponent
              ),
          },
          {
            path: ':namespaceId/llm-models',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/namespace-llm-models/namespace-llm-models.component').then(
                (m) => m.NamespaceLlmModelsComponent
              ),
          },
          // TODO: replace /new and /:id/edit routes with LlmConfigFormComponent once implemented
          {
            path: ':namespaceId/llm-configs',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/namespace-llm-configs/namespace-llm-configs.component').then(
                (m) => m.NamespaceLlmConfigsComponent
              ),
          },
          // TODO: replace /new and /:id/edit routes with LlmModelConfigFormComponent once implemented
          {
            path: ':namespaceId/llm-models',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/namespace-llm-models/namespace-llm-models.component').then(
                (m) => m.NamespaceLlmModelsComponent
              ),
          },
          {
            path: ':namespaceId/cases/:caseId',
            canActivate: [agentosReadyGuard],
            loadComponent: () => import('./components/case-chat/case-chat.component').then((m) => m.CaseChatComponent),
          },
          {
            path: 'me',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/user-profile/user-profile.component').then((m) => m.UserProfileComponent),
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
