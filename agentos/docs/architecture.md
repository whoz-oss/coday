# AgentOS Architecture

## Module Structure

AgentOS is split into two Gradle modules within a composite build:

- **`agentos-sdk`** — plugin contract: interfaces, domain models, HTTP API DTOs — no Spring dependency
- **`agentos-service`** — Spring Boot application: orchestration, REST API, plugin loading

The SDK is the stable public API. Plugins depend only on `agentos-sdk` (via `compileOnly`). The service depends on both.

## Package Map (`agentos-service`)

| Package | Responsibility |
|---|---|
| `agent` | `AgentService` / `AgentRegistry` / `AgentDiscoveryService` — runtime agent lifecycle |
| `aiModel` | `AiModelRegistry` / `AiModelDiscoveryService` — AI model configuration registry |
| `aiProvider` | `AiProviderRegistry` / `AiProviderDiscoveryService` — AI provider registry |
| `caseFlow` | `Case` / `CaseService` / `CaseController` — case (conversation) lifecycle |
| `caseEvent` | `CaseEvent` subtypes, emitter, SSE controller — event streaming |
| `chat` | `ChatClientProvider` / `ChatModelFactory` — Spring AI client construction |
| `configAgent` | Built-in `AGENTOS_CONFIG` integration: read and propose tools, frozen plans, server-side apply (see `config-agent.md`) |
| `entity` | `EntityController` — abstract CRUD base for all REST resources |
| `messaging` | `MessagingBotService` / `MessagingBotSupervisor` / `MessagingGateway` / `MessagingDelivery` / `TelegramDriver` / `SlackDriver` — the bots of agents, run at once from the database, that feed cases and answer from them (see `messaging.md`) |
| `namespace` | `Namespace` / `NamespaceController` — multi-tenancy grouping |
| `plugin` | `PluginService` — PF4J plugin loading and management |
| `tool` | `ToolRegistry` / `ToolExecutorService` / `ToolController` — tool registry and execution |

## Package Map (`agentos-sdk`)

### Domain and plugin contract

| Package | Responsibility |
|---|---|
| `agent` | `Agent`, `AgentDefinition`, `AgentPlugin`, `AgentStatus` — plugin contract for agents |
| `aiProvider` | `AiProvider`, `AiModel`, `AiModelPlugin`, `AiProviderPlugin`, `AiApiType` |
| `caseEvent` | `CaseEvent` sealed hierarchy with all subtypes, `MessageContent` |
| `caseFlow` | `CaseStatus` enum |
| `entity` | `Entity`, `EntityMetadata`, `EntityService`, `EntityRepository`, `InMemoryEntityRepository` |
| `feedback` | `Feedback` domain entity |
| `tool` | `StandardTool`, `ToolPlugin`, `ToolExecutionResult` |
| `actor` | `Actor`, `ActorRole` |

### HTTP API DTOs and contracts (`api.*`)

The `api.*` packages define the canonical HTTP contract for AgentOS: both the method
signatures (`*Api` interfaces) and the data types (`*Dto` classes) used as request
bodies and responses.

**SDK dependencies are intentionally minimal** — no Spring MVC annotations of any kind:
- `jakarta.validation.api` (`compileOnly`) — `@NotBlank`, `@NotNull`, `@Size`, etc.
  on DTO fields. Shared constraints benefit both the service (Bean Validation) and
  consumers that choose to apply them client-side.
- `swagger-annotations` (`compileOnly`) — `@Schema` on DTO fields for OpenAPI spec
  quality. Never bundled.

**Two sides implement the same interface independently:**
- `agentos-service` controllers implement the `*Api` interface and own all Spring MVC
  annotations (`@RestController`, `@RequestMapping`, `@PostMapping`, `@PreAuthorize`,
  etc.) locally on the controller class and its methods.
- External consumers (e.g. whoz Copilot) implement the same `*Api` interface as a
  Feign client, adding their own `@FeignClient`, routing annotations, and client
  configuration. AgentOS does not prescribe client technology or configuration.

Because routing annotations live exclusively on the controller (never on the interface
or on `EntityController`), Spring MVC cannot register duplicate routes regardless of
how many base classes or interfaces a controller inherits from.

