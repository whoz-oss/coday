# AgentOS Usage Examples

This document provides practical examples for using the AgentOS Agent Service.

## Table of Contents
- [Basic Operations](#basic-operations)
- [Advanced Queries](#advanced-queries)
- [Real-World Scenarios](#real-world-scenarios)
- [Integration Examples](#integration-examples)

## Basic Operations

### 1. Get All Agents

**Request:**
```bash
curl http://localhost:8080/api/agents
```

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
  },
  // ... more agents
]
```

### 2. Get Specific Agent

**Request:**
```bash
curl http://localhost:8080/api/agents/security-auditor
```

**Response:**
```json
{
  "id": "security-auditor",
  "name": "Security Auditor",
  "description": "Performs security audits and identifies vulnerabilities",
  "version": "1.0.0",
  "capabilities": ["security-scan", "vulnerability-detection", "compliance-check"],
  "requiredContext": ["SECURITY", "CODE_REVIEW"],
  "tags": ["security", "audit", "compliance"],
  "priority": 10,
  "status": "ACTIVE"
}
```

### 3. Get Available Context Types

**Request:**
```bash
curl http://localhost:8080/api/agents/context-types
```

**Response:**
```json
[
  "CODE_REVIEW",
  "TESTING",
  "DEPLOYMENT",
  "DOCUMENTATION",
  "BUG_FIXING",
  "FEATURE_DEVELOPMENT",
  "SECURITY",
  "PERFORMANCE",
  "DATABASE",
  "API_DESIGN",
  "UI_UX",
  "DEVOPS",
  "DATA_ANALYSIS",
  "GENERAL"
]
```

## Advanced Queries

### 1. Find Agents by Single Context Type

**Request:**
```bash
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "contextTypes": ["CODE_REVIEW"]
  }'
```

**Response:**
```json
{
  "agents": [
    {
      "id": "code-reviewer",
      "name": "Code Reviewer",
      "priority": 10,
      // ...
    },
    {
      "id": "security-auditor",
      "name": "Security Auditor",
      "priority": 10,
      // ...
    }
  ],
  "totalCount": 2,
  "context": {
    "contextTypes": ["CODE_REVIEW"],
    "tags": [],
    "capabilities": [],
    "excludeStatuses": [],
    "minPriority": null,
    "maxResults": null
  }
}
```

### 2. Find High-Priority Agents

**Request:**
```bash
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "minPriority": 9
  }'
```

**Result:** Returns only agents with priority >= 9

### 3. Find Agents with Multiple Criteria

**Request:**
```bash
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "contextTypes": ["SECURITY", "CODE_REVIEW"],
    "tags": ["security"],
    "minPriority": 8,
    "maxResults": 5
  }'
```

**Use Case:** Find top 5 security-focused agents suitable for code review

### 4. Find Agents by Capability

**Request:**
```bash
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "capabilities": ["test-generation", "unit-testing"]
  }'
```

**Result:** Returns agents that can generate tests

### 5. Exclude Inactive Agents

**Request:**
```bash
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "contextTypes": ["GENERAL"],
    "excludeStatuses": ["INACTIVE", "DEPRECATED", "MAINTENANCE"]
  }'
```

**Use Case:** Only get agents that are currently active

## Real-World Scenarios

### Scenario 1: Code Review Pipeline

```bash
# Step 1: Find code review agents
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "contextTypes": ["CODE_REVIEW"],
    "minPriority": 8,
    "excludeStatuses": ["INACTIVE"]
  }' > agents.json

# Step 2: Extract the highest priority agent
AGENT_ID=$(jq -r '.agents[0].id' agents.json)

# Step 3: Get detailed agent info
curl http://localhost:8080/api/agents/$AGENT_ID

# Step 4: Use this agent for code review (future implementation)
# Execute agent with code review task
```

### Scenario 2: Security Audit Workflow

```bash
# Find all security-related agents
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "tags": ["security", "audit"],
    "capabilities": ["vulnerability-detection", "security-scan"]
  }' | jq '.agents[] | {id, name, capabilities}'

# Output:
# {
#   "id": "security-auditor",
#   "name": "Security Auditor",
#   "capabilities": ["security-scan", "vulnerability-detection", "compliance-check"]
# }
```

### Scenario 3: Testing Strategy

```bash
# Find agents for different testing needs
echo "=== Unit Testing Agents ==="
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "capabilities": ["unit-testing"]
  }' | jq '.agents[] | .name'

echo "=== Integration Testing Agents ==="
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "capabilities": ["integration-testing"]
  }' | jq '.agents[] | .name'
