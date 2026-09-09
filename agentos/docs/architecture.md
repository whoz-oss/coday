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
| `entity` | `EntityController` — abstract CRUD base for all REST resources |
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
| `api.feedback` | `FeedbackDto`, `FeedbackInput` — HTTP DTOs for `/api/feedbacks` |
| `api.integrationConfig` | `IntegrationConfigDto`, `IntegrationTypeDescriptor` — HTTP DTOs for `/api/integration-configs` and `/api/integration-types` |
| `api.namespace` | `NamespaceDto`, `NamespaceListItem`, `NamespaceUserListItem`, `NamespaceRoleEntry`, `SyncUserRolesRequest` — HTTP DTOs for `/api/namespaces` |
| `api.user` | `UserDto`, `GroupsByExternalIdsRequest` — HTTP DTOs for `/api/users` |
| `api.userGroup` | `UserGroupSummary`, `UserGroupSearchResult`, `UserGroupCreateRequest`, `UserGroupUpdateRequest` — HTTP DTOs for `/api/user-groups` |
