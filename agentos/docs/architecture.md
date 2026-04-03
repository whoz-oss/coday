# AgentOS Architecture

## Module Structure

AgentOS is split into two Gradle modules within a composite build:

- **`agentos-sdk`** — plugin contract: interfaces, domain models, no Spring dependency
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

| Package | Responsibility |
|---|---|
| `agent` | `Agent`, `AgentDefinition`, `AgentPlugin`, `AgentStatus` — plugin contract for agents |
| `aiProvider` | `AiProvider`, `AiModel`, `AiModelPlugin`, `AiProviderPlugin`, `AiApiType` |
| `caseEvent` | `CaseEvent` sealed hierarchy with all subtypes, `MessageContent` |
| `caseFlow` | `CaseStatus` enum |
| `entity` | `Entity`, `EntityMetadata`, `EntityService`, `EntityRepository`, `InMemoryEntityRepository` |
| `tool` | `StandardTool`, `ToolPlugin`, `ToolExecutionResult` |
| `actor` | `Actor`, `ActorRole` |


