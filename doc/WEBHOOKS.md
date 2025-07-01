# Webhook System Documentation

## Overview

Coday's webhook system enables external platforms to trigger AI agent interactions programmatically through HTTP endpoints. Each webhook is identified by a UUID v4 and can be configured to execute either free-form commands or predefined templates with placeholder substitution.

The webhook system is designed for integration with external tools like Jira, GitLab, CI/CD pipelines, and other automation platforms that need to trigger AI-powered analysis or processing.

## Architecture

- **UUID-based Endpoints**: Each webhook has a unique UUID v4 identifier
- **Configuration Storage**: Webhooks are stored as YAML files in `{configDir}/webhooks/{uuid}.yml`
- **Command Types**: Support for both free-form commands and template-based commands
- **Project Binding**: Each webhook is associated with a specific Coday project
- **User Context**: Execution runs in the context of the webhook creator

## Configuration Management

### Commands Overview

All webhook configuration is managed through the `config webhook` command group:

```bash
config webhook list                    # List all configured webhooks
config webhook add                     # Add a new webhook (goes directly to edit)
config webhook edit --uuid=<uuid>     # Edit an existing webhook
config webhook delete --uuid=<uuid>   # Delete a webhook
```

### Adding a Webhook

The add command creates a minimal webhook and immediately opens the edit interface:

```bash
config webhook add
```

This will:
1. Create the webhook with default settings
2. Automatically open the edit interface to complete configuration

### Editing a Webhook

The edit command allows modification of all webhook properties:

```bash
config webhook edit --uuid=abc123-def456-...
```

Or without UUID to select from a list:

```bash
config webhook edit
```

**Editable Fields:**
- **Name**: Descriptive name for the webhook
- **Project**: Target Coday project for execution
- **Command Type**: 'free' or 'template'
- **Commands**: Array of commands (for template type)

### Listing Webhooks

View all configured webhooks with details:

```bash
config webhook list
```

Shows: name, UUID, project, creator, creation date, command type, and command count.

### Deleting a Webhook

Remove a webhook configuration:

```bash
config webhook delete --uuid=abc123-def456-...
```

## Webhook Model

Each webhook is stored as a YAML file with the following structure:

```yaml
uuid: "550e8400-e29b-41d4-a716-446655440000"  # UUID v4 format
name: "Jira Issue Analysis"                   # Descriptive name
project: "my-project"                         # Target Coday project
createdBy: "john.doe@company.com"             # Creator username (for tracking)
createdAt: "2025-07-01T10:30:00.000Z"        # Creation timestamp
commandType: "template"                       # 'free' or 'template'
commands:                                     # Commands array (for template type)
  - "analyze issue {{issueId}}: {{description}}"
  - "suggest resolution for {{issueId}}"
```

### Field Descriptions

| Field | Type | Description |
|-------|------|-------------|
| `uuid` | string | UUID v4 identifier for the webhook |
| `name` | string | Human-readable name for identification |
| `project` | string | Coday project name for execution context |
| `createdBy` | string | Username of webhook creator (tracking only) |
| `createdAt` | Date | Webhook creation timestamp |
| `commandType` | 'free' \| 'template' | Command processing mode |
| `commands` | string[] | Template commands (for template type only) |

## API Endpoint Specification

### Endpoint URL

```
POST /api/webhook/{uuid}
```

Where `{uuid}` is the UUID v4 identifier of the configured webhook.

### Authentication

Authentication is handled by the reverse proxy using the `x-forwarded-email` header. The webhook execution runs under the authenticated user's context, NOT the webhook creator's context.

### Request Headers

```
Content-Type: application/json
x-forwarded-email: user@company.com  # Set by authentication proxy
```

### Request Body

#### Common Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `title` | string | No | Title for the saved conversation thread |
| `awaitFinalAnswer` | boolean | No | Wait for completion (sync mode) vs return immediately (async) |

#### For 'free' Command Type

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `prompts` | string[] | Yes | Array of commands to execute |

