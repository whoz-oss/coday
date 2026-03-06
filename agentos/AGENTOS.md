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

## Detailed Documentation

- **Full architecture**: [docs/ARCHITECTURE.md](docs/to-rework/ARCHITECTURE.md)

---

**Stack**: Spring Boot 3.5 + Spring AI + Kotlin + PF4J
