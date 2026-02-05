# Scheduler System Documentation

## Overview

Coday's scheduler system enables automated execution of prompts at specified intervals. Schedulers provide a flexible way to run AI tasks periodically without manual intervention, perfect for:
- Regular code reviews
- Periodic log analysis
- Automated reporting
- Scheduled deployments
- Monitoring and alerting

Schedulers execute prompts with the identity and permissions of their creator, ensuring proper access control and audit trails.

## Architecture

### Key Concepts

- **Prompt-Based**: Schedulers execute predefined prompts
- **Interval-Based**: Flexible scheduling with minutes, hours, days, or months
- **User Context**: Executions run with the creator's identity
- **Access Control**: Owner or CODAY_ADMIN can manage schedulers
- **Project-Scoped**: Schedulers are stored per project

### Storage Architecture

```
~/.coday/
  projects/
    my-project/
      schedulers/
        {scheduler-id}.yml
      prompts/
        {prompt-id}.yml
```

Each scheduler is stored as a YAML file with a unique ID (UUID v4).

## Scheduler Model

### YAML Structure

```yaml
id: "sched-123-abc-456"
name: "Daily Staging Deploy"
enabled: true
promptId: "deploy-app-prompt-id"
schedule:
  startTimestamp: "2025-01-15T02:00:00.000Z"
  interval: "1d"
  daysOfWeek: [1, 2, 3, 4, 5]  # Monday to Friday
  endCondition:
    type: "occurrences"
    value: 100
parameters:
  app: "coday"
  env: "staging"
createdBy: "john_doe"
createdAt: "2025-01-15T10:00:00.000Z"
lastRun: "2025-01-16T02:00:00.000Z"
nextRun: "2025-01-17T02:00:00.000Z"
occurrenceCount: 1
```

### Field Descriptions

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique identifier (UUID v4) |
| `name` | string | Human-readable scheduler name |
| `enabled` | boolean | Whether scheduler is active |
| `promptId` | string | ID of the prompt to execute |
| `schedule` | object | Schedule configuration (see below) |
| `parameters` | object/undefined | Parameters to pass to prompt execution |
| `createdBy` | string | Username of creator (executions run with this identity) |
| `createdAt` | string | ISO 8601 creation timestamp |
| `lastRun` | string | ISO 8601 timestamp of last execution |
| `nextRun` | string/null | ISO 8601 timestamp of next execution (null if expired) |
| `occurrenceCount` | number | Number of times executed |

### Schedule Configuration

#### Interval Format

The `interval` field supports:
- **Minutes**: `1min` to `59min`
- **Hours**: `1h` to `24h`
- **Days**: `1d` to `31d`
- **Months**: `1M` to `12M`

Examples:
- `30min` - Every 30 minutes
- `2h` - Every 2 hours
- `1d` - Daily
- `1M` - Monthly

#### Schedule Object

```typescript
interface IntervalSchedule {
  startTimestamp: string        // ISO 8601 UTC timestamp
  interval: string              // Format: '2min', '5h', '14d', '1M'
  daysOfWeek?: number[]         // 0-6 (0=Sunday, 6=Saturday)
  endCondition?: {
    type: 'occurrences' | 'endTimestamp'
    value: number | string      // Number for occurrences, ISO 8601 for timestamp
  }
}
```

**Days of Week** (optional):
- If specified, scheduler only runs on these days
- Values: 0 (Sunday) through 6 (Saturday)
- Example: `[1, 2, 3, 4, 5]` = Monday to Friday

**End Condition** (optional):
- `occurrences`: Stop after N executions
- `endTimestamp`: Stop after a specific date/time

### Parameter Modes

Schedulers support two parameter modes:

#### Simple Mode
Single string value stored as `{PARAMETERS: "value"}`:
```yaml
parameters:
  PARAMETERS: "staging"
```

Passed to prompt as string: `"staging"`

#### Structured Mode
Key-value pairs for multiple parameters:
```yaml
parameters:
  app: "coday"
  env: "staging"
  version: "1.2.3"
```

Passed to prompt as object: `{app: "coday", env: "staging", version: "1.2.3"}`

## Access Control

### Ownership Model

- **Owner**: User who created the scheduler can view, edit, and delete it
- **CODAY_ADMIN**: Members of the CODAY_ADMIN group can manage ALL schedulers in ALL projects
- **Other users**: Cannot see or modify schedulers they don't own

### Execution Identity

All scheduled executions run with the creator's identity (`createdBy`):
- Creator's permissions are used
- Creator's configuration is applied
- Thread shows creator as the user


## Web Interface Management

### Creating a Scheduler

