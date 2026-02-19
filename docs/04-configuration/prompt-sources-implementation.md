# Prompt Sources Implementation - Dual Storage Support

## Overview

This document describes the implementation of dual storage support for prompts, allowing them to be stored either locally (user-specific) or in the project directory (committable).

## Implementation Date

**Feature Branch**: `feat/vincent.audibert/#411-scheduled-tasks-and-move-webhooks`  
**Implementation**: January 2025

## Architecture

### Storage Locations

**1. Local Storage** (default, user-specific):
```
~/.coday/projects/{projectName}/prompts/{id}.yml
```

**2. Project Storage** (committable, shared):
```
{projectPath}/prompts/{id}.yml  (next to coday.yaml)
```

### Key Design Decisions

1. **UUID-based IDs**: No conflict possible between local and project prompts
2. **Source chosen at creation**: User selects `local` (default) or `project`
3. **Immutable source**: Cannot be changed after creation
4. **Automatic localization**: Service finds prompts in either location
5. **Git responsibility**: User decides what to commit

## Backend Implementation

### 1. Model Changes (`libs/model/src/lib/prompt.ts`)

```typescript
export type PromptSource = 'local' | 'project'

export interface Prompt {
  // ... existing fields
  source: PromptSource
}

export interface PromptInfo {
  // ... existing fields
  source: PromptSource
}
```

### 2. Service Changes (`libs/service/src/lib/prompt.service.ts`)

**Constructor**:
```typescript
constructor(codayConfigPath?: string, projectPath?: string)
```

**Key Methods**:
- `getPromptsDir(projectName, source)`: Returns directory for given source
- `findPromptSource(projectName, id)`: Locates prompt automatically
- `create(projectName, prompt, source)`: Creates with chosen source
- `get(projectName, id)`: Auto-finds in either source
- `update(projectName, id, updates)`: Updates in original location
- `delete(projectName, id)`: Deletes from original location
- `list(projectName)`: Lists from both sources with source indicator

**Project Path Resolution**:
```typescript
// Find coday.yaml location (same as agents)
const codayFiles = await findFilesByName({ text: 'coday.yaml', root: this.projectPath })
const codayFolder = path.dirname(codayFiles[0]!)
return path.join(this.projectPath, codayFolder, 'prompts')
```

### 3. API Changes (`apps/server/src/lib/prompt.routes.ts`)

**POST `/api/projects/:projectName/prompts`**:
- Accepts `source` in request body
- Validates source is 'local' or 'project'
- Defaults to 'local' if not specified

**PUT `/api/projects/:projectName/prompts/:id`**:
- Prevents modification of `source` field
- Returns 422 error if source change attempted

### 4. Server Initialization (`apps/server/src/server.ts`)

```typescript
// Resolve project path from project service
let projectPathForPrompts: string | undefined
if (resolvedProjectName) {
  const project = projectService.getProject(resolvedProjectName)
  if (project?.config.path) {
    projectPathForPrompts = project.config.path
  }
}

// Initialize prompt service with project path
const promptService = new PromptService(codayOptions.configDir, projectPathForPrompts)
```

## Frontend Implementation

### 1. Type Definitions (`apps/client/src/app/core/services/prompt-api.service.ts`)

```typescript
export type PromptSource = 'local' | 'project'

export interface Prompt {
  // ... existing fields
  source: PromptSource
}

export interface PromptInfo {
  // ... existing fields
  source: PromptSource
}
```

### 2. Form Component (`apps/client/src/app/components/prompt-form/`)

**Creation Mode**:
- Radio buttons to choose storage location
- Visual representation with icons (👤 Local, 📦 Project)
- Clear descriptions for each option
- Default selection: `local`

**Edit Mode**:
- Source displayed as read-only badge
- Clear indication that source cannot be changed
- Color-coded badges (blue for local, green for project)

