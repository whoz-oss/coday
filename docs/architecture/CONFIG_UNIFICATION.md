# Configuration Unification - Architecture Document

## Context

Issue: #410 - Unify configuration structure with stackable CodayConfig

## Problem Statement

Currently, Coday has three different configuration types with significant duplication:
- `ProjectDescription` (coday.yaml in project root)
- `ProjectLocalConfig` (~/.coday/projects/{name}/project.yaml)
- `UserConfig` (~/.coday/users/{username}/user.yaml)

This creates:
- Structural redundancy (`ai`, `mcp`, `agents` repeated across types)
- Semantic ambiguity (user config mixes global and per-project settings)
- Maintenance burden (changes need to be replicated)
- Confusion about configuration hierarchy and precedence

## Solution: Unified CodayConfig

### Configuration Stacking Order

Configurations are merged in this order (lowest to highest priority):

1. **USER GLOBAL** (`~/.coday/users/{username}/user.yaml`)
2. **CODAY.YAML** (versioned in project root)
3. **PROJECT LOCAL** (`~/.coday/projects/{name}/project.yaml`)
4. **USER PROJECT** (`~/.coday/users/{username}/user.yaml > projects[name]`)

This allows:
- Users to define default preferences (API keys, favorite tools)
- Projects to define required configurations (integrations, MCP servers)
- Local overrides for non-versioned settings (secrets, local paths)
- User-specific customizations per project (preferred agent, custom context)

### New Type: CodayConfig

See `libs/model/coday-config.ts` for the complete interface.

Key features:
- **Unified structure**: Same type used at all levels
- **`context` field**: Replaces `description` and `bio` with a single concept
- **Clean interface**: No project metadata "warts" (path, storage, volatile, etc.)
- **Deprecated fields**: Old fields marked for future removal but kept for compatibility
- **Merge function**: `mergeCodayConfigs()` for intelligent merging
- **Normalization**: `normalizeCodayConfig()` maps deprecated fields to modern equivalents

### Separation of Concerns: Config vs Metadata

Project-specific metadata (path, storage, volatile, createdAt) is separated from configuration:

```typescript
interface Project {
  name: string
  root: string
  config: CodayConfig      // Configuration (stackable)
  metadata?: {              // Metadata (not stackable)
    path: string
    storage?: StorageConfig
    volatile?: boolean
    createdAt?: number
  }
}
```

## Migration Path

### Phase 1: Create New Types ✅

- [x] Create `CodayConfig` interface
- [x] Add merge and normalization functions
- [x] Document architecture

### Phase 2: Refactor Core Types

- [ ] Update `Project` entity to use `CodayConfig`
- [ ] Separate metadata from configuration
- [ ] Create migration utilities

### Phase 3: Update Services

- [ ] ProjectService: Work with `Project` entities
- [ ] UserService: Use `CodayConfig` structure
- [ ] ProjectStateService: Manage `Project` entities
- [ ] AiConfigService: Simplify using unified config
- [ ] McpConfigService: Use unified merge logic

### Phase 4: Update Loaders

- [ ] Refactor `loadOrInitProjectDescription` → `loadCodayYaml`
- [ ] Create `buildSystemContext()` function for context merging
- [ ] Update repositories to work with `Project` entities

### Phase 5: Update Handlers

- [ ] HandlerLooper: Use `Project` entity
- [ ] CommandContext: Access config via `project.config`
- [ ] All handlers: Update to new structure

### Phase 6: Update Frontend

- [ ] ConfigApiService: Use `CodayConfig` everywhere
- [ ] JsonEditorComponent: Simplify with unified structure
- [ ] Update state services

### Phase 7: Migration & Testing

- [ ] Create migration functions
- [ ] Add migration tests
- [ ] Document migration path for users
- [ ] Comprehensive testing

## Implementation Details

### Context Building

The system context (sent to agents) is built by merging contexts from all levels:

```typescript
function buildSystemContext(
  userGlobalConfig: CodayConfig,
  codayYamlConfig: CodayConfig,
  projectLocalConfig: CodayConfig,
  userProjectConfig: CodayConfig,
  userData: UserData
): string {
  // Merge configs in order
  const merged = mergeCodayConfigs(
    userGlobalConfig,
    codayYamlConfig,
    projectLocalConfig,
    userProjectConfig
  )
  
  // Add user information
  let context = merged.context || ''
  context += `\n\n## User\n\n`
  context += `    You are interacting with: ${userData.username}\n`
  if (userData.bio) {
    context += `\n    ${userData.bio}`
  }
  
  return context
}
```

### Configuration Merging

The `mergeCodayConfigs()` function implements intelligent merging:

- **Simple properties**: Last value wins (e.g., `defaultAgent`)
- **Context**: Concatenated with separator
- **AI providers**: Merged by name, models merged by name
- **MCP servers**: Merged by id
- **Integrations**: Merged by key
- **Deprecated fields**: Simple override (no merge)

### Backward Compatibility

Old configuration files are supported through:

1. **Deprecated fields**: Kept in type with `@deprecated` tag
2. **Normalization**: `normalizeCodayConfig()` maps old fields to new
3. **Migration on load**: Configs converted at load time (no file modification)
4. **Type aliases**: Old types kept as deprecated aliases

## Benefits

1. **Simplicity**: One config type instead of three
2. **Clarity**: Clear stacking order and precedence
3. **Maintainability**: Changes in one place
4. **Consistency**: Same structure for all levels
5. **Flexibility**: Easy to add new configuration options
6. **Clean separation**: Config vs metadata properly separated

## Risks & Mitigations

### Risk: Data Loss During Migration

**Mitigation**:
- Migration functions thoroughly tested
- Migration happens at load time (no file modification initially)
- Backup strategy documented for users
- Rollback mechanism available

### Risk: Breaking Changes

**Mitigation**:
- Backward compatibility maintained
- Deprecated fields supported
- Gradual migration path
- Version flags for controlled rollout

### Risk: Context Building Complexity

**Mitigation**:
- Clear documentation of stacking order
- Comprehensive tests for merge logic
- Examples for common scenarios
- Debug mode to inspect merged config

## Timeline

- **Phase 1**: ✅ Complete
- **Phase 2-4**: ~3-5 days (Backend)
- **Phase 5-6**: ~2-3 days (Frontend)
- **Phase 7**: ~2-3 days (Testing & Documentation)

**Total estimated effort**: 7-11 days

## Open Questions

1. **Agent migration**: Force migration out of config files now or defer?
2. **MCP at user global**: Keep current model or rethink?
3. **Migration timing**: On-the-fly, CLI command, or automatic?
4. **Support duration**: How long to maintain backward compatibility?

## References

- Issue: #410
- Implementation: `libs/model/coday-config.ts`
- Migration analysis: See exchange files for detailed breakdown
- Related: Configuration masking service, config handlers

## Approval

- [x] Architecture reviewed
- [ ] Implementation plan approved
- [ ] Migration strategy validated
- [ ] Timeline confirmed

---

*Document created: 2024-12-20*
*Last updated: 2024-12-20*
*Status: Draft - Awaiting approval*
