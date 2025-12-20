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
- **Intelligent merging**: Smart merge logic for AI providers, MCP servers, integrations
- **Context stacking**: Concatenates contexts from all levels
- **Backward compatibility**: Deprecated fields mapped automatically
- **Well-tested**: 98%+ code coverage with comprehensive unit tests

## Installation

This is an internal library used by other Coday packages:

```typescript
import { CodayConfig, mergeCodayConfigs, normalizeCodayConfig } from '@coday/coday-config'
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

### Merging Configurations

```typescript
const userConfig: CodayConfig = {
  version: 1,
  context: 'I am Vincent',
  ai: [{ name: 'openai', apiKey: 'sk-user-xxx' }],
}

const projectConfig: CodayConfig = {
  version: 1,
  context: 'This is Coday project',
  ai: [
    {
      name: 'openai',
      models: [{ name: 'gpt-4o', alias: 'BIG', contextWindow: 128000 }],
    },
  ],
}

// Merge configs (later configs override earlier ones)
const merged = mergeCodayConfigs(userConfig, projectConfig)

// Result:
// {
//   version: 1,
//   context: "I am Vincent\n\n---\n\nThis is Coday project",
//   ai: [{
//     name: 'openai',
//     apiKey: 'sk-user-xxx',
//     models: [{ name: 'gpt-4o', alias: 'BIG', contextWindow: 128000 }]
//   }]
// }
```

### Normalizing Deprecated Fields

```typescript
const oldConfig: CodayConfig = {
  version: 1,
  description: 'Project description', // deprecated
  bio: 'User bio', // deprecated
}

const normalized = normalizeCodayConfig(oldConfig)

// Result:
// {
//   version: 1,
//   context: "Project description\n\n---\n\nUser bio",
//   description: 'Project description', // preserved for compatibility
//   bio: 'User bio' // preserved for compatibility
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
- Deprecated fields: last value wins (no merge)

## API Reference

### `CodayConfig`

Main configuration interface. See TypeScript definitions for complete structure.

### `mergeCodayConfigs(...configs: (CodayConfig | undefined | null)[]): CodayConfig`

Merge multiple configurations according to stacking order. Later configs override earlier ones.

**Parameters:**
- `configs` - Array of configs to merge, in order of increasing priority

**Returns:**
- Merged configuration

### `normalizeCodayConfig(config: CodayConfig): CodayConfig`

Normalize a config by mapping deprecated fields to their modern equivalents.

**Parameters:**
- `config` - Configuration to normalize

**Returns:**
- Normalized configuration

### `DEFAULT_CODAY_CONFIG`

Default empty configuration with version 1.

## Testing

Run tests:

```bash
nx test coday-config
```

Current coverage: **98.52%**

## Related Documentation

- [Configuration Unification Architecture](../../docs/architecture/CONFIG_UNIFICATION.md)
- [Migration Analysis](../../exchange/MIGRATION_ANALYSIS.md) (if available)
- [GitHub Issue #410](https://github.com/whoz-oss/coday/issues/410)

## License

See main Coday repository for license information.
