# AgentOS Plugin System

## Overview

AgentOS uses **PF4J** (Plugin Framework for Java) to support a dynamic plugin system that allows you to extend the agent registry with custom agents packaged as JAR files.

## Features

- 🔌 **Dynamic Loading**: Load plugins at runtime without restarting
- 🔄 **Hot Reload**: Reload plugins to update agents
- 📦 **JAR-based**: Distribute plugins as simple JAR files
- 🎯 **Type-safe**: Full Kotlin/Java type safety
- 🔒 **Isolated**: Plugins run in their own classloader
- 📝 **Versioned**: Support for plugin versioning
- 🚀 **Easy Development**: Simple API for plugin developers

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                   AgentOS Core                       │
│                                                      │
│  ┌────────────────────────────────────────────┐   │
│  │         AgentRegistry                       │   │
│  │  - Built-in agents                         │   │
│  │  - Plugin-provided agents                  │   │
│  └────────────────────────────────────────────┘   │
│                      ▲                              │
│                      │                              │
│  ┌────────────────────────────────────────────┐   │
│  │         PluginService                       │   │
│  │  - Load/unload plugins                     │   │
│  │  - Plugin lifecycle management             │   │
│  └────────────────────────────────────────────┘   │
│                      ▲                              │
│                      │                              │
│  ┌────────────────────────────────────────────┐   │
│  │         PF4J PluginManager                  │   │
│  │  - Plugin discovery                        │   │
│  │  - Classloader management                  │   │
│  └────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
                       │
                       │ Loads from
                       ▼
              ┌─────────────────┐
              │  plugins/       │
              │  ├─ plugin1.jar │
              │  ├─ plugin2.jar │
              │  └─ plugin3.jar │
              └─────────────────┘
```

## Plugin API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/plugins` | List all plugins |
| GET | `/api/plugins/{id}` | Get plugin details |
| POST | `/api/plugins/upload` | Upload and load a plugin |
| POST | `/api/plugins/{id}/start` | Start a plugin |
| POST | `/api/plugins/{id}/stop` | Stop a plugin |
| POST | `/api/plugins/{id}/reload` | Reload a plugin |
| DELETE | `/api/plugins/{id}` | Unload a plugin |
| POST | `/api/plugins/reload-agents` | Reload all agents from plugins |

## Creating a Plugin

### Step 1: Project Structure

Create a new Gradle/Maven project with this structure:

```
my-agent-plugin/
├── build.gradle.kts
└── src/
    └── main/
        ├── kotlin/
        │   └── com/example/
        │       ├── MyPlugin.kt
        │       └── MyAgentPlugin.kt
        └── resources/
            └── plugin.properties
```

### Step 2: Dependencies

**build.gradle.kts:**
```kotlin
plugins {
    kotlin("jvm") version "1.9.25"
}

repositories {
    mavenCentral()
}

dependencies {
    // PF4J (provided by agentOS at runtime)
    compileOnly("org.pf4j:pf4j:3.12.0")
    
    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    
    // AgentOS API (compile only - provided at runtime)
    // In a real setup, you'd publish agentOS API as a separate artifact
}

tasks.jar {
    manifest {
        attributes(
            "Plugin-Id" to "my-agent-plugin",
            "Plugin-Version" to project.version,
            "Plugin-Provider" to "Your Name",
            "Plugin-Class" to "com.example.MyPlugin"
        )
    }
    
    // Include dependencies
    from(configurations.runtimeClasspath.get().map { 
        if (it.isDirectory) it else zipTree(it) 
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

### Step 3: Plugin Descriptor

**src/main/resources/plugin.properties:**
```properties
plugin.id=my-agent-plugin
plugin.class=com.example.MyPlugin
plugin.version=1.0.0
plugin.provider=Your Name
plugin.description=My custom agent plugin
plugin.requires=*
```

### Step 4: Main Plugin Class

**MyPlugin.kt:**
```kotlin
package com.example

import org.pf4j.Plugin
import org.pf4j.PluginWrapper

class MyPlugin(wrapper: PluginWrapper) : Plugin(wrapper) {
    override fun start() {
        println("My Agent Plugin started!")
    }
    
    override fun stop() {
        println("My Agent Plugin stopped!")
    }
}
```

### Step 5: Agent Provider

**MyAgentPlugin.kt:**
```kotlin
package com.example

import io.biznet.agentos.agents.domain.Agent
import io.biznet.agentos.agents.domain.AgentStatus
import io.biznet.agentos.agents.domain.ContextType
import io.biznet.agentos.plugins.AgentPlugin
import org.pf4j.Extension

@Extension
class MyAgentPlugin : AgentPlugin {
    
    override fun getPluginId(): String = "my-agent-plugin"
    
    override fun getVersion(): String = "1.0.0"
    
