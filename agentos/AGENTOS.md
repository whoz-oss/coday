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

## Built-in integrations

Beyond the PF4J plugins loaded from `plugins/`, a few integrations ship inside the service itself.
They are declared exactly like any other: an `IntegrationConfig` of the matching type, which an
agent opts into by naming it in its `integrations` map.

| Type | What an agent gets |
|---|---|
| `QUERY_USER` | Ask the user a question mid-run and resume with the answer (auto-granted by default) |
| `REDIRECT` | Hand the current request over to another agent |
| `CASE` | Read another case's transcript, start a new case with another agent |
| `USER_GROUP` | Query UserGroup membership and agent deployment |
| `SELF_CONFIG` | Let the user manage **their own** integration configurations |

Each is seeded at platform scope on startup when its `enabled-by-default` property is set, so the
type appears in the integration catalogue without an administrator having to know the type string.
Seeding declares **existence, not content**: once the configuration exists it belongs to the
administrator, and startup never rewrites it.

### `SELF_CONFIG` — self-service configuration

Five tools, prefixed with the configuration's name:

- `listIntegrationTypes` — the catalogue, each type with the JSON Schema of its parameters
- `listIntegrations` — the user's own configurations, parameters included
- `createIntegration`, `updateIntegration`, `deleteIntegration`

```yaml
# in an agent's YAML
integrations:
  SELF_CONFIG:                                   # all five tools
  # SELF_CONFIG: [listIntegrationTypes, listIntegrations]   # read-only variant
```

**The tools only ever reach configurations owned by the calling user** — their user-global and
user x namespace overlays. Shared configurations (namespace or platform) are refused for writing
even when the user is an administrator who could edit them in the UI: an LLM acting on a misread
instruction would otherwise reconfigure a whole namespace instead of one person's runs. A user who
wants to change a shared configuration for themselves creates a personal one of the same name,
which overrides it for their runs through the usual four-tier overlay.

That ownership boundary is also what makes it safe for `listIntegrations` to return parameters in
clear text: every row it can reach belongs to the person asking.

Two further restrictions: a run with no identified user (webhook, scheduled prompt) is refused
outright, and the agent cannot set `authSettingName` or `autoGrant` — binding a stored credential,
and handing a tool to every agent in scope, stay with a human in the configuration interface.

`SELF_CONFIG` is never auto-grantable: an agent able to rewrite configuration is always an explicit,
reviewed assignment.

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

## Detailed Documentation

- **Full architecture**: [docs/ARCHITECTURE.md](docs/to-rework/ARCHITECTURE.md)
- **File exchange** (storage layout, configuration, REST API, agent tools): [docs/file-exchange.md](docs/file-exchange.md)

---

**Stack**: Spring Boot 3.5 + Spring AI + Kotlin + PF4J
