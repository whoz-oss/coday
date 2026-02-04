# Prompt System Documentation

## Overview

Coday's prompt system provides reusable AI task definitions that can be executed:
- **Directly by users** through the web interface
- **Via schedulers** for automated execution at specified intervals
- **Via webhooks** for external API triggers (requires CODAY_ADMIN)

Prompts replace the previous "Webhook" concept with a more generic and flexible task definition system. They support parameterization through placeholders, making them adaptable to different execution contexts.

## Architecture

### Key Concepts

- **Reusable Templates**: Define AI tasks once, execute many times
- **Parameterization**: Support for both simple string parameters and structured key-value pairs
- **Project-Scoped**: Prompts are stored per project
- **Collaborative**: All users can view and edit prompts (not ownership-based like webhooks)
- **Webhook Integration**: CODAY_ADMIN can enable prompts for external HTTP API access

### Storage Architecture

```
~/.coday/
  projects/
    my-project/
      prompts/
        {prompt-id}.yml
```

Each prompt is stored as a YAML file with a unique ID (UUID v4).

## Prompt Model

### YAML Structure

```yaml
id: "abc-123-def-456"
name: "pr-review"
description: "Review pull requests and provide feedback"
commands:
  - "Review PR {{PARAMETERS}} and analyze code quality"
  - "Check for potential security issues"
  - "Suggest improvements and best practices"
webhookEnabled: false
createdBy: "john_doe"
createdAt: "2025-01-15T10:30:00.000Z"
updatedAt: "2025-01-20T14:45:00.000Z"
```

### Field Descriptions

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Unique identifier (UUID v4) |
| `name` | string | Lowercase alphanumeric with hyphens (e.g., `my-prompt-name`) |
| `description` | string | Human-readable description of what the prompt does |
| `commands` | string[] | Template commands with optional placeholder substitution |
| `webhookEnabled` | boolean | Whether this prompt can be triggered via webhook API (CODAY_ADMIN only) |
| `createdBy` | string | Username of creator (normalized with underscores) |
| `createdAt` | string | ISO 8601 creation timestamp |
| `updatedAt` | string | ISO 8601 last update timestamp |

### Naming Rules

Prompt names must follow these rules:
- Lowercase letters (a-z)
- Numbers (0-9)
- Hyphens (-) for word separation
- No spaces, dots, or special characters

Examples:
- ✅ `pr-review`
- ✅ `deploy-staging`
- ✅ `analyze-logs-v2`
- ❌ `PR Review` (spaces, uppercase)
- ❌ `deploy.staging` (dot)
- ❌ `analyze_logs` (underscore)

The web interface automatically normalizes names by replacing spaces and punctuation with hyphens.

## Parameter System

Prompts support flexible parameterization through two modes:

### Simple Parameter Mode

Use `{{PARAMETERS}}` placeholder for a single string value that can be:
- Interpolated where `{{PARAMETERS}}` appears
- Appended to the first command if no `{{PARAMETERS}}` placeholder exists

**Example:**
```yaml
commands:
  - "Review PR {{PARAMETERS}}"
  - "Check tests for the changes"
```

**Execution:**
```typescript
executePrompt(promptId, "1234", username)
```

**Result:**
```
1. "Review PR 1234"
2. "Check tests for the changes"
```

### Structured Parameter Mode

Use `{{key}}` placeholders for multiple named parameters:

**Example:**
```yaml
commands:
  - "Deploy {{app}} to {{env}}"
  - "Run smoke tests in {{env}}"
```

**Execution:**
```typescript
executePrompt(promptId, { app: "coday", env: "production" }, username)
```

**Result:**
```
1. "Deploy coday to production"
2. "Run smoke tests in production"
```

### Append Mode (No Placeholders)

If a command has no placeholders and a string parameter is provided, it's appended to the first command only:

**Example:**
```yaml
commands:
  - "Analyze deployment logs"
  - "Summarize findings"
```

**Execution:**
```typescript
executePrompt(promptId, "staging", username)
```

**Result:**
```
1. "Analyze deployment logs staging"
2. "Summarize findings"
```

### Parameter Validation

The system validates parameters strictly:
- **Missing parameters**: If placeholders remain after interpolation → Error with list of missing keys
- **Type mismatch**: String provided but prompt contains `{{key}}` placeholders → Error
- **Structured required**: "Prompt contains structured placeholders ({{key}}). Use an object parameter instead of a string."

## Access Control

### Viewing and Editing

