# Study: Package-Based Evolution of Coday

## Context

Currently, Coday operates as a single local application that users must clone and run locally, configuring it to work
with their various project repositories. This study explores the evolution of Coday towards a more flexible,
package-based architecture that could support multiple deployment patterns.

## Deployment Patterns

Three main usage patterns were identified:

1. **Local Installation (Current)**
    - Full clone of Coday repository
    - Configuration for multiple projects
    - Local tool access
    - Best for Coday development and power users

2. **NPM Dev Dependency**
    - Coday installed as dev dependency in projects
    - Automatic project context (no selection needed)
    - User configuration still in ~/.coday
    - Simpler setup for single-project usage

3. **Server Deployment**
    - Hosted Coday instance
    - Web/Slack/Teams interfaces
    - Challenges with project file access
    - Potential solution: run as dev dependency in CI/CD

## Package Architecture

### Core Package Structure

The proposal is to split Coday into a core package and optional integration packages:

- `@coday/core`: Essential framework and AI capabilities
- `@coday/web`: Web interface and Express server
- `@coday/slack`: Future Slack integration
- `@coday/teams`: Future Teams integration

### Integration Management

Optional integrations would be detected and loaded at runtime through package existence checks:

```typescript
// In @coday/core's main entry
if (existsSync('./node_modules/@coday/web')) {
  const { WebIntegration } = await import('@coday/web')
  coday.registerIntegration(new WebIntegration())
}
```

This pattern allows:

- Dynamic loading of optional features
- Clean separation of concerns
- Reduced bundle size for basic usage
- Flexibility in deployment options

### Monorepo Structure

The proposed repository structure follows the yarn workspaces pattern:

```
/
├── packages/
│   ├── core/             # @coday/core package
│   │   ├── package.json
│   │   ├── src/
│   │   └── tsconfig.json
│   │
│   ├── web/             # @coday/web package
│   │   ├── package.json  # with @coday/core as peer dependency
│   │   ├── src/
│   │   └── tsconfig.json
│   │
│   └── slack/           # future integrations
│
├── package.json         # Workspace root
├── tsconfig.base.json   # Shared TS config
└── yarn.lock
```

This structure is:

- Well-established in the JavaScript ecosystem
- Supported natively by package managers
- Simpler than alternatives like Nx
- Easy to understand and maintain

### Key Considerations

1. **Package Management**
    - Clear definition of peer vs direct dependencies
    - Version management across packages
    - Proper .npmignore configuration

2. **Configuration**
    - Automatic project context in dev dependency mode
    - Integration-specific configuration schemas
    - Maintaining user configuration in ~/.coday

3. **Integration API**
    - Clear public API for integrations
    - Event system for cross-package communication
    - Plugin registration system

## Tools Consideration

While more complex tools like Nx were considered, the recommendation is to start with basic yarn workspaces for:

- Simplicity and maintainability
- Native package manager support
- Reduced development friction

Additional tools could be evaluated later if needed:

- Turborepo for build caching
- Changesets for version management
- Lerna for advanced publishing needs

## Next Steps

1. Create basic monorepo structure
2. Move current code to core package
3. Extract web interface to separate package
4. Establish integration API patterns
5. Set up build and test workflows
6. Document package usage patterns