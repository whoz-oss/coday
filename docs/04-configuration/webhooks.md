# Webhook System Documentation

## Overview

Coday's webhook system enables external platforms to trigger AI agent interactions programmatically through HTTP endpoints. Each webhook is identified by a UUID v4 and can be configured to execute either free-form commands or predefined templates with placeholder substitution.

The webhook system is designed for integration with external tools like Jira, GitLab, CI/CD pipelines, and other automation platforms that need to trigger AI-powered analysis or processing.

## Architecture

### Key Concepts

- **UUID-based Endpoints**: Each webhook has a unique UUID v4 identifier
- **Project-Scoped Storage**: Webhooks are stored per project in `~/.coday/projects/{projectName}/webhooks/{uuid}.yml`
- **Command Types**: Support for both free-form commands and template-based commands
- **Access Control**: Owner OR CODAY_ADMIN group members can manage webhooks
- **User Context**: Execution runs in the context of the user who triggers the webhook

### Storage Architecture

```
~/.coday/
  projects/
    my-project/
      webhooks/
        {uuid-1}.yml
        {uuid-2}.yml
      triggers/
        {trigger-id}.yml
```

Webhooks are now stored per project, ensuring proper isolation and access control.

## Configuration Management

### Web Interface (Recommended)

The easiest way to manage webhooks is through the web interface:

1. Navigate to your project
2. Click the menu button (☰) in the top-left corner
3. Select "Webhooks" from the menu
4. Use the dialog to create, edit, or delete webhooks

The web interface provides:
- Visual form for webhook configuration
- Project-scoped webhook listing
- Access control enforcement (only your webhooks + CODAY_ADMIN)
- Validation and error feedback

### REST API

For programmatic management, use the REST API endpoints:

```bash
# List webhooks for a project
GET /api/projects/{projectName}/webhooks

# Get specific webhook
GET /api/projects/{projectName}/webhooks/{uuid}

# Create webhook
POST /api/projects/{projectName}/webhooks

# Update webhook
PUT /api/projects/{projectName}/webhooks/{uuid}

# Delete webhook
DELETE /api/projects/{projectName}/webhooks/{uuid}
```

All CRUD operations require authentication and enforce ownership (owner OR CODAY_ADMIN).

## Webhook Model

Each webhook is stored as a YAML file with the following structure:

```yaml
uuid: "550e8400-e29b-41d4-a716-446655440000"  # UUID v4 format
name: "Jira Issue Analysis"                   # Descriptive name
createdBy: "john.doe@company.com"             # Creator username
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
| `createdBy` | string | Username of webhook creator |
| `createdAt` | Date | Webhook creation timestamp |
| `commandType` | 'free' \| 'template' | Command processing mode |
| `commands` | string[] | Template commands (for template type only) |

**Note**: The `project` field has been removed - the project is implicit from the storage location.

## Access Control

### Ownership Model

- **Owner**: User who created the webhook can view, edit, and delete it
- **CODAY_ADMIN**: Members of the CODAY_ADMIN group can manage ALL webhooks in ALL projects
- **Other users**: Cannot see or modify webhooks they don't own

### Setting CODAY_ADMIN Group

Add users to the CODAY_ADMIN group in their user configuration:

```yaml
# ~/.coday/users/{username}/user.yml
version: 1
temp_groups:
  - CODAY_ADMIN
```

## API Endpoint Specification

### Execution Endpoint

```
POST /api/webhooks/{uuid}/execute
```

**Important**: The execution endpoint remains project-agnostic. The webhook service automatically finds the webhook across all projects using the UUID.

### Authentication

Authentication is handled by the reverse proxy using the `x-forwarded-email` header. The webhook execution runs under the authenticated user's context.

In development mode (without `--auth` flag), the system username is used.

### Request Headers

```
Content-Type: application/json
x-forwarded-email: user@company.com  # Set by authentication proxy (production)
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
| 404 | Webhook not found | `{"error": "Webhook not found: xxx"}` |
| 422 | Invalid request | `{"error": "Missing or invalid prompts array"}` |
| 500 | Server error | `{"error": "Internal server error"}` |

## Usage Examples

### Free Command Type

**Webhook Configuration:**
```yaml
uuid: "abc123-def456-..."
name: "Ad-hoc Analysis"
createdBy: "analyst@company.com"
commandType: "free"
```

**API Request:**
```bash
curl -X POST "https://coday.company.com/api/webhooks/abc123-def456-.../execute" \
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
createdBy: "support@company.com"
commandType: "template"
commands:
  - "analyze jira issue {{issueKey}}: {{summary}}"
  - "review description: {{description}}"
  - "suggest resolution for {{issueKey}} with priority {{priority}}"
```

**API Request:**
```bash
curl -X POST "https://coday.company.com/api/webhooks/def456-ghi789-.../execute" \
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

**Jira Automation Rule:**
```json
{
  "url": "https://coday.company.com/api/webhooks/your-webhook-uuid/execute",
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

**GitLab Webhook Configuration:**
- URL: `https://coday.company.com/api/webhooks/your-webhook-uuid/execute`
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
3. **Access Control**: Only CODAY_ADMIN members can manage webhooks across projects
4. **Input Validation**: Template placeholders should be validated before sending to avoid injection

### Performance Optimization

1. **Async Mode**: Use `awaitFinalAnswer: false` for fire-and-forget operations to avoid timeouts
2. **Batch Operations**: Group related commands in templates rather than making multiple webhook calls
3. **Error Handling**: Implement retry logic in calling systems for transient failures

### Monitoring and Debugging

1. **Thread IDs**: Store returned thread IDs for later reference and debugging
2. **Logging**: All webhook calls are logged with correlation IDs for tracing
3. **Testing**: Test webhooks through the web interface before automation

### Template Design

1. **Clear Placeholders**: Use descriptive placeholder names like `{{issueKey}}` rather than `{{id}}`
2. **Fallback Values**: Consider how templates behave with missing placeholder values
3. **Command Ordering**: Structure template commands in logical execution order

## Troubleshooting

### Common Issues

**404 Webhook Not Found**
- Verify the UUID in the URL matches an existing webhook
- Check that the webhook wasn't deleted
- Ensure the webhook exists in one of your projects

**401/403 Access Denied**
- For CRUD operations: You must be the owner OR in CODAY_ADMIN group
- For execution: Authentication required (x-forwarded-email header)

**422 Invalid Request**
- For 'free' type: ensure `prompts` array is provided and not empty
- For 'template' type: verify required placeholder values are included

**Template Processing Issues**
- Check that placeholder names in the request match `{{placeholders}}` in templates
- Verify placeholder values are properly formatted (strings, numbers, etc.)

### Debug Steps

1. **Check webhook exists**:
   - Open web interface
   - Navigate to project
   - Open Webhooks dialog
   - Verify webhook is listed

2. **Test manually**:
   ```bash
   curl -X POST "http://localhost:4100/api/webhooks/your-uuid/execute" \
     -H "Content-Type: application/json" \
     -d '{"prompts": ["test command"]}'
   ```

3. **Check logs**:
   - Server logs show webhook execution attempts
   - Look for `[WEBHOOK]` prefixed messages
   - Thread IDs in logs help trace execution

## Migration from Legacy System

If you have webhooks in the old global storage (`~/.coday/webhooks/`), they need to be recreated:

1. Note the configuration of your existing webhooks
2. Delete the old webhook files manually
3. Recreate webhooks through the web interface in the appropriate projects

The new architecture provides better isolation, access control, and project organization.