- **All users** can view and edit all prompts in a project
- Prompts are **collaborative** by design (unlike webhooks which are ownership-based)
- The `createdBy` field shows who created each prompt (displayed with a badge in UI)

### Webhook Enablement

- Only **CODAY_ADMIN** group members can:
  - Enable/disable the `webhookEnabled` flag
  - Toggle webhook access via the web interface
  - Modify `webhookEnabled` through API updates

## Web Interface Management

### Creating a Prompt

1. Navigate to your project
2. Click menu (☰) → "Prompts"
3. Click "Create Prompt"
4. Fill in the form:
   - **Name**: Descriptive identifier (auto-normalized)
   - **Description**: Explain the prompt's purpose
   - **Commands**: One or more template commands
   - **Enable webhook** (CODAY_ADMIN only): Allow external API access

### Editing a Prompt

1. Open Prompts dialog
2. Click "Edit" on any prompt
3. Modify fields (all users can edit)
4. Save changes

### Webhook URL (CODAY_ADMIN only)

When editing a prompt with `webhookEnabled: true`, admins see:
- **Webhook URL** section with the full URL
- **Copy URL** button to copy to clipboard
- Format: `https://your-domain.com/api/webhooks/{promptId}/execute`

### Visual Indicators

- **Owner badge**: Blue badge shows creator's username if different from current user
- **Webhook status**: "Webhook: Enabled/Disabled" displayed in prompt list
- **Toggle button**: CODAY_ADMIN can enable/disable webhook access (orange when enabled, gray when disabled)

### Webhook Execution Endpoint

```bash
POST /api/webhooks/{promptId}/execute
Content-Type: application/json

# Simple parameter mode
{
  "title": "Optional thread title",
  "awaitFinalAnswer": false,
  "parameters": "simple-value"
}

# Structured parameter mode
{
  "title": "Optional thread title",
  "awaitFinalAnswer": false,
  "key1": "value1",
  "key2": "value2"
}
```

**Note**: The `parameters` field is special - if provided as a string, it's used for simple mode. All other fields become structured parameters.

### Response Modes

#### Asynchronous (Default)
```json
{
  "threadId": "thread_abc123"
}
```
HTTP Status: 201 Created

#### Synchronous (`awaitFinalAnswer: true`)
```json
{
  "threadId": "thread_abc123",
  "lastEvent": {
    "type": "message",
    "role": "assistant",
    "content": "Analysis complete..."
  }
}
```
HTTP Status: 200 OK

### Error Responses

| Status | Description | Example |
|--------|-------------|---------|
| 400 | Bad request | Missing prompt ID |
| 403 | Forbidden | Webhook not enabled, or CODAY_ADMIN required |
| 404 | Not found | Prompt doesn't exist |
| 422 | Validation error | `Missing required parameters: key1, key2` |
| 500 | Server error | Internal error |

## Usage Examples

### Example 1: PR Review (Simple Parameter)

**Prompt Configuration:**
```yaml
name: "pr-review"
description: "Review pull requests"
commands:
  - "Review PR {{PARAMETERS}} and provide feedback"
  - "Check for security issues"
```

**Web Interface Execution:**
```
User types: pr-review 1234
→ Executes with parameter "1234"
```

**API Execution:**
```bash
curl -X POST "https://coday.example.com/api/webhooks/{promptId}/execute" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "PR #1234 Review",
    "parameters": "1234"
  }'
```

**Result:**
```
1. "Review PR 1234 and provide feedback"
2. "Check for security issues"
```

### Example 2: Deployment (Structured Parameters)

**Prompt Configuration:**
```yaml
name: "deploy-app"
description: "Deploy application to environment"
commands:
  - "Deploy {{app}} version {{version}} to {{env}}"
  - "Run health checks in {{env}}"
  - "Notify team about {{app}} deployment"
```

**Scheduler Configuration:**
```json
{
  "name": "Daily Staging Deploy",
  "promptId": "deploy-app-prompt-id",
  "parameters": {
    "app": "coday",
    "version": "latest",
    "env": "staging"
  },
  "schedule": {
    "interval": "1d",
    "startTimestamp": "2025-01-15T02:00:00Z"
  }
}
```

**Result:**
```
1. "Deploy coday version latest to staging"
2. "Run health checks in staging"
3. "Notify team about coday deployment"
```

### Example 3: Log Analysis (Append Mode)

**Prompt Configuration:**
```yaml
name: "analyze-logs"
description: "Analyze application logs"
commands:
  - "Analyze application logs"
  - "Summarize errors and warnings"
  - "Suggest remediation steps"
```

