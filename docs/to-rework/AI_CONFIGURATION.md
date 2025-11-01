# 🤖 AI Configuration

Coday's AI capabilities are powered by configurable AI providers that can be set at different levels (user, project, or global). This document explains how to configure and manage AI providers and models.

## Configuration Levels

AI configuration follows Coday's hierarchical configuration system with three levels of precedence:

1. **User Level** (highest precedence) - Personal configurations in `~/.coday/user.yml`
2. **Project Level** (medium precedence) - Project-specific configurations in `.coday.yml`
3. **Global Level** (lowest precedence) - Default configurations from `coday.yaml`

Higher precedence levels override lower ones, allowing for flexible configuration management.

### Level Selection

All AI configuration commands default to **user level** and support the `--project` (or `-p`) flag for project-level operations:

- **User level** (default): Personal settings that apply across all your projects
- **Project level** (with `--project`): Settings specific to the current project, shared with team members

This design ensures that personal API keys remain private by default while allowing project-specific provider configurations when needed.

## Configuration Commands

All AI configuration commands support level selection through flags:
- **User level** (default): Personal configurations that apply across all projects
- **Project level**: Use `--project` or `-p` flag for project-specific configurations

### Basic Configuration

The `config ai` command shows available AI configuration subcommands:

```bash
# View available AI configuration commands
coday config ai
```

This displays the available subcommands which provide interfaces to:
- List configured providers (`list`)
- Add new AI providers (`add`)
- Edit existing providers (`edit`)
- Configure API keys (`apikey`)
- Manage models (`model`)
- Delete providers (`delete`)

Each subcommand supports level selection through the `--project/-p` flag.

### Advanced Commands

```bash
# List all configured AI providers (shows all levels)
coday config ai list

# Add a new provider (user level by default)
coday config ai add
coday config ai add --project    # Add at project level

# Edit an existing provider (user level by default)
coday config ai edit [provider-name]
coday config ai edit [provider-name] --project    # Edit at project level

# Delete a provider (user level by default)
coday config ai delete [provider-name]
coday config ai delete [provider-name] --project    # Delete at project level

# Configure API keys only (user level by default)
coday config ai apikey [provider-name]
coday config ai apikey [provider-name] --project    # Set API key at project level
```

### Level Selection Examples

```bash
# View available commands
coday config ai                        # Shows subcommand list

# User-level operations (default behavior)
coday config ai add                     # Add provider to user config
coday config ai edit anthropic          # Edit user-level anthropic config
coday config ai apikey openai           # Set user-level OpenAI API key

# Project-level operations (using --project or -p flag)
coday config ai add --project           # Add provider to project config
coday config ai edit anthropic -p       # Edit project-level anthropic config
coday config ai apikey openai --project # Set project-level OpenAI API key
```

## Supported Providers

### Built-in Providers

| Provider  | Type | Description | Status |
|-----------|------|-------------|--------|
| Anthropic | `anthropic` | Claude models (recommended) | ✨ |
| OpenAI | `openai` | GPT models | ✅ |
| Google | `google` | Gemini models | 🚧 |

### Provider Configuration Structure

Each AI provider configuration includes:

```typescript
interface AiProviderConfig {
  name: string          // Provider identifier
  type?: AiProviderType // 'openai' | 'anthropic'
  url?: string          // Custom API endpoint
  apiKey?: string       // API authentication key
  secure?: boolean      // Indicates secure/private infrastructure
  models?: AiModel[]    // Custom model definitions
}
```

### Model Configuration Structure

Models can be configured with detailed specifications:

```typescript
interface AiModel {
  name: string              // Model name as per API
  alias?: string           // Project-friendly alias
  contextWindow: number    // Token limit
  thinking?: any          // Thinking model properties
  price?: {               // Pricing per million tokens
    inputMTokens?: number
    cacheWrite?: number
    cacheRead?: number
    outputMTokens?: number
  }
}
```

## Configuration Examples

### Basic Provider Setup

When using built-in providers (anthropic, openai), minimal configuration is required:

```yaml
# User or project config
ai:
  - name: anthropic
    apiKey: "sk-ant-xxx"
  - name: openai
    apiKey: "sk-xxx"
```

