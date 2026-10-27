# AgentOS

## What is it?

**AgentOS** = AI agent orchestration system built with Spring Boot + Spring AI + Kotlin.

Designed to be reused via Docker in products like Whoz (staffing solution).

## Architecture

```
REST API (Controllers)
    ↓
Orchestrator + Spring AI (OpenAI, Anthropic, vLLM)
    ↓
Plugins + Tools (Kotlin code or YAML)
```

## Key Concepts

### Agent Registry

Agent discovery based on contexts, capabilities, priorities, tags.

### Orchestrator

Multi-turn conversation management:

1. Generates an **intention** (what the agent wants to do)
2. Selects the appropriate **tool**
3. Generates **parameters**
4. Executes and records the **result**

### Plugin System

- **Code-Based**: Agents in Kotlin (type-safe)
- **Filesystem**: Agents in YAML (hot reload)

## Quick Start

```bash
# With built-in agents
./gradlew bootRun

# With YAML plugin
./run-filesystem.sh
```

API: `http://localhost:8080`

## Create a YAML Agent

```yaml
# agents/my-agent.yaml
name: My Agent
description: What the agent does
capabilities: [ capability-1 ]
contexts: [ GENERAL ]
tags: [ custom ]
priority: 8
```

Reload: `curl -X POST http://localhost:8080/api/plugins/filesystem-agents/reload`

## Create a Kotlin Plugin

```kotlin
@Extension
class MyPlugin : AgentPlugin() {
    override fun getAgents(): List<Agent> = listOf(
        Agent(
            id = "my-agent",
            name = "My Agent",
            capabilities = listOf("custom"),
            requiredContext = listOf(ContextType.GENERAL),
            priority = 8
        )
    )
}
```

## Persistence (WZ-28667)

AgentOS uses **file-system persistence by default**. Data survives restarts.

```
data/                                  # AGENTOS_PERSISTENCE_DATA_DIR (default: data/)
  cases/<projectId>/<caseId>.json
  case-events/<caseId>/<eventId>.json
  namespaces/all/<namespaceId>.json

data/exchange/                         # AGENTOS_EXCHANGE_MOUNT_ROOT (default: data/exchange/), see docs/file-exchange.md
  <namespaceId>/cases/<YYYY>/<MM>/<DD>/<caseId>/   # case-scoped file exchange (read/write)
  <namespaceId>/shared/                            # namespace-shared file exchange (read; read/write for namespace admins)
```

To switch to **in-memory** mode (data lost on restart):

```bash
# env var
export AGENTOS_PERSISTENCE_MODE=in-memory

# or application.yml
agentos.persistence.mode: in-memory
```

## Spring AI Configuration

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
```

## Docker

```dockerfile
FROM eclipse-temurin:17-jre-alpine
COPY build/libs/agentos-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

## Whoz Integration

AgentOS can be deployed as an independent service in Whoz for:

- **Smart consultant assignment** (skills/availability matching)
- **Schedule optimization** (maximize utilization)
- **Skills analysis** (identify gaps)
- **Automated reporting** (report generation)

Via Docker Compose:

```yaml
services:
  agentos:
    image: agentos:latest
    environment:
      - WHOZ_API_URL=http://whoz-app:8080/api
```

Custom Whoz plugins written in Kotlin to access business data.

## CI / CD

### Validation (Pull Requests)

Every PR against `master` triggers `.github/workflows/validate.yml`, which:

1. Runs `lint` and `test` on all **affected** TypeScript projects
2. Builds and tests all **affected** JVM projects (tagged `platform:jvm` — covers sdk, service, and plugins)
3. Checks the OpenAPI spec is up to date

JDK 25 (Temurin) and Gradle cache are pre-configured in the workflow.

### Release (Push to `master`)

Every push to `master` triggers `.github/workflows/release.yml`, which:

1. Determines if a release is needed (conventional commits: `feat`, `fix`, `BREAKING CHANGE`)
2. Runs `nx release` — bumps versions, generates changelog, tags, and pushes
3. Publishes all JVM artifacts to [GitHub Packages](https://github.com/orgs/whoz-oss/packages) in parallel

### Published Artifacts

All artifacts are published to GitHub Packages under the `whoz-oss.agentos` group:

| Artifact | Description | Version key in `libs.versions.toml` |
|---|---|---|
| `agentos-sdk` | Plugin SDK — interfaces and extension points | `agentosSdk` |
| `agentos-service` | Spring Boot orchestration service (bootJar) | `agentosService` |
| `agentos-datetime-plugin` | Date/time tools plugin | `agentosService` |
| `agentos-file-plugin` | File system tools plugin | `agentosService` |
| `agentos-bash-plugin` | Bash command tools plugin | `agentosService` |
| `agentos-tmux-plugin` | Tmux session management plugin | `agentosService` |

The `agentosSdk` and `agentosService` version keys in `agentos/gradle/libs.versions.toml` are
both updated automatically by `scripts/release.ts` on every Nx release. To add a new versioned
Gradle artifact, append its TOML key to the `tomlVersionKeys` array in that script.

### Consuming Artifacts

Add the GitHub Packages Maven repository to your Gradle project:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/whoz-oss/coday")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}
```

## Logging & correlation

Every log line produced during a case run carries SLF4J MDC correlation keys, defined in
`io.whozoss.agentos.logging.LogContext`:

| Key | Set by | Scope |
|---|---|---|
| `caseId`, `namespaceId` | `CaseRuntime.run` | the whole turn, sub-cases included (each has its own runtime) |
| `agentName`, `userId` | `CaseServiceImpl.runAgent` | one agent execution |
| (all of the above) | `AgentSimple` tool callbacks | re-applied on Spring AI / Reactor threads |

The context travels with coroutines (`kotlinx-coroutines-slf4j` `MDCContext`), so it survives
dispatcher hops. Code that starts a new coroutine scope or runs on a foreign thread must carry it
explicitly (`LogContext.mdcContext(...)`, `withLogContext`, `withMdc`).

- **Console** (default profile): `... [case=<caseId> ns=<namespaceId> agent=<agentName> user=<userId>] - message`;
  the bracket block is omitted on lines logged outside a case run.
- **Docker** profile: JSON via the Logstash encoder; MDC keys are top-level fields, ready to filter on.
- **Levels**: `logging.level.io.whozoss.agentos` is INFO by default, in every profile. In docker, set
  `LOGGING_LEVEL_IO_WHOZOSS_AGENTOS` (`LOGGING_LEVEL_IO_BIZNET_AGENTOS` is still read as a fallback).
- **`debug` profile**: sets `io.whozoss.agentos` to DEBUG and also logs Spring AI prompts/completions and
  HTTP traffic. Local debugging only.
- **Rule**: tool arguments, tool outputs, prompts and secrets are never logged above TRACE.

## Detailed Documentation

- **Full architecture**: [docs/ARCHITECTURE.md](docs/to-rework/ARCHITECTURE.md)
- **File exchange** (storage layout, configuration, REST API, agent tools): [docs/file-exchange.md](docs/file-exchange.md)

---

**Stack**: Spring Boot 3.5 + Spring AI + Kotlin + PF4J
