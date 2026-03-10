# Running AgentOS

## Required Environment Variables

At minimum one AI provider must be configured. Set the relevant keys before starting:

```bash
export OPENAI_API_KEY=sk-...
export ANTHROPIC_API_KEY=sk-ant-...
# or for local/vLLM providers, configure via aiprovider/ YAML
```

## Starting the Service

```bash
nx bootRun agentos-service
```

The service starts on **port 8080**. Ready signal in logs: `Started AgentOSApplication`.

## Filesystem Plugin Directories

When running with the filesystem plugin, place YAML files in the directories resolved by the plugin (see `plugin-system.md`). The default paths are relative to the working directory:

```
agents/          <- AgentDefinition YAML files
aimodel/         <- AiModel YAML files
aiprovider/      <- AiProvider YAML files
plugins/         <- plugin JARs (loaded at startup)
```

## Spring Profiles

| Profile | Purpose |
|---|---|
| *(default)* | Full runtime with plugin loading |
| `test` | Used by integration tests — disables external calls |

Set with `--spring.profiles.active=<profile>` or `SPRING_PROFILES_ACTIVE` env var.