    override fun getDescription(): String = "My custom agents"
    
    override fun getAgents(): List<Agent> = listOf(
        Agent(
            id = "my-custom-agent",
            name = "My Custom Agent",
            description = "Does something useful",
            version = "1.0.0",
            capabilities = listOf("custom-capability"),
            requiredContext = setOf(ContextType.GENERAL),
            tags = setOf("custom", "example"),
            priority = 7,
            status = AgentStatus.ACTIVE
        )
    )
    
    override fun initialize() {
        println("MyAgentPlugin initialized")
    }
    
    override fun destroy() {
        println("MyAgentPlugin destroyed")
    }
}
```

### Step 6: Build the Plugin

```bash
./gradlew jar
```

This creates a JAR file in `build/libs/my-agent-plugin-1.0.0.jar`

## Using Plugins

### Method 1: Manual Deployment

1. Copy the plugin JAR to the `plugins/` directory:
   ```bash
   cp my-agent-plugin-1.0.0.jar /path/to/agentos/plugins/
   ```

2. Restart agentOS or reload plugins via API

### Method 2: Upload via API

```bash
curl -X POST http://localhost:8080/api/plugins/upload \
  -F "file=@my-agent-plugin-1.0.0.jar"
```

### Method 3: Hot Reload

If the plugin is already loaded:

```bash
curl -X POST http://localhost:8080/api/plugins/my-agent-plugin/reload
```

## Managing Plugins

### List All Plugins

```bash
curl http://localhost:8080/api/plugins | jq
```

**Response:**
```json
[
  {
    "id": "example-agent-plugin",
    "version": "1.0.0",
    "state": "STARTED",
    "description": "Example plugin providing sample agents",
    "provider": "AgentOS",
    "agentCount": 3,
    "pluginPath": "plugins/example-agent-plugin-1.0.0.jar"
  }
]
```

### Get Plugin Details

```bash
curl http://localhost:8080/api/plugins/example-agent-plugin | jq
```

### Start a Plugin

```bash
curl -X POST http://localhost:8080/api/plugins/my-plugin/start
```

### Stop a Plugin

```bash
curl -X POST http://localhost:8080/api/plugins/my-plugin/stop
```

### Reload a Plugin

```bash
curl -X POST http://localhost:8080/api/plugins/my-plugin/reload
```

### Unload a Plugin

```bash
curl -X DELETE http://localhost:8080/api/plugins/my-plugin
```

### Reload All Agents

After adding/updating plugins manually:

```bash
curl -X POST http://localhost:8080/api/plugins/reload-agents
```

## Example Plugin

An example plugin is included in `example-plugin/`. To build and test it:

```bash
cd example-plugin
../gradlew jar

# The plugin JAR will be in build/libs/
# Copy it to the plugins directory
cp build/libs/example-plugin-1.0.0.jar ../plugins/

# Restart agentOS or use the reload API
```

The example plugin provides 3 agents:
- **ml-trainer**: ML Model Trainer
- **db-optimizer**: Database Optimizer
- **ux-reviewer**: UX Reviewer

## Configuration

Configure the plugin system in `application.yml`:

```yaml
agentos:
  plugins:
    directory: plugins        # Plugin directory path
    autoLoad: true           # Auto-load plugins on startup
```

## Plugin Lifecycle

```
┌─────────────┐
│   Created   │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│   Loaded    │ ◄──── loadPlugin()
└──────┬──────┘
       │
       ▼
┌─────────────┐
│   Started   │ ◄──── startPlugin()
└──────┬──────┘       ▲
       │              │
       │              │ reloadPlugin()
       ▼              │
┌─────────────┐       │
│   Stopped   │ ──────┘
└──────┬──────┘
       │
       ▼
┌─────────────┐
│  Unloaded   │ ◄──── unloadPlugin()
└─────────────┘
```

## Advanced Features

### Plugin Dependencies

Specify dependencies in `plugin.properties`:

```properties
plugin.requires=>=1.0.0 & <2.0.0
plugin.dependencies=other-plugin-id
```

### Multiple Agent Providers

A single plugin can have multiple `@Extension` classes:

```kotlin
@Extension
class AgentProviderA : AgentPlugin {
    // ... provides agents A
}

@Extension
class AgentProviderB : AgentPlugin {
    // ... provides agents B
}
```

### Plugin Configuration

Plugins can read their own configuration:

```kotlin
@Extension
class ConfigurablePlugin : AgentPlugin {
    override fun initialize() {
        val config = loadConfiguration()
        // Use configuration
    }
    