| Package | Responsibility |
|---|---|
| `api.agentConfig` | `AgentConfigDto`, `AgentConfigSearchRequest`, `AgentDefinitionDto` — HTTP DTOs for `/api/agent-configs` |
| `api.aiProvider` | `AiProviderDto`, `AiModelDto` — HTTP DTOs for `/api/ai-providers` and `/api/ai-models` |
| `api.case` | `CaseDto`, `AddMessageRequest`, `ListByUserInNamespaceRequest` — HTTP DTOs for `/api/cases` |
| `api.common` | `GetByIdsRequest` — shared request body for all `POST /by-ids` endpoints |
| `api.configChange` | `ConfigChangeApi`, `ConfigChangeViewDto`, `ConfigChangeReportDto`, `ConfigChangeApplyRequest`, `ConfigChangeRejectRequest`, the operation/result DTOs (`ConfigOperationViewDto`, `ConfigFieldChangeDto`, `ConfigSecretInputDto`, `ConfigOperationResultDto`) and their enums — HTTP contract for `/api/cases/{caseId}/config-changes` |
| `api.feedback` | `FeedbackDto`, `FeedbackInput` — HTTP DTOs for `/api/feedbacks` |
| `api.integrationConfig` | `IntegrationConfigDto`, `IntegrationTypeDescriptor` — HTTP DTOs for `/api/integration-configs` and `/api/integration-types` |
| `api.messaging` | `MessagingApi`, `MessagingBotApi`, `MessagingBotDto`, `MessagingBotManagerDto`, `MessagingAppManifestDto`, `MessagingIdentityDto`, `MessagingLinkDto`, `MessagingContinueRequest`, `MessagingPlatform` — HTTP contract for `/api/messaging`, `/api/namespaces/{namespaceId}/agent-configs/{agentId}/messaging-bots` and `/api/cases/{caseId}/messaging` |
| `api.namespace` | `NamespaceDto`, `NamespaceListItem`, `NamespaceUserListItem`, `NamespaceRoleEntry`, `SyncUserRolesRequest`, `NamespaceAgentDeployRequest` — HTTP DTOs for `/api/namespaces` |
| `api.user` | `UserDto`, `GroupsByExternalIdsRequest` — HTTP DTOs for `/api/users` |
| `api.userGroup` | `UserGroupSummary`, `UserGroupSearchResult`, `UserGroupCreateRequest`, `UserGroupUpdateRequest` — HTTP DTOs for `/api/user-groups` |

## Case summary

Each top-level case carries a short summary (`Case.summary`, exposed read-only as `summary` in the REST `Case`) so that an older case can be found again: the case search of the UI matches the title, the id **and** the summary, and the summary is shown as the row tooltip and, while a search is active, as a one-line subtitle under the title. Written by `CaseSummaryService` (package `caseFlow`), it is generated from the conversation, never typed by a user: `PUT /api/cases/{id}` and create ignore it.

**When it is generated.** `CaseServiceImpl` considers a refresh each time a case turn ends (status `IDLE`, same spot as the automatic title). It launches one in the background when:

- the case is top-level: **sub-cases (delegations) are excluded**, their parent's summary covers the work;
- at least one agent turn has finished (`AgentFinishedEvent`), so the first summary already sees the agent's first answer;
- and either the case has no summary yet, or at least **3 user messages** came in since the last one (`Case.summaryMessageCount` stores how many user messages the last summary covered; the threshold is `SUMMARY_REFRESH_USER_MESSAGES`).

A case is never summarized twice at once. When the summary is stored, the namespace case-list stream (`namespace-case-list-updated`) is signalled, so open drawers reload their list and show the new summary without a refresh.

**Model and size.** It is the same cheap path as the automatic title: a utility call (`forUtilityCall()`, reasoning effort capped at low) on the **default AI model of the namespace**. The input is the previous summary (so the model updates it instead of starting over) plus the last 12 user and agent text messages, each cut to 1,000 characters, **8,000 characters in total** (the oldest messages are dropped first; images and tool traffic are not sent). The prompt asks for 2 to 3 factual sentences, about 400 characters, in the language of the conversation; the stored text is trimmed and capped at **500 characters**.

**Cost.** About one small utility call per 3 user messages of a case, plus one after its first agent turn: at most roughly 2,500 input tokens and 150 output tokens each, whatever the length of the conversation. Nothing is spent on sub-cases, on cases that never got an agent answer, or on a namespace with no default model.

**Failures.** There is no fallback text, unlike the title: with no default model, no provider, a blank answer or a failed call, nothing is written, the previous summary (if any) stays and the next eligible turn tries again.

**Disabling.** Set `agentos.case.summary-enabled: false` (environment variable `AGENTOS_CASE_SUMMARY_ENABLED=false`), read at startup. No new summary is generated; existing ones are kept and stay searchable.

**Not context compaction.** The summary is a label for humans and for search. It is never sent back to the agent's model and is unrelated to the context compaction planned in #1285, whose summary is written for the model; the two stay separate, even if some generation code is shared later.