#### For 'template' Command Type

Any additional fields in the request body are used as placeholder values:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `{key}` | any | No | Values to replace `{{key}}` placeholders in template commands |

### Response Modes

#### Asynchronous Mode (Default)

Returns immediately with thread ID for fire-and-forget operations:

```json
{
  "threadId": "thread_abc123"
}
```

**HTTP Status**: 201 Created

#### Synchronous Mode (`awaitFinalAnswer: true`)

Waits for all commands to complete and returns the final result:

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

**HTTP Status**: 200 OK

### Error Responses

| Status | Description | Response Body |
|--------|-------------|---------------|
| 400 | Missing UUID | `{"error": "Missing webhook UUID in URL"}` |
| 404 | Webhook not found | `{"error": "Webhook with UUID 'xxx' not found"}` |
| 422 | Invalid request | `{"error": "Missing or invalid prompts array"}` |
| 500 | Server error | `{"error": "Internal server error"}` |

## Usage Examples

### Free Command Type

**Webhook Configuration:**
```yaml
uuid: "abc123-def456-..."
name: "Ad-hoc Analysis"
project: "research-project"
commandType: "free"
```

**API Request:**
```bash
curl -X POST "https://coday.company.com/api/webhook/abc123-def456-..." \
  -H "Content-Type: application/json" \
  -H "x-forwarded-email: analyst@company.com" \
  -d '{
    "title": "Code Review Analysis",
    "prompts": [
      "analyze the recent commits in the main branch",
      "identify potential security issues",
      "suggest improvements"
    ],
    "awaitFinalAnswer": false
  }'
```

**Response:**
```json
{
  "threadId": "thread_xyz789"
}
```

### Template Command Type

**Webhook Configuration:**
```yaml
uuid: "def456-ghi789-..."
name: "Jira Issue Processor"
project: "support-project"
commandType: "template"
commands:
  - "analyze jira issue {{issueKey}}: {{summary}}"
  - "review description: {{description}}"
  - "suggest resolution for {{issueKey}} with priority {{priority}}"
```

**API Request:**
```bash
curl -X POST "https://coday.company.com/api/webhook/def456-ghi789-..." \
  -H "Content-Type: application/json" \
  -H "x-forwarded-email: support@company.com" \
  -d '{
    "title": "PROJ-123 Analysis",
    "issueKey": "PROJ-123",
    "summary": "Authentication fails on mobile app",
    "description": "Users report unable to login on iOS app version 2.1.0",
    "priority": "High",
    "awaitFinalAnswer": true
  }'
```

**Processed Commands:**
1. `analyze jira issue PROJ-123: Authentication fails on mobile app`
2. `review description: Users report unable to login on iOS app version 2.1.0`
3. `suggest resolution for PROJ-123 with priority High`

**Response:**
```json
{
  "threadId": "thread_abc456",
  "lastEvent": {
    "type": "message",
    "role": "assistant",
    "name": "Sway",
    "content": "Based on the analysis, the authentication issue appears to be related to..."
  }
}
```

## Integration Examples

### Jira Integration

Create a Jira automation rule that calls Coday when issues are created or updated.

**Webhook Setup:**
```bash
config webhook add
# Name: Jira Issue Analysis
# Project: support-analysis
# Command Type: template
# Commands:
#   - "analyze jira issue {{issueKey}}: {{summary}}"
#   - "categorize issue type and priority"
#   - "suggest initial response for {{issueKey}}"
```

**Jira Automation Rule:**
```json
{
  "url": "https://coday.company.com/api/webhook/your-webhook-uuid",
  "method": "POST",
  "headers": {
    "Content-Type": "application/json"
  },
  "body": {
    "title": "{{issue.key}} - {{issue.fields.summary}}",
    "issueKey": "{{issue.key}}",
    "summary": "{{issue.fields.summary}}",
    "description": "{{issue.fields.description}}",
    "priority": "{{issue.fields.priority.name}}",
    "issueType": "{{issue.fields.issuetype.name}}"
  }
}
```

