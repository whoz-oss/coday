# 🎛️ Handler Design Patterns

Coday's command processing follows consistent architectural patterns for maintainability and user experience.

## Core Patterns

### 1. Nested Handler Structure

Commands are organized hierarchically using `NestedHandler` containers:

```
config
├── ai
│   ├── add → edit
│   ├── edit  
│   ├── delete
│   └── model
│       ├── add → edit
│       ├── edit
│       └── delete
```

**Implementation**:
```typescript
export class ConfigHandler extends NestedHandler {
  constructor(interactor: Interactor, services: CodayServices) {
    super({ commandWord: 'config', description: '...' }, interactor)
    
    this.handlers = [
      new AiConfigHandler(interactor, services),
      new UserBioHandler(interactor, services)
    ]
  }
}
```

### 2. Add-Edit Delegation

Add handlers create default entities, then delegate to edit handlers for completion:

```typescript
export class AddHandler extends CommandHandler {
  constructor(private editHandler: EditHandler) { /* ... */ }
  
  async handle(command: string, context: CommandContext) {
    // Create default entity
    await service.create(defaultEntity)
    
    // Delegate to edit handler
    return this.editHandler.handle(`edit ${entityName}`, context)
  }
}
```

**Benefits**: Eliminates duplication, ensures consistency, single source of truth.

### 3. Standardized Argument Parsing

All handlers use `parseArgs` for consistent argument processing:

```typescript
const args = parseArgs(subCommand, [
  { key: 'provider' },              // --provider=value (no alias)
  { key: 'model', alias: 'm' },     // --model=value or -m
  { key: 'project', alias: 'p' }    // --project or -p (standard)
])

const isProject = !!args.project
const level = isProject ? ConfigLevel.PROJECT : ConfigLevel.USER
```

## Design Principles

### Explicitness Over Implicitness
- Use named arguments (`--provider=openai`) instead of positional
- Primary parameters have no aliases to enforce clarity
- Commands are self-documenting

### Consistency
- Standard `--project/-p` flag across all configuration handlers
- Uniform CRUD operations: `list`, `add`, `edit`, `delete`
- Consistent error handling and user feedback patterns

### Composability
- Handlers focus on single responsibilities
- Complex workflows built through delegation
- Nested structure scales with feature growth

## Implementation Template

```typescript
export class DomainOperationHandler extends CommandHandler {
  constructor(
    private interactor: Interactor,
    private services: CodayServices
  ) {
    super({
      commandWord: 'operation',
      description: 'Description. Use --param=value, --project/-p for project level.'
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    // 1. Parse arguments
    const args = parseArgs(this.getSubCommand(command), [
      { key: 'entity' },
      { key: 'project', alias: 'p' }
    ])
    
    // 2. Process business logic
    const level = !!args.project ? ConfigLevel.PROJECT : ConfigLevel.USER
    
    // 3. Provide feedback
    this.interactor.displayText('✅ Operation completed')
    return context
  }
}
```

This architecture creates maintainable, consistent command processing that scales with Coday's evolution while providing excellent user experience through explicit, discoverable interfaces.