1. Navigate to your project
2. Click menu (☰) → "Schedulers"
3. Click "Create Scheduler"
4. Fill in the form:
   - **Name**: Descriptive identifier
   - **Prompt**: Select from available prompts
   - **Enabled**: Start enabled or disabled
   - **Start Date/Time**: When to begin (pre-filled with current time)
   - **Interval**: Value and unit (minutes/hours/days/months)
   - **Days of Week** (optional): Restrict to specific days
   - **End Condition** (optional): Limit by occurrences or end date
   - **Parameters**: Simple (single value) or Structured (key-value pairs)

### Parameter Mode Toggle

The scheduler form provides a toggle between:
- **Simple**: Single text field
  - For prompts with `{{PARAMETERS}}` placeholder
  - Or prompts without placeholders (appended to first command)
- **Structured**: Key-value pairs
  - For prompts with `{{key}}` placeholders
  - Multiple parameters with named keys

### Editing a Scheduler

1. Open Schedulers dialog
2. Click "Edit" on your scheduler (or any if CODAY_ADMIN)
3. Modify fields
4. Save changes

**Note**: Modifying the schedule resets `occurrenceCount` and recalculates `nextRun`.

### Scheduler Actions

- **Enable/Disable**: Toggle button (green when enabled, gray when disabled)
- **Run Now**: Execute immediately (manual trigger, doesn't affect schedule)
- **Edit**: Modify configuration
- **Delete**: Remove scheduler (with confirmation)

### Visual Indicators

- **Status Badge**: "Enabled" (green) or "Disabled" (gray)
- **Owner Badge**: Blue badge shows creator's username if different from current user
- **Next Run**: Formatted timestamp showing next scheduled execution
- **Prompt Name**: Displays prompt name instead of ID

## Scheduling Behavior

### Execution Timing

The scheduler service checks every **30 seconds** for schedulers that should execute:
1. Load all enabled schedulers from all projects
2. Check if `nextRun` is in the past or present
3. Execute matching schedulers
4. Update `lastRun`, increment `occurrenceCount`, calculate new `nextRun`

### Next Run Calculation

The system calculates `nextRun` based on:
- Current time
- Interval configuration
- Days of week restriction (if specified)
- End condition (if specified)

**Example**: Daily at 2 AM, weekdays only
```yaml
interval: "1d"
startTimestamp: "2025-01-15T02:00:00.000Z"
daysOfWeek: [1, 2, 3, 4, 5]
```
- If today is Friday at 2 AM → Next run: Monday at 2 AM (skips weekend)

### Missed Executions

If the system is down during a scheduled execution:
- **On startup**: Skips missed occurrences
- **Next run**: Calculated from current time forward
- **Occurrence count**: Incremented only for actual executions

**Example**:
- Scheduled daily at 2 AM
- System down from Tuesday to Thursday
- On startup Thursday at 10 AM:
  - Skips Tuesday and Wednesday executions
  - Next run: Friday at 2 AM

### End Conditions

#### Occurrences Limit
```yaml
endCondition:
  type: "occurrences"
  value: 100
```
- Stops after 100 executions
- `nextRun` becomes `null` after final execution

#### End Timestamp
```yaml
endCondition:
  type: "endTimestamp"
  value: "2025-12-31T23:59:59.000Z"
```
- Stops after specified date/time
- `nextRun` becomes `null` after end timestamp

## Usage Examples

### Example 1: Daily Code Review (Simple Parameter)

**Prompt:**
```yaml
name: "code-review"
commands:
  - "Review recent commits in {{PARAMETERS}}"
  - "Identify potential issues"
```

**Scheduler:**
```yaml
name: "Daily Main Branch Review"
promptId: "code-review-prompt-id"
schedule:
  startTimestamp: "2025-01-15T09:00:00.000Z"
  interval: "1d"
  daysOfWeek: [1, 2, 3, 4, 5]  # Weekdays only
parameters:
  PARAMETERS: "main"
enabled: true
```

**Execution**: Every weekday at 9 AM, reviews main branch

### Example 2: Hourly Log Analysis (Structured Parameters)

**Prompt:**
```yaml
name: "analyze-logs"
commands:
  - "Analyze {{service}} logs from last {{duration}}"
  - "Report errors and warnings"
  - "Suggest fixes for {{service}}"
```

**Scheduler:**
```yaml
name: "Hourly API Log Check"
promptId: "analyze-logs-prompt-id"
schedule:
  startTimestamp: "2025-01-15T00:00:00.000Z"
  interval: "1h"
parameters:
  service: "api-gateway"
  duration: "1 hour"
enabled: true
```

**Execution**: Every hour, analyzes API gateway logs

### Example 3: Weekly Report (End Condition)

**Prompt:**
```yaml
name: "weekly-report"
commands:
  - "Generate weekly summary report"
  - "Include metrics and highlights"
```

**Scheduler:**
```yaml
name: "Weekly Team Report"
promptId: "weekly-report-prompt-id"
schedule:
  startTimestamp: "2025-01-20T10:00:00.000Z"  # Monday 10 AM
  interval: "7d"
  daysOfWeek: [1]  # Mondays only
  endCondition:
    type: "occurrences"
    value: 52  # One year
parameters: {}
enabled: true
```

**Execution**: Every Monday at 10 AM for one year (52 weeks)

### Example 4: Monthly Deployment (Specific Day)

**Prompt:**
```yaml
name: "deploy-release"
commands:
  - "Deploy {{version}} to {{env}}"
  - "Run smoke tests"
  - "Notify team"
```

**Scheduler:**
```yaml
name: "Monthly Production Release"
promptId: "deploy-release-prompt-id"
schedule:
  startTimestamp: "2025-02-01T02:00:00.000Z"  # First day of month
  interval: "1M"
parameters:
  version: "latest"
  env: "production"
enabled: true
```

**Execution**: First day of each month at 2 AM

## Integration with Prompts

Schedulers depend on prompts for execution. The prompt system provides:
- **Parameter validation**: Ensures required parameters are provided
- **Command templates**: Reusable AI task definitions
- **Flexible interpolation**: Simple or structured parameters

See [Prompts Documentation](./prompts.md) for details on:
- Creating prompts
- Parameter modes (`{{PARAMETERS}}` vs `{{key}}`)
- Placeholder validation

## Best Practices

### Scheduler Design

1. **Clear naming**: Use descriptive names indicating frequency and purpose
   - ✅ "Daily Main Branch Review"
   - ✅ "Hourly Error Log Analysis"
   - ❌ "Scheduler 1"

2. **Appropriate intervals**: Choose intervals that match task requirements
   - Frequent tasks: Minutes/hours
   - Daily tasks: Specific time with `daysOfWeek`
   - Periodic tasks: Days/months with end conditions

3. **Off-peak scheduling**: Schedule resource-intensive tasks during low-usage periods
   - Deployments: Early morning (2-4 AM)
   - Reports: Start of business day (8-9 AM)
   - Analysis: Off-hours

4. **End conditions**: Use for:
   - Time-limited campaigns
   - Trial periods
   - Temporary monitoring

### Parameter Strategy

1. **Simple parameters** for:
   - Single value inputs (branch names, environment)
   - Static configurations
   - Append-style augmentation

2. **Structured parameters** for:
   - Multiple related values
   - Complex configurations
   - Dynamic values that might change

3. **Parameter updates**: Edit scheduler to change parameters without recreating

### Monitoring

1. **Check execution status**: Review `lastRun` and `nextRun` fields
2. **Monitor threads**: Each execution creates a thread with correlation ID
3. **Review logs**: Server logs show `[SCHEDULER]` prefixed messages
4. **Test manually**: Use "Run Now" to test before enabling

### Maintenance

1. **Disable unused schedulers**: Rather than delete, disable for potential reuse
2. **Update prompts carefully**: Changes affect all schedulers using that prompt
3. **Review occurrence counts**: Check if schedulers are executing as expected
4. **Cleanup expired schedulers**: Remove schedulers with `nextRun: null` if no longer needed

## Troubleshooting

### Common Issues

**Scheduler not executing**
- Check `enabled: true`
- Verify `nextRun` is in the future
- Check `daysOfWeek` restrictions
- Verify prompt still exists
- Check end condition not reached

**"Prompt not found" error**
- Prompt was deleted
- Prompt ID is incorrect
- Update scheduler with valid prompt ID

**"Missing required parameters" error**
- Prompt has `{{placeholders}}` not provided in scheduler parameters
- Add missing parameters in structured mode
- Or update prompt to use `{{PARAMETERS}}` for simple mode

**Wrong execution time**
- Check `startTimestamp` timezone (must be UTC)
- Verify interval configuration
- Check `daysOfWeek` if using day restrictions

**Scheduler shows "Expired or completed"**
- `nextRun` is `null`
- End condition reached (occurrences or timestamp)
- Edit scheduler to extend or remove end condition

## Performance Considerations

### Resource Usage

- **Check interval**: 30 seconds (low overhead)
- **Concurrent executions**: Each scheduler executes in separate thread
- **Memory**: Minimal per scheduler (YAML file loaded on demand)

### Scaling Limits

- **Recommended**: < 100 schedulers per project
- **Maximum**: Limited by file system and memory
- **Execution overlap**: Schedulers can execute concurrently

### Optimization

1. **Batch operations**: Use single prompt with multiple commands vs multiple schedulers
2. **Appropriate intervals**: Avoid very frequent schedules (< 5 minutes) unless necessary
3. **End conditions**: Clean up expired schedulers to reduce check overhead
