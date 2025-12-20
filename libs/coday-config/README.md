# @coday/coday-config

Unified configuration structure for Coday with intelligent merging capabilities.

## Overview

This library provides the `CodayConfig` interface and related utilities for managing Coday's configuration across multiple levels:

1. **USER GLOBAL** (`~/.coday/users/{username}/user.yaml`)
2. **CODAY.YAML** (versioned in project root)
3. **PROJECT LOCAL** (`~/.coday/projects/{name}/project.yaml`)
4. **USER PROJECT** (`~/.coday/users/{username}/user.yaml > projects[name]`)

## Key Features

- **Unified interface**: Same `CodayConfig` type used at all levels
- **UserConfig extension**: Specialized type for user-level config with per-project overrides
- **Intelligent merging**: Smart merge logic for AI providers, MCP servers, integrations
- **Context stacking**: Concatenates contexts from all levels
- **Well-tested**: 98%+ code coverage with comprehensive unit tests

## Installation

This is an internal library used by other Coday packages:

```typescript
import { CodayConfig, UserConfig, mergeCodayConfigs } from '@coday/coday-config'
```

## Usage

### Basic Configuration

```typescript
const config: CodayConfig = {
  version: 1,
  context: 'I am a TypeScript developer',
  ai: [
    {
      name: 'openai',
      apiKey: 'sk-xxx',
      models: [{ name: 'gpt-4o', alias: 'BIG', contextWindow: 128000 }],
    },
  ],
  mcp: {
    servers: [
      {
        id: 'filesystem',
        name: 'Filesystem',
        command: 'npx',
        args: ['@modelcontextprotocol/server-filesystem', '/home/user'],
      },
    ],
  },
  defaultAgent: 'sway',
}
```

### User Configuration with Per-Project Overrides

```typescript
const userConfig: UserConfig = {
  version: 1,
  context: 'I am Vincent, a software engineer',
  ai: [{ name: 'openai', apiKey: 'sk-user-xxx' }],
  
  // Per-project overrides
  projects: {
    'coday': {
      version: 1,
      context: 'Focus on TypeScript best practices',
      defaultAgent: 'sway',
    },
    'my-app': {
      version: 1,
      ai: [{ name: 'anthropic', apiKey: 'sk-ant-xxx' }],
    },
  },
}
```

### Merging Configurations

```typescript
const userGlobal: CodayConfig = {
  version: 1,
  context: 'I am Vincent',
  ai: [{ name: 'openai', apiKey: 'sk-user-xxx' }],
}

const codayYaml: CodayConfig = {
  version: 1,
  context: 'This is Coday project',
  ai: [
    {
      name: 'openai',
      models: [{ name: 'gpt-4o', alias: 'BIG', contextWindow: 128000 }],
    },
  ],
}

const projectLocal: CodayConfig = {
  version: 1,
  integrations: {
    github: { apiKey: 'ghp_local_xxx' },
  },
}

// Extract user-project config
const userProject: CodayConfig | undefined = userConfig.projects?.['coday']

// Merge configs (later configs override earlier ones)
const merged = mergeCodayConfigs(userGlobal, codayYaml, projectLocal, userProject)

// Result:
// {
//   version: 1,
//   context: "I am Vincent\n\n---\n\nThis is Coday project\n\n---\n\nFocus on TypeScript best practices",
//   ai: [{
//     name: 'openai',
//     apiKey: 'sk-user-xxx',
//     models: [{ name: 'gpt-4o', alias: 'BIG', contextWindow: 128000 }]
//   }],
//   integrations: {
//     github: { apiKey: 'ghp_local_xxx' }
//   },
//   defaultAgent: 'sway'
// }
```

## Merge Rules

### Context
Concatenated with separator: `\n\n---\n\n`

### AI Providers
- Merged by `name`
- Provider properties: last value wins
- Models: merged by `name` within provider
- Model properties: last value wins

### MCP Servers
- Merged by `id`
- Server properties: last value wins

### Integrations
- Merged by key
- Integration properties: last value wins

### Simple Properties
- `defaultAgent`: last value wins

### Projects Property (UserConfig only)
The `projects` property is **never merged**. It only exists at the user global level and is used to extract per-project configs before merging.

## API Reference

### `CodayConfig`

Main configuration interface used at all levels. Contains:
- `version`: Configuration version number
- `context`: Contextual information (bio, description, guidelines)
- `ai`: AI provider configurations
- `mcp`: MCP server configurations
- `integrations`: Integration configurations (Slack, GitHub, etc.)
- `defaultAgent`: Default agent name

### `UserConfig extends CodayConfig`

User-level configuration with additional `projects` property for per-project overrides.

### `mergeCodayConfigs(...configs: (CodayConfig | undefined | null)[]): CodayConfig`

Merge multiple configurations according to stacking order. Later configs override earlier ones.

**Parameters:**
- `configs` - Array of configs to merge, in order of increasing priority

**Returns:**
- Merged configuration

**Example:**
```typescript
const merged = mergeCodayConfigs(
  userGlobal,
  codayYaml,
  projectLocal,
  userProjectOverride
)
```

### `DEFAULT_CODAY_CONFIG`

Default empty configuration with version 1.

## Configuration Levels

### User Global (`~/.coday/users/{username}/user.yaml`)
- Type: `UserConfig`
- Contains global user preferences (API keys, bio)
- Can define per-project overrides via `projects` property
- Lowest priority in merge order

### Coday.yaml (project root)
- Type: `CodayConfig`
- Versioned with the project
- Contains project description, required integrations, MCP servers
- Second priority in merge order

### Project Local (`~/.coday/projects/{name}/project.yaml`)
- Type: `CodayConfig`
- Local, non-versioned overrides
- Contains secrets, local paths, storage configuration
- Third priority in merge order

### User Project (`user.yaml > projects[name]`)
- Type: `CodayConfig`
- User-specific overrides for a specific project
- Highest priority in merge order

## Testing

Run tests:

```bash
nx test coday-config
```

Current coverage: **98.03%** (25 tests)

## Migration from Old Config Types

This library does **not** handle migration from deprecated configuration types (`ProjectDescription`, `ProjectLocalConfig`, old `UserConfig`). Migration logic should be implemented at the application level when loading configurations.

## Related Documentation

- [Configuration Unification Architecture](../../docs/architecture/CONFIG_UNIFICATION.md)
- [Migration Analysis](../../exchange/MIGRATION_ANALYSIS.md) (if available)
- [GitHub Issue #410](https://github.com/whoz-oss/coday/issues/410)

## License

See main Coday repository for license information.