### Custom Provider Configuration

For custom endpoints or additional models:

```yaml
ai:
  - name: custom-openai
    type: openai
    url: "https://api.custom.com/v1"
    apiKey: "custom-key"
    secure: true
    models:
      - name: gpt-4-custom
        alias: "my-gpt4"
        contextWindow: 128000
        price:
          inputMTokens: 30.0
          outputMTokens: 60.0
```

### Model Aliases

Model aliases allow you to reference models with friendly names:

```yaml
ai:
  - name: anthropic
    models:
      - name: claude-3-5-sonnet-20241022
        alias: "claude-sonnet"
        contextWindow: 200000
      - name: claude-3-5-haiku-20241022
        alias: "claude-haiku"
        contextWindow: 200000
```

## Environment Variable Override

Environment variables can temporarily override configured API keys:

```bash
# Temporary override (only works if provider is already configured)
export ANTHROPIC_API_KEY="sk-ant-override"
export OPENAI_API_KEY="sk-override"
export GEMINI_API_KEY="override-key"
```

> ⚠️ **Important**: Environment variables only override existing configurations. The provider must be configured first through the config commands.

## Configuration Merging

When the same provider is configured at multiple levels, configurations are merged with the following rules:

1. **Provider-level properties** (apiKey, url, etc.) from higher precedence levels override lower ones
2. **Models** are merged by name/alias - higher precedence models override lower ones
3. **Model properties** are merged individually - higher precedence properties override lower ones

### Example Merging Scenario

**Global config (coday.yaml):**
```yaml
ai:
  - name: anthropic
    models:
      - name: claude-3-5-sonnet-20241022
        alias: "claude"
        contextWindow: 200000
```

**User config:**
```yaml
ai:
  - name: anthropic
    apiKey: "user-key"
    models:
      - name: claude-3-5-sonnet-20241022
        price:
          inputMTokens: 3.0
          outputMTokens: 15.0
```

**Resulting merged configuration:**
```yaml
ai:
  - name: anthropic
    apiKey: "user-key"  # From user config
    models:
      - name: claude-3-5-sonnet-20241022
        alias: "claude"           # From global config
        contextWindow: 200000     # From global config
        price:                   # From user config
          inputMTokens: 3.0
          outputMTokens: 15.0
```

## Best Practices

### Configuration Level Selection
- **User level (default)**: Use for personal API keys and provider preferences
- **Project level**: Use only when specific providers/models are required for the project
- **Team collaboration**: Project-level configs are shared; ensure team members have access to specified providers
- **API key management**: Generally prefer user-level API keys to avoid sharing credentials

### Security
- Store API keys at the user level for personal projects
- Use project-level configuration only when necessary for team collaboration
- Never commit API keys to version control
- Consider using environment variables for CI/CD environments
- Use the `--project` flag judiciously - project configs are shared with team members

### Model Management
- Use aliases for commonly referenced models
- Configure pricing information for cost monitoring
- Set appropriate context windows to optimize performance
- Define project-specific model aliases when needed for consistency across team

### Provider Organization
- Use descriptive names for custom providers
- Mark secure/private providers with the `secure` flag
- Group related models under appropriate providers
- Consider project-level configuration for custom/internal providers that all team members should use

## Troubleshooting

### Common Issues

**Provider not found:**
- Ensure the provider is configured at the appropriate level
- Check configuration syntax and indentation
- Verify the provider name matches exactly

**API key not working:**
- Confirm the API key is valid and has necessary permissions
- Check if environment variables are overriding configuration
- Ensure the provider type matches the API service

**Model not available:**
- Verify the model name matches the provider's API specification
- Check if the model is properly configured with contextWindow
- Ensure aliases are unique across all providers

## Integration with Agents

Once configured, AI providers and models can be referenced in agent definitions:

```yaml
# In agent configuration
agents:
  - name: MyAgent
    provider: anthropic # can be optional if alias over several providers
    modelName: claude-sonnet  # Using alias
```

See [Agent Definitions](AGENT_DEFINITIONS.md) for more details on agent configuration.