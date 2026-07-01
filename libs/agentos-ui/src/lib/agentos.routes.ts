import { Route } from '@angular/router'
import { agentosReadyGuard } from './guards/agentos-ready.guard'

export const AGENTOS_ROUTES: Route[] = [
  {
    path: '',
    loadComponent: () =>
      import('./components/agentos-shell/agentos-shell.component').then((m) => m.AgentosShellComponent),
    children: [
      // --- Cases (own full-height shell with header + drawer) ---
      {
        path: ':namespaceId/cases',
        canActivate: [agentosReadyGuard],
        loadComponent: () => import('./components/case-shell/case-shell.component').then((m) => m.CaseShellComponent),
        children: [
          {
            path: '',
            loadComponent: () => import('./components/case-home/case-home.component').then((m) => m.CaseHomeComponent),
          },
          {
            path: ':caseId',
            loadComponent: () => import('./components/case-chat/case-chat.component').then((m) => m.CaseChatComponent),
          },
        ],
      },
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
              import('./components/integrations-all-scopes/integrations-all-scopes.component').then(
                (m) => m.IntegrationsAllScopesComponent
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
              import('./components/ai-providers-all-scopes/ai-providers-all-scopes.component').then(
                (m) => m.AiProvidersAllScopesComponent
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
            path: ':namespaceId/agent-configs/:agentConfigId/inspect',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/agent-config-inspect/agent-config-inspect.component').then(
                (m) => m.AgentConfigInspectComponent
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
          // --- Case Definitions ---
          {
            path: ':namespaceId/case-definitions/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/case-definition-form/case-definition-form.component').then(
                (m) => m.CaseDefinitionFormComponent
              ),
          },
          {
            path: ':namespaceId/case-definitions/:caseDefinitionId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/case-definition-form/case-definition-form.component').then(
                (m) => m.CaseDefinitionFormComponent
              ),
          },
          {
            path: ':namespaceId/case-definitions',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/case-definition-list/case-definition-list.component').then(
                (m) => m.CaseDefinitionListComponent
              ),
          },
          // --- Admin ---
          {
            path: 'admin/users/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () => import('./components/user-form/user-form.component').then((m) => m.UserFormComponent),
          },
          {
            path: 'admin/users/:userId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () => import('./components/user-form/user-form.component').then((m) => m.UserFormComponent),
          },
          {
            path: 'admin/users',
            canActivate: [agentosReadyGuard],
            loadComponent: () => import('./components/user-list/user-list.component').then((m) => m.UserListComponent),
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