    private fun loadConfiguration(): Map<String, String> {
        // Load from file, environment, etc.
        return mapOf()
    }
}
```

### Spring Integration

While plugins don't have direct access to Spring context, you can:

1. **Pass configuration via environment**
2. **Use plugin initialization parameters**
3. **Communicate via well-defined interfaces**

## Best Practices

### 1. Plugin Naming

- Use reverse domain naming: `com.company.plugin-name`
- Keep IDs unique and descriptive
- Use semantic versioning

### 2. Agent IDs

- Prefix agent IDs with plugin ID: `my-plugin:agent-name`
- Ensures uniqueness across plugins
- Makes it clear which plugin provides which agent

```kotlin
Agent(
    id = "${getPluginId()}:custom-agent",
    name = "Custom Agent",
    // ...
)
```

### 3. Error Handling

Always handle errors gracefully:

```kotlin
override fun getAgents(): List<Agent> {
    return try {
        loadAgentsFromConfig()
    } catch (e: Exception) {
        logger.error("Failed to load agents", e)
        emptyList()
    }
}
```

### 4. Resource Cleanup

Clean up in `destroy()`:

```kotlin
override fun destroy() {
    // Close connections
    // Release resources
    // Cancel background tasks
}
```

### 5. Testing

Test plugins independently:

```kotlin
class MyAgentPluginTest {
    @Test
    fun `should provide correct agents`() {
        val plugin = MyAgentPlugin()
        val agents = plugin.getAgents()
        
        assertEquals(3, agents.size)
        assertTrue(agents.all { it.status == AgentStatus.ACTIVE })
    }
}
```

## Troubleshooting

### Plugin Not Loading

1. Check plugin.properties is present
2. Verify manifest attributes in JAR
3. Check logs for errors
4. Ensure plugin.class is correct

```bash
# Inspect JAR contents
jar tf my-plugin.jar | grep plugin.properties
jar xf my-plugin.jar META-INF/MANIFEST.MF
cat META-INF/MANIFEST.MF
```

### Agents Not Appearing

1. Check `@Extension` annotation is present
2. Verify `AgentPlugin` interface is implemented
3. Check logs for loading errors
4. Reload agents via API

```bash
curl -X POST http://localhost:8080/api/plugins/reload-agents
```

### ClassNotFoundException

- Ensure all dependencies are included in plugin JAR
- Use `shadowJar` or similar to create fat JAR
- Check classloader isolation

### Version Conflicts

- Use `compileOnly` for provided dependencies
- Don't bundle agentOS classes in plugin
- Let agentOS provide common libraries

## Security Considerations

1. **Validate Plugin Sources**: Only load trusted plugins
2. **Scan for Vulnerabilities**: Check dependencies
3. **Limit Permissions**: Consider security manager
4. **Audit Plugin Code**: Review before deployment
5. **Monitor Plugin Behavior**: Log and track actions

## Performance

- **Lazy Loading**: Plugins load on-demand
- **Isolated Classloaders**: No interference between plugins
- **Hot Reload**: Update without full restart
- **Minimal Overhead**: PF4J is lightweight

## Example Workflows

### Development Workflow

```bash
# 1. Develop plugin
cd my-plugin
./gradlew jar

# 2. Test locally
cp build/libs/my-plugin.jar ../agentos/plugins/

# 3. Start agentOS
cd ../agentos
./gradlew bootRun

# 4. Verify plugin loaded
curl http://localhost:8080/api/plugins

# 5. Test agents
curl http://localhost:8080/api/agents | jq '.[] | select(.tags[] | contains("my-plugin"))'
```

### Production Deployment

```bash
# 1. Build release
./gradlew clean jar

# 2. Upload to server
scp build/libs/my-plugin-1.0.0.jar server:/opt/agentos/plugins/

# 3. Load via API
curl -X POST http://server:8080/api/plugins/upload \
  -F "file=@my-plugin-1.0.0.jar"

# 4. Verify
curl http://server:8080/api/plugins/my-plugin
```

## Resources

- [PF4J Documentation](https://pf4j.org/)
- [Example Plugin Source](../example-plugin/)
- [AgentOS Plugin API](../src/main/kotlin/io/biznet/agentos/plugins/)
- [Building Plugins Tutorial](./BUILDING_PLUGINS.md)

## FAQ

**Q: Can plugins access the database?**
A: Plugins are isolated. They can only provide agents via the AgentPlugin interface.

**Q: Can I use Spring in plugins?**
A: Plugins don't have access to Spring context. Keep them self-contained.

**Q: How do I debug plugins?**
A: Use standard JVM debugging. Set breakpoints in plugin code.

**Q: Can plugins communicate with each other?**
A: Not directly. They're isolated for security and stability.

**Q: What happens if a plugin crashes?**
A: The plugin is isolated. It won't crash agentOS.

**Q: Can I update a plugin without restart?**
A: Yes! Use the reload API endpoint.

---

**Ready to build your first plugin?** Check out the [example plugin](../example-plugin/) to get started!