```

### Scenario 4: DevOps Pipeline

```bash
# Find agents for CI/CD and deployment
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "contextTypes": ["DEVOPS", "DEPLOYMENT"],
    "capabilities": ["ci-cd", "deployment"]
  }' | jq
```

### Scenario 5: Multi-Stage Development

```bash
# Stage 1: Development - Find feature development agents
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{"contextTypes": ["FEATURE_DEVELOPMENT"]}'

# Stage 2: Testing - Find testing agents
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{"contextTypes": ["TESTING"]}'

# Stage 3: Security - Find security agents
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{"contextTypes": ["SECURITY"]}'

# Stage 4: Deployment - Find deployment agents
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{"contextTypes": ["DEPLOYMENT"]}'
```

## Integration Examples

### Example 1: Shell Script Integration

```bash
#!/bin/bash
# find-agent.sh - Find the best agent for a task

CONTEXT_TYPE=$1
MIN_PRIORITY=${2:-5}

RESPONSE=$(curl -s -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d "{
    \"contextTypes\": [\"$CONTEXT_TYPE\"],
    \"minPriority\": $MIN_PRIORITY
  }")

AGENT_ID=$(echo $RESPONSE | jq -r '.agents[0].id')
AGENT_NAME=$(echo $RESPONSE | jq -r '.agents[0].name')

echo "Best agent for $CONTEXT_TYPE: $AGENT_NAME (ID: $AGENT_ID)"
```

**Usage:**
```bash
./find-agent.sh CODE_REVIEW 8
# Output: Best agent for CODE_REVIEW: Code Reviewer (ID: code-reviewer)
```

### Example 2: Python Integration

```python
import requests
import json

class AgentOSClient:
    def __init__(self, base_url="http://localhost:8080"):
        self.base_url = base_url
    
    def find_agents(self, context_types=None, capabilities=None, 
                    tags=None, min_priority=None, max_results=None):
        """Find agents matching the criteria"""
        url = f"{self.base_url}/api/agents/query"
        payload = {}
        
        if context_types:
            payload["contextTypes"] = context_types
        if capabilities:
            payload["capabilities"] = capabilities
        if tags:
            payload["tags"] = tags
        if min_priority:
            payload["minPriority"] = min_priority
        if max_results:
            payload["maxResults"] = max_results
        
        response = requests.post(url, json=payload)
        return response.json()
    
    def get_agent(self, agent_id):
        """Get specific agent by ID"""
        url = f"{self.base_url}/api/agents/{agent_id}"
        response = requests.get(url)
        return response.json()
    
    def register_agent(self, agent_data):
        """Register a new agent"""
        url = f"{self.base_url}/api/agents"
        response = requests.post(url, json=agent_data)
        return response.json()

# Usage
client = AgentOSClient()

# Find code review agents
result = client.find_agents(
    context_types=["CODE_REVIEW"],
    min_priority=8
)

print(f"Found {result['totalCount']} agents")
for agent in result['agents']:
    print(f"- {agent['name']} (Priority: {agent['priority']})")
```

### Example 3: Node.js Integration

```javascript
const axios = require('axios');

class AgentOSClient {
  constructor(baseUrl = 'http://localhost:8080') {
    this.baseUrl = baseUrl;
  }

  async findAgents(criteria) {
    const url = `${this.baseUrl}/api/agents/query`;
    const response = await axios.post(url, criteria);
    return response.data;
  }

  async getAgent(agentId) {
    const url = `${this.baseUrl}/api/agents/${agentId}`;
    const response = await axios.get(url);
    return response.data;
  }

  async registerAgent(agentData) {
    const url = `${this.baseUrl}/api/agents`;
    const response = await axios.post(url, agentData);
    return response.data;
  }
}

// Usage
(async () => {
  const client = new AgentOSClient();
  
  // Find security agents
  const result = await client.findAgents({
    tags: ['security'],
    minPriority: 9
  });
  
  console.log(`Found ${result.totalCount} security agents`);
  result.agents.forEach(agent => {
    console.log(`- ${agent.name}: ${agent.description}`);
  });
})();
```

### Example 4: Java Integration

```java
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

public class AgentOSClient {
    private final RestTemplate restTemplate;
    private final String baseUrl;
    
