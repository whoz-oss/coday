## Summary

This PR introduces a comprehensive **Prompts and Schedulers** system that replaces the previous webhook-based approach with a more flexible and user-friendly architecture for reusable AI tasks.

## Key Features

### 1. Prompt System
**Reusable AI task definitions** that can be executed:
- **Directly by users** through the web interface
- **Via schedulers** for automated execution
- **Via webhooks** for external API triggers (CODAY_ADMIN only)

**Key capabilities**:
- Flexible parameterization (simple string or structured key-value pairs)
- `{{PARAMETERS}}` placeholder for simple values
- `{{key}}` placeholders for structured parameters
- Auto-append mode when no placeholders present
- Strict validation with clear error messages
- Normalized naming (lowercase-with-hyphens)
- Collaborative editing (all users can edit all prompts)

### 2. Scheduler System
**Automated prompt execution** with interval-based scheduling:
- Minutes (1-59min), Hours (1-24h), Days (1-31d), Months (1-12M)
- Days of week restrictions (e.g., weekdays only)
- End conditions (occurrences or timestamp)
- Owner-based access control
- Manual "Run Now" for testing
- Automatic skip of missed executions on system restart

### 3. Web Interface
Complete Angular UI for management:
- **Prompt Manager**: CRUD operations, webhook toggle (admin), URL copy
- **Scheduler Manager**: CRUD operations, enable/disable, run now
- **Search functionality**: Filter by name, description, author
- **Visual indicators**: Owner badges, status badges, webhook status
- **Parameter modes**: Toggle between simple/structured in scheduler form
- **Error feedback**: Backend validation errors displayed in UI

### 4. Backend Architecture
- **PromptExecutionService**: Unified execution logic for all contexts
- **PromptService**: CRUD operations with access control
- **SchedulerService**: Lifecycle management with 30s check interval
- **REST API**: Complete CRUD endpoints for prompts and schedulers
- **Parameter processing**: Automatic detection and conversion
- **Validation**: Strict parameter validation with missing key detection

## Parameter System

The system supports three parameter modes:

### Simple Parameter (`{{PARAMETERS}}`)
```yaml
commands:
  - "Review PR {{PARAMETERS}}"
```
Execution: `executePrompt(promptId, "1234", username)`
Result: `"Review PR 1234"`

### Structured Parameters (`{{key}}`)
```yaml
commands:
  - "Deploy {{app}} to {{env}}"
```
Execution: `executePrompt(promptId, {app: "coday", env: "prod"}, username)`
Result: `"Deploy coday to prod"`

### Append Mode (no placeholders)
```yaml
commands:
  - "Analyze logs"
```
Execution: `executePrompt(promptId, "from last 24h", username)`
Result: `"Analyze logs from last 24h"`

## Access Control

### Prompts
- **Viewing/Editing**: All users (collaborative)
- **Webhook enable/disable**: CODAY_ADMIN only
- Visual indicators show creator with badge

### Schedulers
- **Owner**: Can view/edit/delete own schedulers
- **CODAY_ADMIN**: Can manage all schedulers
- Executions run with creator's identity

## Architecture Highlights

### Data Models
```typescript
interface Prompt {
  id: string
  name: string  // normalized: lowercase-with-hyphens
  description: string
  commands: string[]
  webhookEnabled: boolean  // CODAY_ADMIN only
  createdBy: string
  createdAt: string
  updatedAt?: string
}

interface Scheduler {
  id: string
  name: string
  enabled: boolean
  promptId: string
  schedule: IntervalSchedule
  parameters?: Record<string, unknown>
  createdBy: string
  lastRun?: string
  nextRun?: string | null
  occurrenceCount?: number
}
```

### Storage Structure
```
~/.coday/
  projects/
    my-project/
      prompts/
        {prompt-id}.yml
      schedulers/
        {scheduler-id}.yml
```

### API Endpoints
```
# Prompts
GET/POST    /api/projects/:project/prompts
GET/PUT/DELETE  /api/projects/:project/prompts/:id
POST/DELETE     /api/projects/:project/prompts/:id/webhook

# Schedulers
GET/POST    /api/projects/:project/schedulers
GET/PUT/DELETE  /api/projects/:project/schedulers/:id
POST        /api/projects/:project/schedulers/:id/enable
POST        /api/projects/:project/schedulers/:id/disable
POST        /api/projects/:project/schedulers/:id/run

# Webhook execution (unchanged)
POST        /api/webhooks/:promptId/execute
```

## UI Components

### Prompt Manager
- List with search (name, description, author)
- Create/Edit forms with command fields
- Webhook toggle and URL copy (admin only)
- Delete with confirmation
- Owner badges for visibility

### Scheduler Manager
- List with search (name, prompt, author)
- Create/Edit forms with:
  - Prompt selection dropdown
  - Start date/time picker
  - Interval configuration
  - Days of week selector
  - End condition options
  - Parameter mode toggle (simple/structured)
- Enable/Disable toggle
- Run Now button
- Status indicators (enabled/disabled, next run)

## Implementation Details

