import { Route } from '@angular/router'
import { agentosReadyGuard } from './guards/agentos-ready.guard'

export const AGENTOS_ROUTES: Route[] = [
  {
    // OAuth popup callback — standalone route outside the AgentOS shell.
    // The popup window does not need the full layout; it bootstraps, sends postMessage, and closes.
    path: 'oauth/callback',
    loadComponent: () =>
      import('./components/oauth-callback/oauth-callback.component').then((m) => m.OAuthCallbackComponent),
  },
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
            // Legacy URL: edit now lives in the namespace settings shell (`:namespaceId/edit`).
            path: 'namespaces/:namespaceId/edit',
            redirectTo: '/agentos/:namespaceId/edit',
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
          // --- Admin: Tool invoke (debug) ---
          {
            path: 'admin/tool-invoke',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/tool-invoke/tool-invoke.component').then((m) => m.ToolInvokeComponent),
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
          // --- Admin: Platform Scheduled Prompts ---
          {
            path: 'admin/scheduled-prompts/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/scheduled-prompt-form/scheduled-prompt-form.component').then(
                (m) => m.ScheduledPromptFormComponent
              ),
          },
          {
            path: 'admin/scheduled-prompts/:scheduledPromptId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/scheduled-prompt-form/scheduled-prompt-form.component').then(
                (m) => m.ScheduledPromptFormComponent
              ),
          },
          {
            path: 'admin/scheduled-prompts',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/platform-scheduled-prompts/platform-scheduled-prompts.component').then(
                (m) => m.PlatformScheduledPromptsComponent
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
          // --- Admin: Platform Auth Settings ---
          {
            path: 'admin/auth-settings/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/auth-setting-form/auth-setting-form.component').then(
                (m) => m.AuthSettingFormComponent
              ),
          },
          {
            path: 'admin/auth-settings/:authSettingId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/auth-setting-form/auth-setting-form.component').then(
                (m) => m.AuthSettingFormComponent
              ),
          },
          {
            path: 'admin/auth-settings',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/platform-auth-settings/platform-auth-settings.component').then(
                (m) => m.PlatformAuthSettingsComponent
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
          // --- Admin: Platform Skills ---
          {
            path: 'admin/skills/new',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/skill-form/skill-form.component').then((m) => m.SkillFormComponent),
          },
          {
            path: 'admin/skills/:skillId/edit',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/skill-form/skill-form.component').then((m) => m.SkillFormComponent),
          },
          {
            path: 'admin/skills/:skillId/inspect',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/skill-inspect/skill-inspect.component').then((m) => m.SkillInspectComponent),
          },
          {
            path: 'admin/skills',
            canActivate: [agentosReadyGuard],
            loadComponent: () =>
              import('./components/platform-skills/platform-skills.component').then((m) => m.PlatformSkillsComponent),
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
          // --- Namespace settings shell ---
          // Preserve the established URLs while rendering all namespace-scoped pages
          // inside a shared settings navigation shell.
          {
            path: ':namespaceId',
            loadComponent: () =>
              import('./components/namespace-settings-layout/namespace-settings-layout.component').then(
                (m) => m.NamespaceSettingsLayoutComponent
              ),
            children: [
              // --- Namespace details (edit form) ---
              {
                path: 'edit',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/namespace-form/namespace-form.component').then((m) => m.NamespaceFormComponent),
              },
              // --- Auth Settings ---
              {
                path: 'auth-settings/new',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/auth-setting-form/auth-setting-form.component').then(
                    (m) => m.AuthSettingFormComponent
                  ),
              },
              {
                path: 'auth-settings/:authSettingId/edit',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/auth-setting-form/auth-setting-form.component').then(
                    (m) => m.AuthSettingFormComponent
                  ),
              },
              {
                path: 'auth-settings',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/auth-settings-all-scopes/auth-settings-all-scopes.component').then(
                    (m) => m.AuthSettingsAllScopesComponent
                  ),
              },
              // --- Integrations ---
              {
                path: 'integrations/new',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/integration-form/integration-form.component').then(
                    (m) => m.IntegrationFormComponent
                  ),
              },
              {
                path: 'integrations/:integrationId/edit',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/integration-form/integration-form.component').then(
                    (m) => m.IntegrationFormComponent
                  ),
              },
              {
                path: 'integrations/:integrationId/view',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/integration-config-view/integration-config-view.component').then(
                    (m) => m.IntegrationConfigViewComponent
                  ),
              },
              {
                path: 'integrations',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/integrations-all-scopes/integrations-all-scopes.component').then(
                    (m) => m.IntegrationsAllScopesComponent
                  ),
              },
              // --- AI Providers ---
              {
                path: 'ai-providers/new',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/ai-provider-form/ai-provider-form.component').then(
                    (m) => m.AiProviderFormComponent
                  ),
              },
              {
                path: 'ai-providers/:aiProviderId/edit',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/ai-provider-form/ai-provider-form.component').then(
                    (m) => m.AiProviderFormComponent
                  ),
              },
              {
                path: 'ai-providers',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/ai-providers-all-scopes/ai-providers-all-scopes.component').then(
                    (m) => m.AiProvidersAllScopesComponent
                  ),
              },
              // --- AI models ---
              {
                path: 'ai-models/new',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/ai-model-form/ai-model-form.component').then((m) => m.AiModelFormComponent),
              },
              {
                path: 'ai-models/:modelId/edit',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/ai-model-form/ai-model-form.component').then((m) => m.AiModelFormComponent),
              },
              {
                path: 'ai-models',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/namespace-ai-models/namespace-ai-models.component').then(
                    (m) => m.NamespaceAiModelsComponent
                  ),
              },
              {
                path: 'usage',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/namespace-usage/namespace-usage.component').then(
                    (m) => m.NamespaceUsageComponent
                  ),
              },
              // --- Scheduled Prompts ---
              {
                path: 'scheduled-prompts/new',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/scheduled-prompt-form/scheduled-prompt-form.component').then(
                    (m) => m.ScheduledPromptFormComponent
                  ),
              },
              {
                path: 'scheduled-prompts/:scheduledPromptId/edit',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/scheduled-prompt-form/scheduled-prompt-form.component').then(
                    (m) => m.ScheduledPromptFormComponent
                  ),
              },
              {
                path: 'scheduled-prompts',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/scheduled-prompt-list/scheduled-prompt-list.component').then(
                    (m) => m.ScheduledPromptListComponent
                  ),
              },
              // --- Prompts ---
              {
                path: 'prompts/new',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/prompt-form/prompt-form.component').then((m) => m.PromptFormComponent),
              },
              {
                path: 'prompts/:promptId/edit',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/prompt-form/prompt-form.component').then((m) => m.PromptFormComponent),
              },
              {
                path: 'prompts',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/namespace-prompts/namespace-prompts.component').then(
                    (m) => m.NamespacePromptsComponent
                  ),
              },
              // --- Skills ---
              {
                path: ':namespaceId/skills/new',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/skill-form/skill-form.component').then((m) => m.SkillFormComponent),
              },
              {
                path: ':namespaceId/skills/:skillId/edit',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/skill-form/skill-form.component').then((m) => m.SkillFormComponent),
              },
              {
                path: ':namespaceId/skills/:skillId/inspect',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/skill-inspect/skill-inspect.component').then((m) => m.SkillInspectComponent),
              },
              {
                path: ':namespaceId/skills',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/namespace-skills/namespace-skills.component').then(
                    (m) => m.NamespaceSkillsComponent
                  ),
              },

              // --- Agent Configs ---
              {
                path: 'agent-configs/new',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/agent-config-form/agent-config-form.component').then(
                    (m) => m.AgentConfigFormComponent
                  ),
              },
              {
                path: 'agent-configs/:agentConfigId/edit',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/agent-config-form/agent-config-form.component').then(
                    (m) => m.AgentConfigFormComponent
                  ),
              },
              {
                path: 'agent-configs/:agentConfigId/inspect',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/agent-config-inspect/agent-config-inspect.component').then(
                    (m) => m.AgentConfigInspectComponent
                  ),
              },
              {
                path: 'agent-configs/:agentConfigId/launch',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/agent-config-launch/agent-config-launch.component').then(
                    (m) => m.AgentConfigLaunchComponent
                  ),
              },
              {
                path: 'agent-configs',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/namespace-agent-configs/namespace-agent-configs.component').then(
                    (m) => m.NamespaceAgentConfigsComponent
                  ),
              },
              // --- User Groups ---
              {
                path: 'user-groups/new',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/user-group-form/user-group-form.component').then(
                    (m) => m.UserGroupFormComponent
                  ),
              },
              {
                path: 'user-groups/:userGroupId/edit',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/user-group-form/user-group-form.component').then(
                    (m) => m.UserGroupFormComponent
                  ),
              },
              {
                path: 'user-groups',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/namespace-user-groups/namespace-user-groups.component').then(
                    (m) => m.NamespaceUserGroupsComponent
                  ),
              },
              // --- Tool Invoke (namespace-scoped) ---
              {
                path: 'tool-invoke',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/tool-invoke/tool-invoke.component').then((m) => m.ToolInvokeComponent),
              },
              // --- Git repository ---
              {
                path: 'git',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/namespace-git/namespace-git.component').then((m) => m.NamespaceGitComponent),
              },
              // --- Members ---
              {
                path: 'members',
                canActivate: [agentosReadyGuard],
                loadComponent: () =>
                  import('./components/namespace-members/namespace-members.component').then(
                    (m) => m.NamespaceMembersComponent
                  ),
              },
            ],
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