**Component Logic**:
```typescript
source: PromptSource = 'local' // Default

// In create mode: include source in POST request
const promptData = {
  // ... other fields
  source: this.source
}

// In edit mode: source is loaded from existing prompt
this.source = this.data.prompt.source
```

### 3. Manager Component (`apps/client/src/app/components/prompt-manager/`)

**List Display**:
- Source badge next to prompt name (👤 or 📦)
- Tooltip explaining storage location
- Owner badge for prompts created by others
- Prompt ID displayed discreetly in metadata

**Visual Design**:
```scss
.source-badge {
  &.badge-local {
    background: rgba(33, 150, 243, 0.1);
    border: 1px solid rgba(33, 150, 243, 0.3);
  }
  
  &.badge-project {
    background: rgba(76, 175, 80, 0.1);
    border: 1px solid rgba(76, 175, 80, 0.3);
  }
}
```

## User Experience

### Creation Workflow

1. User clicks "Create Prompt"
2. Fills in name, description, commands
3. **Chooses storage location**:
   - 👤 **Local**: Personal, not committed (default)
   - 📦 **Project**: Shared with team, committable
4. Saves prompt
5. Storage location is now permanent

### List View

```
PR Review                                    [👤] [👤 john_doe]
└─ Review pull requests and provide feedback
   ID: abc-123-def-456  •  Webhook: Disabled

Deploy to Staging                            [📦]
└─ Deploy application to staging environment
   ID: def-456-ghi-789  •  Webhook: Enabled
```

### Edit Workflow

1. User clicks "Edit" on any prompt
2. Form shows all fields as editable
3. **Storage location shown as badge** (read-only)
4. Clear message: "Cannot be changed after creation"
5. Updates save to original location

## Benefits

### For Users

✅ **Flexibility**: Choose appropriate storage per prompt  
✅ **No confusion**: Clear visual indicators  
✅ **Collaboration**: Easy to share team templates  
✅ **Experimentation**: Local prompts for testing  

### For Teams

✅ **Version control**: Project prompts can be committed  
✅ **Best practices**: Share proven prompt templates  
✅ **Onboarding**: New team members get standard prompts  
✅ **Consistency**: Team-wide prompt standards  

### For Development

✅ **Clean architecture**: Service handles localization  
✅ **Type safety**: Full TypeScript support  
✅ **Backward compatible**: Existing prompts continue to work  
✅ **Extensible**: Easy to add more sources if needed  

## Migration Notes

### Existing Prompts

All existing prompts (created before this feature) will:
- Continue to work without modification
- Be treated as `local` prompts by default
- Have `source` field added automatically on first access
- No data loss or breaking changes

### Testing Checklist

- [ ] Create local prompt via UI
- [ ] Create project prompt via UI
- [ ] Verify both appear in list with correct badges
- [ ] Edit local prompt (should update local file)
- [ ] Edit project prompt (should update project file)
- [ ] Delete prompts from both sources
- [ ] Verify source field is immutable in edit mode
- [ ] Test with multiple prompts with same name but different IDs
- [ ] Verify project prompts appear in `./prompts/` directory
- [ ] Verify local prompts remain in `~/.coday/projects/.../prompts/`

## Future Enhancements

### Potential Improvements

1. **Export/Import**: Move prompts between sources manually
2. **Bulk Operations**: Convert multiple prompts at once
3. **Team Templates**: Pre-defined project prompts in coday.yaml
4. **Source Filter**: Filter list by storage location
5. **Usage Analytics**: Track which prompts are most used

### Considerations

- **Git conflicts**: Users must manage merge conflicts in project prompts
- **Permissions**: No file-level permissions (OS handles this)
- **Sync**: No automatic sync between sources (intentional)

## Related Documentation

- [Prompt System Documentation](./prompts.md)
- [Architecture Overview](../to-rework/ARCHITECTURE.md)
- [Handler Design Patterns](../to-rework/HANDLER_DESIGN.md)