    public AgentOSClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.restTemplate = new RestTemplate();
    }
    
    public AgentQueryResponse findAgents(AgentQueryRequest request) {
        String url = baseUrl + "/api/agents/query";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        HttpEntity<AgentQueryRequest> entity = 
            new HttpEntity<>(request, headers);
        
        ResponseEntity<AgentQueryResponse> response = 
            restTemplate.postForEntity(url, entity, AgentQueryResponse.class);
        
        return response.getBody();
    }
    
    public Agent getAgent(String agentId) {
        String url = baseUrl + "/api/agents/" + agentId;
        return restTemplate.getForObject(url, Agent.class);
    }
}

// Usage
AgentOSClient client = new AgentOSClient("http://localhost:8080");

AgentQueryRequest request = new AgentQueryRequest();
request.setContextTypes(List.of("CODE_REVIEW"));
request.setMinPriority(8);

AgentQueryResponse response = client.findAgents(request);
System.out.println("Found " + response.getTotalCount() + " agents");
```

## Custom Agent Registration Examples

### Example 1: Register ML Model Agent

```bash
curl -X POST http://localhost:8080/api/agents \
  -H "Content-Type: application/json" \
  -d '{
    "id": "ml-model-trainer",
    "name": "ML Model Trainer",
    "description": "Trains and optimizes machine learning models",
    "version": "1.0.0",
    "capabilities": [
      "model-training",
      "hyperparameter-tuning",
      "model-evaluation"
    ],
    "requiredContext": ["DATA_ANALYSIS", "GENERAL"],
    "tags": ["ml", "training", "optimization"],
    "priority": 8,
    "status": "ACTIVE"
  }'
```

### Example 2: Register Database Agent

```bash
curl -X POST http://localhost:8080/api/agents \
  -H "Content-Type: application/json" \
  -d '{
    "id": "db-optimizer",
    "name": "Database Optimizer",
    "description": "Optimizes database queries and schema",
    "version": "1.0.0",
    "capabilities": [
      "query-optimization",
      "index-suggestions",
      "schema-design"
    ],
    "requiredContext": ["DATABASE", "PERFORMANCE"],
    "tags": ["database", "optimization", "sql"],
    "priority": 7,
    "status": "ACTIVE"
  }'
```

### Example 3: Register UI/UX Agent

```bash
curl -X POST http://localhost:8080/api/agents \
  -H "Content-Type: application/json" \
  -d '{
    "id": "ux-reviewer",
    "name": "UX Reviewer",
    "description": "Reviews user interfaces for usability and accessibility",
    "version": "1.0.0",
    "capabilities": [
      "accessibility-check",
      "usability-analysis",
      "design-review"
    ],
    "requiredContext": ["UI_UX", "GENERAL"],
    "tags": ["ux", "accessibility", "design"],
    "priority": 6,
    "status": "ACTIVE"
  }'
```

## Query with URL Parameters (GET)

For simpler queries, use the GET endpoint:

```bash
# Single context type
curl "http://localhost:8080/api/agents/by-context?contextTypes=TESTING"

# Multiple context types
curl "http://localhost:8080/api/agents/by-context?contextTypes=CODE_REVIEW,SECURITY"

# With tags
curl "http://localhost:8080/api/agents/by-context?tags=security,audit"

# With capabilities
curl "http://localhost:8080/api/agents/by-context?capabilities=deployment"

# With max results
curl "http://localhost:8080/api/agents/by-context?contextTypes=GENERAL&maxResults=3"

# Combined parameters
curl "http://localhost:8080/api/agents/by-context?contextTypes=CODE_REVIEW&tags=quality&maxResults=5"
```

## Tips and Best Practices

### 1. Use Specific Context Types
```bash
# ❌ Too broad
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{"contextTypes": ["GENERAL"]}'

# ✅ More specific
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{"contextTypes": ["CODE_REVIEW", "SECURITY"]}'
```

### 2. Combine Multiple Filters
```bash
# ✅ Better filtering
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "contextTypes": ["SECURITY"],
    "capabilities": ["vulnerability-detection"],
    "minPriority": 9,
    "excludeStatuses": ["INACTIVE"]
  }'
```

### 3. Limit Results for Performance
```bash
# ✅ Limit results
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "contextTypes": ["GENERAL"],
    "maxResults": 10
  }'
```

## Troubleshooting Examples

### Check if Service is Running
```bash
curl http://localhost:8080/actuator/health
```

### Verify Agent Exists
```bash
AGENT_ID="code-reviewer"
curl -I http://localhost:8080/api/agents/$AGENT_ID
# Should return 200 OK if exists
```

### Test Query with Pretty Print
```bash
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{"contextTypes": ["CODE_REVIEW"]}' | jq '.'
```

---

These examples should help you get started with the AgentOS Agent Service!