### Parameter Processing Logic
```typescript
// In PromptExecutionService.processCommands()
if (typeof parameters === 'string') {
  // Simple mode
  if (hasParametersPlaceholder) {
    // Replace {{PARAMETERS}} in ALL commands
  } else if (hasOtherPlaceholders) {
    // Error: structured placeholders need object
  } else {
    // Append to first command only
  }
} else if (typeof parameters === 'object') {
  // Structured mode: interpolate all {{key}} placeholders
}

// Final validation: check for remaining {{...}}
// Throw error with list of missing keys if any remain
```

### Scheduler Execution Flow
1. SchedulerService checks every 30 seconds
2. Finds schedulers with `nextRun` <= now
3. Converts parameters (detect {PARAMETERS: "value"} → string)
4. Calls PromptExecutionService.executePrompt()
5. Updates lastRun, increments occurrenceCount
6. Calculates nextRun (respecting daysOfWeek, endCondition)
7. Logs execution result

### Error Handling
- Parameter validation errors return HTTP 422 with clear messages
- Missing placeholders: "Missing required parameters: key1, key2"
- Type mismatch: "Prompt contains structured placeholders. Use an object parameter."
- Errors displayed in UI alerts with full backend message

## Files Added/Modified

### New Files
- `libs/model/src/lib/prompt.ts` - Prompt data model
- `libs/model/src/lib/scheduler.ts` - Scheduler data model
- `libs/service/src/lib/prompt.service.ts` - Prompt CRUD
- `libs/service/src/lib/scheduler.service.ts` - Scheduler lifecycle
- `libs/service/src/lib/prompt-execution.service.ts` - Unified execution
- `libs/utils/src/lib/interval-schedule.utils.ts` - Interval parsing
- `apps/server/src/lib/prompt.routes.ts` - Prompt REST API
- `apps/server/src/lib/scheduler.routes.ts` - Scheduler REST API
- `apps/server/src/lib/prompt-execution.routes.ts` - Webhook execution
- `apps/client/src/app/components/prompt-manager/` - Prompt UI
- `apps/client/src/app/components/prompt-form/` - Prompt form
- `apps/client/src/app/components/scheduler-manager/` - Scheduler UI
- `apps/client/src/app/components/scheduler-form/` - Scheduler form
- `apps/client/src/app/core/services/prompt-api.service.ts` - Prompt API client
- `apps/client/src/app/core/services/scheduler-api.service.ts` - Scheduler API client
- `docs/04-configuration/prompts.md` - Complete prompt documentation
- `docs/04-configuration/schedulers.md` - Complete scheduler documentation

### Modified Files
- `libs/handler/src/lib/prompt-chain.handler.ts` - Updated for new pattern
- `apps/server/src/server.ts` - Services initialization
- `apps/client/src/app/components/floating-menu/` - Menu integration
- Various service and model updates

### Removed Files
- `docs/04-configuration/webhooks.md` - Replaced by prompts.md
- Legacy webhook CLI handlers (replaced by UI)

## Testing

- ✅ Parameter interpolation (simple, structured, append modes)
- ✅ Validation (missing parameters, type mismatches)
- ✅ Scheduler execution (intervals, daysOfWeek, end conditions)
- ✅ Missed execution handling (skip and recalculate)
- ✅ Access control (owner, CODAY_ADMIN)
- ✅ UI workflows (CRUD, search, filtering)
- ✅ Error display (backend messages in UI)
- ✅ Webhook execution (backward compatible)

## Breaking Changes

⚠️ **Webhook system replaced**: The previous webhook-based approach is now the prompt system. External integrations using `/api/webhooks/:uuid/execute` continue to work but should be updated to use prompt IDs.

⚠️ **Parameter format change**: Webhook templates now support both simple string and structured object parameters.

## Migration Guide

### For Existing Webhooks
1. Review existing webhook configurations
2. Create equivalent prompts through web UI
3. Enable webhook flag (CODAY_ADMIN)
4. Update external integrations with new prompt IDs
5. Test thoroughly before removing old webhooks

### For Scheduled Tasks
1. Create prompts for your automated tasks
2. Create schedulers with appropriate intervals
3. Set parameters (simple or structured)
4. Enable and test with "Run Now"
5. Monitor execution via logs and UI

## Documentation

Complete documentation added:
- `docs/04-configuration/prompts.md` - Prompt system guide
- `docs/04-configuration/schedulers.md` - Scheduler system guide

Includes:
- Architecture and concepts
- Parameter modes and validation
- Access control details
- Usage examples
- API reference
- Troubleshooting guide
- Best practices

## Related

Closes #411

## Checklist

- [x] Code compiles without errors
- [x] Linter passes
- [x] Parameter system implemented and validated
- [x] Scheduler system implemented and tested
- [x] Web UI complete (prompts + schedulers)
- [x] REST API complete with validation
- [x] Access control implemented
- [x] Error messages propagate to UI
- [x] Documentation complete (prompts.md, schedulers.md)
- [x] Migration guide provided
- [x] Backward compatibility maintained
