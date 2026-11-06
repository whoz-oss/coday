# AgentOS — Spring Boot 4 / Jackson 3 Migration: Outcome

Issue #1588. Complements the original study [`boot4-jackson3-migration.md`](boot4-jackson3-migration.md).

## Decisions

- **Single PR, full Jackson 3 (option A)**. The 4-phase plan could not work: Spring AI 1.x does not run on Boot 4,
  and Spring AI 2.0.2 itself depends on Jackson 3 (`tools.jackson.core:jackson-databind`). The SDK exposes Jackson 3
  types (`JsonNode`, `StandardTool`, `ToolPlugin`): **breaking for external plugins**, which must be recompiled.
- **Spring AI 2.0.2.** OpenAI and Anthropic now run on the official vendor SDKs (`OpenAIClient`, `AnthropicClient`);
  models are built through `XxxChatModel.builder()` with connection settings carried by `XxxChatOptions`.

## Behaviour changes found and handled

| Area | Change | Handling |
|---|---|---|
| Tool loop | Spring AI 2 models never execute tools; `ChatClient` installs a `ToolCallingAdvisor` running its own loop. | `UsageTrackingChatModel` keeps owning the loop (per-round accounting, cost gate **before** each tool) and builds its `ChatClient` with an advisor that never executes (`UsageTrackingChatModel.chatClient()`). Without accounting, the default advisor executes tools. |
| Kotlin delegation | `ChatModel.getOptions()` is a Java default method: `ChatModel by delegate` does not forward it, so `ChatClient` silently dropped the tool callbacks. | Explicit `getOptions()` override. |
| OpenAI base URL | The official SDK expects the base URL to end with `/v1` (Spring AI 1.x appended it). | `ChatModelFactory.toOpenAiSdkBaseUrl` normalises existing provider configurations. |
| Cost | Native usage types changed (`Optional` getters); reflection-based reading silently lost cache counts. | `CostCalculator` reads the portable `Usage.cacheRead/WriteInputTokens` (Spring AI 2.0) — no reflection. |
| Jackson 3 defaults | `FAIL_ON_UNKNOWN_PROPERTIES` off; `FAIL_ON_TRAILING_TOKENS` on; `asText()` throws on containers; source redacted in error messages. | Unknown tool arguments still rejected (`StandardTool`); tolerant intention parsing keeps trailing prose (`AgentIntentionGenerator`); `JsonNodeExtensions` read scalar nodes only. |
| Boot 4 config | `spring.jackson.serialization.write-dates-as-timestamps` no longer binds. | `spring.jackson.datatype.datetime.write-dates-as-timestamps`. |
| Spring Security 7 | `PermissionEvaluator` parameters are non-null, while SpEL legitimately passes `null` (platform scope). | Java adapter `NullTolerantPermissionEvaluator` with `@Nullable` entry points. |
| `JsonNode` in Kotlin | `node.map { }` on a `JsonNode` compiles but does not iterate the children. | `node.values().map { }`. |

## Jackson 2 on the classpath

AgentOS code no longer references Jackson 2 (only `jackson-annotations`, shared by Jackson 3). Jackson 2 stays on the
runtime classpath transitively, aligned by the Boot BOM: vendor SDKs used by Spring AI 2.0.2 (OpenAI, Anthropic,
Google GenAI), embedded Neo4j, springdoc, Eureka. Removing it is out of AgentOS's control.
