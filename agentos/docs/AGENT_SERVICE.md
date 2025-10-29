# Agent Service Documentation

## Overview

The Agent Service is a core component of agentOS that allows you to register, manage, and query AI agents based on contextual requirements. It provides a flexible API for discovering the right agent for a specific task.

## Architecture

### Domain Models

#### Agent
Represents an AI agent with the following properties:
- `id`: Unique identifier
- `name`: Human-readable name
- `description`: What the agent does
- `version`: Agent version
- `capabilities`: List of capabilities (e.g., "code-review", "test-generation")
- `requiredContext`: Set of context types the agent is relevant for
- `tags`: Additional categorization tags
- `priority`: Priority level (higher = more important)
- `status`: Current status (ACTIVE, INACTIVE, MAINTENANCE, DEPRECATED)

#### ContextType
Enum defining different types of contexts:
- `CODE_REVIEW`
- `TESTING`
- `DEPLOYMENT`
- `DOCUMENTATION`
- `BUG_FIXING`
- `FEATURE_DEVELOPMENT`
- `SECURITY`
- `PERFORMANCE`
- `DATABASE`
- `API_DESIGN`
- `UI_UX`
- `DEVOPS`
- `DATA_ANALYSIS`
- `GENERAL`

#### AgentContext
Query parameters for finding agents:
- `contextTypes`: Set of context types to match
- `tags`: Set of tags to match
- `capabilities`: Set of capabilities to match
- `excludeStatuses`: Statuses to exclude from results
- `minPriority`: Minimum priority level
- `maxResults`: Maximum number of results to return

## REST API Endpoints

### Get All Agents
```http
GET /api/agents
```

Returns all registered agents.

**Response:**
```json
[
  {
    "id": "code-reviewer",
    "name": "Code Reviewer",
    "description": "Analyzes code for quality, best practices, and potential issues",
    "version": "1.0.0",
    "capabilities": ["static-analysis", "code-review", "best-practices"],
    "requiredContext": ["CODE_REVIEW", "GENERAL"],
    "tags": ["code", "quality", "review"],
    "priority": 10,
    "status": "ACTIVE"
  }
]
```

### Get Agent by ID
```http
GET /api/agents/{agentId}
```

Returns a specific agent by its ID.

### Query Agents (POST)
```http
POST /api/agents/query
Content-Type: application/json

{
  "contextTypes": ["CODE_REVIEW"],
  "capabilities": ["static-analysis"],
  "tags": ["quality"],
  "excludeStatuses": ["INACTIVE"],
  "minPriority": 5,
  "maxResults": 10
}
```

**Response:**
```json
{
  "agents": [...],
  "totalCount": 5,
  "context": {
    "contextTypes": ["CODE_REVIEW"],
    "capabilities": ["static-analysis"],
    "tags": ["quality"],
    "excludeStatuses": ["INACTIVE"],
    "minPriority": 5,
    "maxResults": 10
  }
}
```

### Query Agents (GET with Query Parameters)
```http
GET /api/agents/by-context?contextTypes=CODE_REVIEW,TESTING&maxResults=5
```

Simplified query endpoint using query parameters.

### Register New Agent
```http
POST /api/agents
Content-Type: application/json

{
  "id": "my-agent",
  "name": "My Custom Agent",
  "description": "Does something useful",
  "version": "1.0.0",
  "capabilities": ["custom-capability"],
  "requiredContext": ["GENERAL"],
  "tags": ["custom"],
  "priority": 5,
  "status": "ACTIVE"
}
```

### Update Agent
```http
PUT /api/agents/{agentId}
Content-Type: application/json

{
  "id": "my-agent",
  "name": "My Updated Agent",
  ...
}
```

### Delete Agent
```http
DELETE /api/agents/{agentId}
```

### Get Available Context Types
```http
GET /api/agents/context-types
```

Returns all available context types.

### Get Available Statuses
```http
GET /api/agents/statuses
```

Returns all available agent statuses.

## Usage Examples

### Example 1: Find Code Review Agents
```bash
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "contextTypes": ["CODE_REVIEW"],
    "maxResults": 3
  }'
```

### Example 2: Find Security-Related Agents
```bash
curl -X GET "http://localhost:8080/api/agents/by-context?tags=security"
```

### Example 3: Find High-Priority Agents
```bash
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "minPriority": 9,
    "excludeStatuses": ["INACTIVE", "DEPRECATED"]
  }'
```

### Example 4: Register a Custom Agent
```bash
curl -X POST http://localhost:8080/api/agents \
  -H "Content-Type: application/json" \
  -d '{
    "id": "custom-analyzer",
    "name": "Custom Code Analyzer",
    "description": "Analyzes code with custom rules",
    "version": "1.0.0",
    "capabilities": ["custom-analysis", "rule-checking"],
    "requiredContext": ["CODE_REVIEW"],
    "tags": ["custom", "analysis"],
    "priority": 7,
    "status": "ACTIVE"
  }'
```

## Pre-registered Agents

The system comes with the following default agents:

1. **Code Reviewer** - Code quality and best practices analysis
2. **Test Generator** - Generates unit and integration tests
3. **Bug Hunter** - Identifies and helps fix bugs
4. **Documentation Writer** - Generates technical documentation
5. **Security Auditor** - Security audits and vulnerability detection
6. **Performance Optimizer** - Performance analysis and optimization
7. **DevOps Assistant** - Deployment, CI/CD, and infrastructure
8. **API Designer** - RESTful and GraphQL API design

## Filtering Logic

The service applies filters in the following order:

1. **Status Filtering**: Excludes agents with specified statuses
2. **Context Type Matching**: Matches agents with ANY of the specified context types
3. **Capability Matching**: Matches agents with ANY of the specified capabilities
4. **Tag Matching**: Matches agents with ANY of the specified tags
5. **Priority Filtering**: Filters agents with priority >= minPriority
6. **Sorting**: Sorts results by priority (descending)
7. **Limit**: Applies maxResults limit if specified

## Extension Points

### Adding New Context Types

Edit `ContextType` enum in `Agent.kt`:
```kotlin
enum class ContextType {
    // ... existing types
    MY_NEW_CONTEXT
}
```

### Custom Agent Registration

Agents can be registered programmatically or via the REST API. For programmatic registration during startup, add them in `AgentRegistry.registerDefaultAgents()`.

## Best Practices

1. **Use Specific Context Types**: Be as specific as possible when querying to get the most relevant agents
2. **Set Appropriate Priorities**: Use priority to indicate agent importance (0-10 scale recommended)
3. **Tag Consistently**: Use consistent tagging conventions for better discoverability
4. **Update Agent Status**: Mark agents as INACTIVE or MAINTENANCE when appropriate
5. **Version Management**: Use semantic versioning for agents
6. **Capability Naming**: Use kebab-case for capability names (e.g., "code-review", "test-generation")

## Future Enhancements

Potential improvements for the agent service:

- Agent health checks and monitoring
- Agent execution history and metrics
- Dynamic agent loading from external sources
- Agent dependency management
- Agent collaboration patterns
- Context-based agent composition
- Machine learning-based agent recommendation
- Agent performance tracking and optimization