**Execution:**
```typescript
executePrompt(promptId, "from last 24 hours", username)
```

**Result:**
```
1. "Analyze application logs from last 24 hours"
2. "Summarize errors and warnings"
3. "Suggest remediation steps"
```

## Integration with Schedulers

Schedulers can execute prompts automatically at specified intervals. See [Schedulers Documentation](./schedulers.md) for details.

**Scheduler Form:**
- **Simple Mode**: Single text field for simple parameter value
  - Stored as `{PARAMETERS: "value"}`
  - Passed as string to prompt execution
- **Structured Mode**: Key-value pairs for multiple parameters
  - Stored as `{key1: "value1", key2: "value2"}`
  - Passed as object to prompt execution

**Toggle**: The scheduler form provides a "Simple/Structured" toggle to choose parameter mode.

## Integration with Webhooks

Prompts with `webhookEnabled: true` can be triggered via HTTP API.

### CI/CD Integration Example

**GitHub Actions:**
```yaml
name: Deploy Analysis
on:
  push:
    branches: [main]

jobs:
  analyze:
    runs-on: ubuntu-latest
    steps:
      - name: Trigger Coday Prompt
        run: |
          curl -X POST "${{ secrets.CODAY_PROMPT_URL }}" \
            -H "Content-Type: application/json" \
            -d '{
              "title": "Deploy Analysis: ${{ github.sha }}",
              "branch": "${{ github.ref_name }}",
              "commit": "${{ github.sha }}",
              "author": "${{ github.actor }}"
            }'
```

### Jira Automation Example

**Jira Webhook:**
```json
{
  "url": "https://coday.example.com/api/webhooks/{promptId}/execute",
  "method": "POST",
  "headers": {
    "Content-Type": "application/json"
  },
  "body": {
    "title": "{{issue.key}} - {{issue.fields.summary}}",
    "issueKey": "{{issue.key}}",
    "summary": "{{issue.fields.summary}}",
    "priority": "{{issue.fields.priority.name}}"
  }
}
```

## Best Practices

### Prompt Design

1. **Clear naming**: Use descriptive, action-oriented names (`deploy-staging`, `review-pr`)
2. **Explicit placeholders**: Use descriptive names like `{{issueKey}}` not `{{id}}`
3. **Command ordering**: Structure commands in logical execution order
4. **Documentation**: Write clear descriptions explaining the prompt's purpose

### Parameter Strategy

1. **Simple parameters** for:
   - Single value inputs (IDs, names, simple strings)
   - Quick CLI usage
   - Append-style augmentation

2. **Structured parameters** for:
   - Multiple related values
   - Complex configurations
   - Scheduler automation
   - Webhook integrations

3. **Validation**: Design prompts to fail fast with clear error messages

### Security

1. **Webhook enablement**: Only enable webhooks for prompts that need external access
2. **Parameter validation**: Validate input at the calling system before sending
3. **Access control**: Remember all users can edit prompts - use CODAY_ADMIN for sensitive operations
4. **Audit trail**: Check `createdBy` and `updatedAt` fields for change tracking

### Testing

1. **Test in web interface** before automation
2. **Verify parameter interpolation** with sample data
3. **Check error handling** with missing/invalid parameters
4. **Monitor execution** through thread IDs and logs

## Troubleshooting

### Common Issues

**"Missing required parameters: key1, key2"**
- Prompt contains `{{key1}}` and `{{key2}}` placeholders
- Provide structured parameters: `{key1: "value", key2: "value"}`

**"Prompt contains structured placeholders. Use an object parameter instead of a string."**
- Prompt has `{{key}}` placeholders but string parameter provided
- Either: Remove placeholders or provide structured parameters

**"Prompt name must be lowercase alphanumeric with hyphens"**
- Name contains invalid characters
- Use only: `a-z`, `0-9`, `-`

**"Only CODAY_ADMIN can modify webhookEnabled flag"**
- User is not in CODAY_ADMIN group
- Contact admin or remove `webhookEnabled` from update request

### Debug Steps

1. **Check prompt exists**: Open Prompts dialog in web interface
2. **Test parameters**: Try execution with sample data
3. **Verify placeholders**: Ensure placeholder names match parameter keys exactly
4. **Check logs**: Look for `[PROMPT_EXEC]` messages in server logs
5. **Validate syntax**: Ensure `{{placeholders}}` use double braces