### GitLab Integration

Set up GitLab webhooks to trigger code analysis on merge requests.

**Webhook Setup:**
```bash
config webhook add
# Name: GitLab MR Analysis
# Project: code-review
# Command Type: template
# Commands:
#   - "analyze merge request {{merge_request_iid}}: {{title}}"
#   - "review changes in {{source_branch}} -> {{target_branch}}"
#   - "check for security issues and code quality"
```

**GitLab Webhook Configuration:**
- URL: `https://coday.company.com/api/webhook/your-webhook-uuid`
- Trigger: Merge Request events
- Secret Token: (handled by your authentication proxy)

**Payload Processing:**
GitLab will send merge request data, and the template will process:
```json
{
  "title": "MR Analysis: !42",
  "merge_request_iid": "42",
  "title": "Add user authentication feature",
  "source_branch": "feature/auth",
  "target_branch": "main",
  "awaitFinalAnswer": false
}
```

### CI/CD Pipeline Integration

Use webhooks in your CI/CD pipeline for automated code analysis.

**Pipeline Example (GitHub Actions):**
```yaml
name: Code Analysis
on:
  pull_request:
    types: [opened, synchronize]

jobs:
  analyze:
    runs-on: ubuntu-latest
    steps:
      - name: Trigger Coday Analysis
        run: |
          curl -X POST "${{ secrets.CODAY_WEBHOOK_URL }}" \
            -H "Content-Type: application/json" \
            -d '{
              "title": "PR Analysis: #${{ github.event.number }}",
              "prNumber": "${{ github.event.number }}",
              "title": "${{ github.event.pull_request.title }}",
              "author": "${{ github.event.pull_request.user.login }}",
              "awaitFinalAnswer": true
            }'
```

## Best Practices

### Security Considerations

1. **Authentication**: Ensure your reverse proxy properly validates and sets the `x-forwarded-email` header
2. **UUID Secrecy**: Treat webhook UUIDs as secrets - don't expose them in public repositories
3. **Input Validation**: Template placeholders should be validated before sending to avoid injection

### Performance Optimization

1. **Async Mode**: Use `awaitFinalAnswer: false` for fire-and-forget operations to avoid timeouts
2. **Batch Operations**: Group related commands in templates rather than making multiple webhook calls
3. **Error Handling**: Implement retry logic in calling systems for transient failures

### Monitoring and Debugging

1. **Thread IDs**: Store returned thread IDs for later reference and debugging
2. **Logging**: All webhook calls are logged with correlation IDs for tracing
3. **Testing**: Use the terminal interface to test webhook commands before automation

### Template Design

1. **Clear Placeholders**: Use descriptive placeholder names like `{{issueKey}}` rather than `{{id}}`
2. **Fallback Values**: Consider how templates behave with missing placeholder values
3. **Command Ordering**: Structure template commands in logical execution order

## Troubleshooting

### Common Issues

**404 Webhook Not Found**
- Verify the UUID in the URL matches an existing webhook
- Check that the webhook wasn't deleted

**422 Invalid Request**
- For 'free' type: ensure `prompts` array is provided and not empty
- For 'template' type: verify required placeholder values are included

**Authentication Errors**
- Confirm `x-forwarded-email` header is properly set by your proxy
- Verify the authenticated user has access to the target project

**Template Processing Issues**
- Check that placeholder names in the request match `{{placeholders}}` in templates
- Verify placeholder values are properly formatted (strings, numbers, etc.)

### Debug Commands

Check webhook configuration:
```bash
config webhook list
config webhook edit --uuid=your-uuid
```

Test webhook manually using curl:
```bash
curl -X POST "http://localhost:3000/api/webhook/your-uuid" \
  -H "Content-Type: application/json" \
  -H "x-forwarded-email: your@email.com" \
  -d '{"prompts": ["test command"]}'
```
