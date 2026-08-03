# Plugin System

## Overview

AgentOS uses PF4J for plugin loading. Plugins are JARs placed in the `plugins/` directory. At startup, `PluginService` loads all JARs and the discovery services query each extension point.

The `agentos-sdk` module defines four extension points:

| Interface | Contributes |
|---|---|
| `AgentPlugin` | `AgentDefinition` list -> `AgentRegistry` |
| `AiModelPlugin` | `AiModel` list -> `AiModelRegistry` |
| `AiProviderPlugin` | `AiProvider` list -> `AiProviderRegistry` |
| `ToolPlugin` | `StandardTool<*>` list -> `ToolRegistry` |

## Creating a Plugin

A plugin requires two classes: a `Plugin` subclass (lifecycle hooks) and one or more `@Extension`-annotated classes implementing the relevant interface above.

The JAR manifest must declare `Plugin-Id`, `Plugin-Version`, and `Plugin-Class`. PF4J annotation processing requires the kapt processor in the build, with `pf4j.storageClassName` set to `org.pf4j.processor.LegacyExtensionStorage`.

Deploy by copying the JAR into `plugins/` and restarting the service.

## Filesystem Plugin

The `agentos-plugins-filesystem` module provides filesystem-based providers for agents, AI models, and AI providers. They scan configured directories for YAML files and load definitions without recompilation.

Directory resolution order for each type:

1. System property
2. Environment variable
3. Default relative path (`agents/`, `aimodel/`, `aiprovider/`)

For agents, the `id` is derived from the YAML filename (lowercase, kebab-case, no extension). Supported fields include name, description, capabilities, contexts, tags, priority, status, and AI provider/model hints.

## `{{NAMESPACE_CONFIG_PATH}}` Token in Filesystem IntegrationConfig

A committed `IntegrationConfig` YAML under `<configPath>/integrations/` cannot hardcode an
absolute path — every developer clones the project's repo at a different location. The
`{{NAMESPACE_CONFIG_PATH}}` token, substituted only inside the `parameters` field at parse
time, solves this by resolving to the owning namespace's `configPath`.

```yaml
name: BASH_repo
integrationType: BASH
parameters:
  workingDirectory: "{{NAMESPACE_CONFIG_PATH}}/../.."
  tools:
    - name: build
      command: "{{NAMESPACE_CONFIG_PATH}}/../scripts/build.sh"
```

The token is a plain textual replacement — it can appear anywhere in a value, including
embedded mid-string (`--config={{NAMESPACE_CONFIG_PATH}}/x.json`) or inside an array element,
which is what lets a single token cover an MCP `args: List<String>` entry as well as a BASH
command string. **No path normalization is applied**: the result of substituting into
`{{NAMESPACE_CONFIG_PATH}}/../scripts` is the literal string `<configPath>/../scripts` — `..`
segments are left for the consumer (`Path.of`, `File`, `canonicalPath`) to resolve.

Scope: only `IntegrationConfig.parameters` loaded from the filesystem is substituted. `name`,
`integrationType`, and `description` are never touched, and configs created through the API or
persisted in Neo4j are entirely unaffected — a literal token saved through the API is stored
and will simply fail at runtime, which is accepted power-user behaviour.

Typical consumers: the **BASH** plugin (`workingDirectory`, tool `command`), **MCP_STDIO**
(`cwd`, and paths embedded in `args`), and **FILE_ACCESS** (`rootPath`).

## Tool Registration

When wrapping a `StandardTool` from a plugin as a Spring AI `ToolCallback`, always implement `ToolCallback` directly with `DefaultToolDefinition` — never use `MethodToolCallback`. `MethodToolCallback` reflects on the wrapper method signature and produces a wrong schema, causing the LLM to send empty arguments. Deserialization must happen inside the plugin classloader via `tool.executeWithJson(input)`.
