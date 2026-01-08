# AgentOS

A Spring Boot application for orchestrating AI agents with a dynamic plugin system.

## What is AgentOS?

AgentOS provides a flexible service for discovering, registering, and managing AI agents based on their capabilities and context. It comes with a powerful plugin system that allows you to extend functionality without modifying the core application.

## Features

- 🤖 **Agent Registry** - 8 built-in agents for common tasks (code review, testing, documentation, etc.)
- 🔌 **Plugin System** - Load agents dynamically from plugins
- 🔄 **Hot Reload** - Add/remove plugins without restart
- 📊 **Context-Aware** - Agents can be queried by context, capabilities, and tags
- 🌐 **REST API** - Complete HTTP API for all operations
- 🎯 **Priority-Based** - Agents ranked by priority for better orchestration

## Quick Start

### Prerequisites

- Java 17+
- Gradle (wrapper included)

### Run with Built-in Agents Only

```bash
cd agentos
./gradlew bootRun
```

Access the API at `http://localhost:8080`

### Run with Plugins

**Code-Based Plugin** (5 agents defined in Kotlin):
```bash
./run-code-based.sh
```

**Filesystem Plugin** (loads agents from YAML files):
```bash
./run-filesystem.sh
```

**All Plugins** (loads all available plugins):
```bash
./run-all-plugins.sh
```

## API Examples

### List All Agents

```bash
curl http://localhost:8080/api/agents | jq
```

### Query Agents by Context

```bash
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{"contextTypes": ["CODE_REVIEW"], "minPriority": 8}' | jq
```

### List Plugins

```bash
curl http://localhost:8080/api/plugins | jq
```

## Plugin System

### Two Types of Plugins

**1. Code-Based Plugin** - Agents defined in Kotlin code (type-safe, compile-time checks)
```bash
./run-code-based.sh
```
Provides 5 agents: data-scientist, frontend-architect, backend-architect, cloud-engineer, qa-automation

**2. Filesystem Plugin** - Agents defined in YAML files (no rebuild needed)
```bash
./run-filesystem.sh
```
Loads agents from `agents/*.yaml` files

### Creating YAML Agents

Create a file in `agents/my-agent.yaml`:

```yaml
name: My Custom Agent
description: What this agent does
capabilities:
  - custom-capability
contexts:
  - GENERAL
tags:
  - custom
priority: 7
```

Then reload:
```bash
curl -X POST http://localhost:8080/api/plugins/filesystem-agents/reload
```

## Configuration

Edit `src/main/resources/application.yml`:

```yaml
agentos:
  plugins:
    directory: plugins    # Plugin directory
    autoLoad: true        # Auto-load on startup
```

## Development

### Build

```bash
./gradlew build
```

### Run Tests

```bash
./gradlew test
```

### Build a Plugin

```bash
cd code-based-plugin
../gradlew jar
```

## Important Notes

⚠️ **Spring Boot DevTools must be disabled** when using plugins due to classloader conflicts. This is already configured in `build.gradle.kts`.

## Project Structure

```
agentos/
├── src/main/kotlin/
│   └── io/biznet/agentos/
│       ├── agents/              # Agent registry and API
│       └── plugins/             # Plugin system
├── code-based-plugin/           # Kotlin-based agents
├── filesystem-plugin/           # YAML-based agents
├── agents/                      # YAML agent files
│   ├── howzi.yaml
│   ├── security-scanner.yaml
│   └── api-architect.yaml
├── plugins/                     # Deployed plugin JARs
├── run-code-based.sh           # Run with code-based plugin
├── run-filesystem.sh           # Run with filesystem plugin
└── run-all-plugins.sh          # Run with all plugins
```

## Troubleshooting

### Plugin not loading

Check that:
1. JAR is directly in `plugins/` (not in a subdirectory)
2. DevTools is disabled in `build.gradle.kts`
3. Plugin was built with `clean jar`

## Documentation

**[AGENTOS.md](AGENTOS.md)** - Vue d'ensemble complète (architecture, concepts, intégration Whoz)

### Documentation Détaillée

- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** - Architecture système
- **[docs/PLUGIN_SYSTEM.md](docs/PLUGIN_SYSTEM.md)** - Développement de plugins
- **[docs/EXAMPLES.md](docs/EXAMPLES.md)** - Exemples d'utilisation

---

**Built with Spring Boot 3.5 + Kotlin + Spring AI + PF4J**
