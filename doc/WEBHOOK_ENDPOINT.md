# Webhook Endpoint

The Coday webhook endpoint enables programmatic AI agent interactions through HTTP requests. It creates one-shot Coday instances that process prompts sequentially and automatically save conversation threads.

## API Specification

### Endpoint
```
POST /api/webhook
```

### Request Headers
```
Content-Type: application/json
x-forwarded-email: user@example.com  # Required unless running in no-auth mode
```

### Request Body
```json
{
  "project": "string",           // Required: Project name
  "title": "string",             // Optional: Thread title  
  "prompts": ["string"],         // Required: Array of prompts
  "awaitFinalAnswer": boolean          // Optional: Wait for completion (default: false)
}
```

### Response Formats

**Asynchronous (default)**:
```json
{
  "threadId": "thread_abc123"
}
```

**Synchronous (awaitFinalAnswer: true)**:
```json
{
  "threadId": "thread_abc123",
  "lastEvent": {
    "type": "message",
    "role": "assistant", 
    "content": "Final response...",
    "timestamp": "2025-06-29T14:15:56.789Z"
  }
}
```

## Usage Examples

### Basic Request
```bash
curl -X POST http://localhost:3000/api/webhook \
  -H "Content-Type: application/json" \
  -H "x-forwarded-email: user@company.com" \
  -d '{
    "project": "my-project",
    "title": "Code Analysis",
    "prompts": [
      "analyze recent commits",
      "generate summary"
    ]
  }'
```

### CI/CD Integration
```yaml
# GitHub Actions
- name: Trigger Code Analysis
  run: |
    curl -X POST ${{ secrets.CODAY_URL }}/api/webhook \
      -H "Content-Type: application/json" \
      -H "x-forwarded-email: ${{ github.actor }}@company.com" \
      -d '{
        "project": "ci-analysis", 
        "title": "PR Analysis - ${{ github.event.pull_request.number }}",
        "prompts": ["analyze pull request changes", "generate review summary"],
        "awaitFinalAnswer": true
      }'
```

### Monitoring Integration
```bash
# AlertManager webhook
curl -X POST http://coday:3000/api/webhook \
  -H "Content-Type: application/json" \
  -H "x-forwarded-email: alerts@company.com" \
  -d '{
    "project": "monitoring",
    "title": "Alert Investigation",  
    "prompts": ["investigate current alerts", "suggest remediation"]
  }'
```

## How It Works

1. **Validation**: Validates required parameters and authentication
2. **Instance Creation**: Creates isolated one-shot Coday instance  
3. **Thread Management**: Automatically prepends thread save command
4. **Sequential Processing**: Executes prompts in order
5. **Response**: Returns immediately (async) or waits for completion (sync)
6. **Cleanup**: Instance terminates after processing

## Error Handling

**400 Bad Request**: Missing required parameters
```json
{ "error": "Missing required parameter: project" }
```

**500 Internal Server Error**: Processing failures  
```json
{ "error": "Webhook processing failed" }
```

## Logging & Monitoring

The webhook automatically logs:
- Request initiation with parameters and unique clientId
- Error details with context and duration
- All logs include clientId for correlation

Log types: `WEBHOOK`, `WEBHOOK_ERROR`

The clientId serves as the unique identifier to connect related logs together for debugging and tracing webhook request flows.

## Best Practices

### Request Design
- Use descriptive thread titles for organization
- Keep prompts focused and specific
- Choose appropriate response mode (async/sync)
- Implement proper error handling and retries

### Integration Patterns
- **Fire-and-forget**: Use async mode for background tasks
- **Immediate results**: Use sync mode for CI/CD gates
- **Batch processing**: Group related prompts in single request
- **Monitoring**: Use async mode with separate result polling

### Security & Performance
- Validate project access permissions
- Implement rate limiting for production use
- Monitor webhook usage through logs
- Use HTTPS in production environments