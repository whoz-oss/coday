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
          // --- Namespace CRUD ---
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
          // --- Cases ---
          {
            path: ':namespaceId/cases/:caseId',
            canActivate: [agentosReadyGuard],
            loadComponent: () => import('./components/case-chat/case-chat.component').then((m) => m.CaseChatComponent),
          },
          {
            path: ':namespaceId/cases',
            canActivate: [agentosReadyGuard],
            loadComponent: () => import('./components/case-list/case-list.component').then((m) => m.CaseListComponent),
          },
          // --- Integrations ---
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
          // --- AI Providers ---
          {
            path: ':namespaceId/ai-providers/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/ai-provider-form/ai-provider-form.component').then((m) => m.AiProviderFormComponent),
          },
          {
            path: ':namespaceId/ai-providers/:aiProviderId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/ai-provider-form/ai-provider-form.component').then((m) => m.AiProviderFormComponent),
          },
          {
            path: ':namespaceId/ai-providers',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/namespace-ai-providers/namespace-ai-providers.component').then(
                (m) => m.NamespaceAiProvidersComponent
              ),
          },
          // --- AI models ---
          {
            path: ':namespaceId/ai-models/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/ai-model-form/ai-model-form.component').then((m) => m.AiModelFormComponent),
          },
          {
            path: ':namespaceId/ai-models/:modelId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/ai-model-form/ai-model-form.component').then((m) => m.AiModelFormComponent),
          },
          {
            path: ':namespaceId/ai-models',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/namespace-ai-models/namespace-ai-models.component').then(
                (m) => m.NamespaceAiModelsComponent
              ),
          },
          // --- Agent Configs ---
          {
            path: ':namespaceId/agent-configs/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/agent-config-form/agent-config-form.component').then(
                (m) => m.AgentConfigFormComponent
              ),
          },
          {
            path: ':namespaceId/agent-configs/:agentConfigId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/agent-config-form/agent-config-form.component').then(
                (m) => m.AgentConfigFormComponent
              ),
          },
          {
            path: ':namespaceId/agent-configs',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/namespace-agent-configs/namespace-agent-configs.component').then(
                (m) => m.NamespaceAgentConfigsComponent
              ),
          },
          // --- User profile ---
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
