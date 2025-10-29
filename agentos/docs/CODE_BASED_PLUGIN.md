# Code-Based Agents Plugin

## Overview

The Code-Based Agents Plugin provides hardcoded agents defined directly in Kotlin code. This is useful for:
- Agents that don't change frequently
- Agents with complex initialization logic
- Agents bundled with the plugin
- Testing and demonstration purposes

## Features

- ✅ Agents defined in Kotlin code
- ✅ Type-safe agent definitions
- ✅ Easy to version control
- ✅ No external dependencies
- ✅ Fast loading

## Agents Provided

### 1. Data Scientist
- **ID**: `data-scientist`
- **Capabilities**: data-analysis, machine-learning, statistical-modeling, data-visualization
- **Priority**: 8
- **Use Case**: Data analysis, ML model training, statistical insights

### 2. Frontend Architect
- **ID**: `frontend-architect`
- **Capabilities**: frontend-architecture, component-design, state-management, performance-optimization
- **Priority**: 9
- **Use Case**: Frontend architecture, React/Angular/Vue expertise, UI/UX optimization

### 3. Backend Architect
- **ID**: `backend-architect`
- **Capabilities**: backend-architecture, api-design, database-design, microservices
- **Priority**: 9
- **Use Case**: Backend systems design, API architecture, scalability

### 4. Cloud Engineer
- **ID**: `cloud-engineer`
- **Capabilities**: cloud-infrastructure, kubernetes, docker, ci-cd, terraform
- **Priority**: 8
- **Use Case**: Cloud infrastructure, container orchestration, DevOps

### 5. QA Automation Engineer
- **ID**: `qa-automation`
- **Capabilities**: test-automation, unit-testing, integration-testing, e2e-testing
- **Priority**: 7
- **Use Case**: Test automation, quality assurance, CI integration

## Building

```bash
cd agentos/code-based-plugin
../gradlew jar
```

Output: `build/libs/code-based-plugin-1.0.0.jar`

## Installation

### Method 1: Upload via API
```bash
curl -X POST http://localhost:8080/api/plugins/upload \
  -F "file=@build/libs/code-based-plugin-1.0.0.jar"
```

### Method 2: Manual Copy
```bash
cp build/libs/code-based-plugin-1.0.0.jar ../plugins/
# Restart agentOS or reload plugins
```

## Verification

```bash
# Check plugin is loaded
curl http://localhost:8080/api/plugins/code-based-agents | jq

# List agents from this plugin
curl http://localhost:8080/api/agents | \
  jq '.[] | select(.tags[] | contains("code-based"))'
```

## Adding New Agents

Edit `CodeBasedAgentProvider.kt`:

```kotlin
override fun getAgents(): List<Agent> = listOf(
    createDataScientistAgent(),
    createFrontendArchitectAgent(),
    // ... existing agents
    createYourNewAgent()  // Add your new agent
)

private fun createYourNewAgent() = Agent(
    id = "your-agent-id",
    name = "Your Agent Name",
    description = "What your agent does",
    version = "1.0.0",
    capabilities = listOf("capability1", "capability2"),
    requiredContext = setOf(ContextType.GENERAL),
    tags = setOf("your-tag", "code-based"),
    priority = 8,
    status = AgentStatus.ACTIVE
)
```

Then rebuild and reload:
```bash
../gradlew jar
curl -X POST http://localhost:8080/api/plugins/code-based-agents/reload
```

## Advantages

### ✅ Type Safety
- Compile-time checking
- IDE autocomplete
- Refactoring support

### ✅ Version Control
- Track changes in Git
- Easy code review
- Merge conflict resolution

### ✅ Complex Logic
- Can add initialization code
- Can call external services
- Can compute agent properties dynamically

### ✅ Testing
- Easy to unit test
- Mock dependencies
- Test agent creation logic

## Disadvantages

### ❌ Requires Rebuild
- Must rebuild JAR to add/modify agents
- Need to reload plugin
- Development cycle longer than filesystem plugin

### ❌ Not User-Editable
- Non-developers can't modify agents
- Requires Kotlin knowledge
- Must deploy new version

## When to Use

**Use Code-Based Plugin when:**
- Agents are stable and don't change often
- You need type safety and compile-time checks
- Agents have complex initialization logic
- You want to bundle agents with code
- You're comfortable with Kotlin development

**Use Filesystem Plugin when:**
- Agents change frequently
- Non-developers need to modify agents
- You want quick updates without rebuild
- You prefer configuration over code
- You need dynamic agent loading

## Example: Dynamic Agent Creation

```kotlin
private fun createAgentsFromConfig(): List<Agent> {
    val config = loadConfiguration()
    return config.agentDefinitions.map { def ->
        Agent(
            id = def.id,
            name = def.name,
            description = def.description,
            version = def.version,
            capabilities = def.capabilities,
            requiredContext = setOf(ContextType.GENERAL),
            tags = setOf("code-based", "dynamic"),
            priority = def.priority,
            status = AgentStatus.ACTIVE
        )
    }
}
```

## Configuration

The plugin doesn't require configuration. All agents are defined in code.

## Troubleshooting

### Plugin Loads But No Agents
**Check:**
- `@Extension` annotation is present on `CodeBasedAgentProvider`
- `getAgents()` returns non-empty list
- Agents have valid IDs and names

### Agents Not Updating
**Solution:**
```bash
# Rebuild
../gradlew jar

# Reload plugin
curl -X POST http://localhost:8080/api/plugins/code-based-agents/reload
```

### Build Fails
**Check:**
- Kotlin version compatibility
- All dependencies available
- No syntax errors in agent definitions

## Source Code

**Location**: `agentos/code-based-plugin/`

**Structure**:
```
code-based-plugin/
├── build.gradle.kts
├── src/
│   └── main/
│       ├── kotlin/
│       │   └── io/biznet/agentos/plugins/codebased/
│       │       ├── CodeBasedPlugin.kt
│       │       └── CodeBasedAgentProvider.kt
│       └── resources/
│           └── plugin.properties
```

## API Examples

```bash
# Get plugin info
curl http://localhost:8080/api/plugins/code-based-agents | jq

# Get data scientist agent
curl http://localhost:8080/api/agents/data-scientist | jq

# Query for cloud engineering agents
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "capabilities": ["cloud-infrastructure"],
    "tags": ["code-based"]
  }' | jq
```

## Best Practices

1. **Consistent Naming**: Use kebab-case for IDs
2. **Clear Descriptions**: Make agent purposes obvious
3. **Appropriate Priorities**: Use 1-10 scale consistently
4. **Meaningful Tags**: Include "code-based" tag
5. **Version Agents**: Update version when changing capabilities

## Future Enhancements

- [ ] Load agent definitions from embedded JSON/YAML
- [ ] Support for agent templates
- [ ] Agent factory patterns
- [ ] Dynamic capability discovery
- [ ] Agent composition

---

**Ready to use!** Build the plugin and upload it to agentOS.
