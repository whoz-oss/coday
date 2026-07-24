import { Route } from '@angular/router'
import { agentosReadyGuard } from './guards/agentos-ready.guard'

export const AGENTOS_ROUTES: Route[] = [
  {
    path: '',
    loadComponent: () =>
      import('./components/agentos-shell/agentos-shell.component').then((m) => m.AgentosShellComponent),
    children: [
      // --- Cases (own full-height shell with sidebar layout) ---
      {
        path: 'home',
        canActivate: [agentosReadyGuard],
        loadComponent: () => import('./components/case-shell/case-shell.component').then((m) => m.CaseShellComponent),
      },
      {
        path: '',
        loadComponent: () => import('./components/layout/layout.component').then((m) => m.LayoutComponent),
        children: [
          // IMPORTANT: static-prefix routes (namespaces, admin, me) must come before
          // parametric :namespaceId routes. Angular matches children in order — the
          // first match wins, so "admin/agent-configs/new" would otherwise be swallowed
          // by ":namespaceId/agent-configs/new" with namespaceId="admin".

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
          // --- Admin hub ---
          {
            path: 'admin',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/admin-home/admin-home.component').then((m) => m.AdminHomeComponent),
          },
          // --- Admin: Users ---
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
            loadComponent: () =>
              import('./components/admin-users-groups/admin-users-groups.component').then(
                (m) => m.AdminUsersGroupsComponent
              ),
          },
          // --- Admin: Platform Integration Configs ---
          {
            path: 'admin/integration-configs/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/integration-form/integration-form.component').then(
                (m) => m.IntegrationFormComponent
              ),
          },
          {
            path: 'admin/integration-configs/:integrationId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/integration-form/integration-form.component').then(
                (m) => m.IntegrationFormComponent
              ),
          },
          {
            path: 'admin/integration-configs',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/platform-integration-configs/platform-integration-configs.component').then(
                (m) => m.PlatformIntegrationConfigsComponent
              ),
          },
          // --- Admin: Platform Case Definitions ---
          {
            path: 'admin/case-definitions/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/case-definition-form/case-definition-form.component').then(
                (m) => m.CaseDefinitionFormComponent
              ),
          },
          {
            path: 'admin/case-definitions/:caseDefinitionId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/case-definition-form/case-definition-form.component').then(
                (m) => m.CaseDefinitionFormComponent
              ),
          },
          {
            path: 'admin/case-definitions',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/platform-case-definitions/platform-case-definitions.component').then(
                (m) => m.PlatformCaseDefinitionsComponent
              ),
          },
          // --- Admin: Platform Prompts ---
          {
            path: 'admin/prompts/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/prompt-form/prompt-form.component').then((m) => m.PromptFormComponent),
          },
          {
            path: 'admin/prompts/:promptId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/prompt-form/prompt-form.component').then((m) => m.PromptFormComponent),
          },
          {
            path: 'admin/prompts',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/platform-prompts/platform-prompts.component').then(
                (m) => m.PlatformPromptsComponent
              ),
          },
          // --- Admin: Platform AI Providers ---
          {
            path: 'admin/ai-providers/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/ai-provider-form/ai-provider-form.component').then((m) => m.AiProviderFormComponent),
          },
          {
            path: 'admin/ai-providers/:aiProviderId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/ai-provider-form/ai-provider-form.component').then((m) => m.AiProviderFormComponent),
          },
          {
            path: 'admin/ai-providers',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/platform-ai-providers/platform-ai-providers.component').then(
                (m) => m.PlatformAiProvidersComponent
              ),
          },
          // --- Admin: Platform AI Models ---
          {
            path: 'admin/ai-models/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/ai-model-form/ai-model-form.component').then((m) => m.AiModelFormComponent),
          },
          {
            path: 'admin/ai-models/:modelId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/ai-model-form/ai-model-form.component').then((m) => m.AiModelFormComponent),
          },
          {
            path: 'admin/ai-models',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/platform-ai-models/platform-ai-models.component').then(
                (m) => m.PlatformAiModelsComponent
              ),
          },
          // --- Admin: Platform Agent Configs ---
          {
            path: 'admin/agent-configs/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/agent-config-form/agent-config-form.component').then(
                (m) => m.AgentConfigFormComponent
              ),
          },
          {
            path: 'admin/agent-configs/:agentConfigId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/agent-config-form/agent-config-form.component').then(
                (m) => m.AgentConfigFormComponent
              ),
          },
          {
            path: 'admin/agent-configs/:agentConfigId/inspect',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/agent-config-inspect/agent-config-inspect.component').then(
                (m) => m.AgentConfigInspectComponent
              ),
          },
          {
            path: 'admin/agent-configs',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/platform-agent-configs/platform-agent-configs.component').then(
                (m) => m.PlatformAgentConfigsComponent
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
            path: 'user',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/user-profile/user-profile.component').then((m) => m.UserProfileComponent),
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
          // --- Prompts ---
          {
            path: ':namespaceId/prompts/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/prompt-form/prompt-form.component').then((m) => m.PromptFormComponent),
          },
          {
            path: ':namespaceId/prompts/:promptId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/prompt-form/prompt-form.component').then((m) => m.PromptFormComponent),
          },
          {
            path: ':namespaceId/prompts',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/namespace-prompts/namespace-prompts.component').then(
                (m) => m.NamespacePromptsComponent
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
          // --- User Groups ---
          {
            path: ':namespaceId/user-groups/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/user-group-form/user-group-form.component').then((m) => m.UserGroupFormComponent),
          },
          {
            path: ':namespaceId/user-groups/:userGroupId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/user-group-form/user-group-form.component').then((m) => m.UserGroupFormComponent),
          },
          {
            path: ':namespaceId/user-groups',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/namespace-user-groups/namespace-user-groups.component').then(
                (m) => m.NamespaceUserGroupsComponent
              ),
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
