# Building AgentOS Plugins - Quick Start

This guide will walk you through creating your first AgentOS plugin in 10 minutes.

## Prerequisites

- Java 17+
- Gradle or Maven
- Basic Kotlin/Java knowledge

## Quick Start (10 minutes)

### Step 1: Create Project Structure (2 minutes)

```bash
mkdir my-agent-plugin
cd my-agent-plugin

# Create directory structure
mkdir -p src/main/kotlin/com/example
mkdir -p src/main/resources
```

### Step 2: Create build.gradle.kts (2 minutes)

```kotlin
plugins {
    kotlin("jvm") version "1.9.25"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.pf4j:pf4j:3.12.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}

tasks.jar {
    manifest {
        attributes(
            "Plugin-Id" to "my-agent-plugin",
            "Plugin-Version" to version,
            "Plugin-Provider" to "Your Name",
            "Plugin-Class" to "com.example.MyPlugin"
        )
    }
    from(configurations.runtimeClasspath.get().map { 
        if (it.isDirectory) it else zipTree(it) 
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

### Step 3: Create plugin.properties (1 minute)

**src/main/resources/plugin.properties:**
```properties
plugin.id=my-agent-plugin
plugin.class=com.example.MyPlugin
plugin.version=1.0.0
plugin.provider=Your Name
plugin.description=My first agent plugin
plugin.requires=*
```

### Step 4: Create Plugin Class (2 minutes)

**src/main/kotlin/com/example/MyPlugin.kt:**
```kotlin
package com.example

import org.pf4j.Plugin
import org.pf4j.PluginWrapper

class MyPlugin(wrapper: PluginWrapper) : Plugin(wrapper) {
    override fun start() {
        println("My plugin started!")
    }
    
    override fun stop() {
        println("My plugin stopped!")
    }
}
```

### Step 5: Create Agent Provider (3 minutes)

**src/main/kotlin/com/example/MyAgentProvider.kt:**

You'll need to copy the Agent domain classes from agentOS or reference them. For this example, assume they're available:

```kotlin
package com.example

import io.biznet.agentos.agents.domain.Agent
import io.biznet.agentos.agents.domain.AgentStatus
import io.biznet.agentos.agents.domain.ContextType
import io.biznet.agentos.plugins.AgentPlugin
import org.pf4j.Extension

@Extension
class MyAgentProvider : AgentPlugin {
    
    override fun getPluginId(): String = "my-agent-plugin"
    
    override fun getAgents(): List<Agent> = listOf(
        Agent(
            id = "hello-agent",
            name = "Hello Agent",
            description = "My first custom agent",
            version = "1.0.0",
            capabilities = listOf("greeting", "hello-world"),
            requiredContext = setOf(ContextType.GENERAL),
            tags = setOf("custom", "hello"),
            priority = 5,
            status = AgentStatus.ACTIVE
        )
    )
}
```

### Step 6: Build the Plugin

```bash
./gradlew jar
```

Your plugin JAR is now in `build/libs/my-agent-plugin-1.0.0.jar`

### Step 7: Deploy to AgentOS

**Option A: Manual Copy**
```bash
cp build/libs/my-agent-plugin-1.0.0.jar /path/to/agentos/plugins/
```

**Option B: Upload via API**
```bash
curl -X POST http://localhost:8080/api/plugins/upload \
  -F "file=@build/libs/my-agent-plugin-1.0.0.jar"
```

### Step 8: Verify It Works

```bash
# Check plugin is loaded
curl http://localhost:8080/api/plugins | jq

# Check your agent is registered
curl http://localhost:8080/api/agents | jq '.[] | select(.id == "hello-agent")'
```

## Development Workflow

### Hot Reload During Development

```bash
# 1. Make changes to your plugin
# 2. Rebuild
./gradlew jar

# 3. Reload via API
curl -X POST http://localhost:8080/api/plugins/my-agent-plugin/reload
```

### Testing Your Plugin

Create a test:

**src/test/kotlin/com/example/MyAgentProviderTest.kt:**
```kotlin
package com.example

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MyAgentProviderTest {
    
    @Test
    fun `should provide hello agent`() {
        val provider = MyAgentProvider()
        val agents = provider.getAgents()
        
        assertEquals(1, agents.size)
        assertEquals("hello-agent", agents[0].id)
        assertEquals("Hello Agent", agents[0].name)
    }
    
    @Test
    fun `agent should have correct capabilities`() {
        val provider = MyAgentProvider()
        val agent = provider.getAgents()[0]
        
        assertTrue(agent.capabilities.contains("greeting"))
        assertTrue(agent.capabilities.contains("hello-world"))
    }
}
```

Run tests:
```bash
./gradlew test
```

## Common Patterns

### Multiple Agents

```kotlin
@Extension
class MyAgentProvider : AgentPlugin {
    override fun getAgents(): List<Agent> = listOf(
        createAgent1(),
        createAgent2(),
        createAgent3()
    )
    
    private fun createAgent1() = Agent(
        id = "agent-1",
        name = "Agent 1",
        // ...
    )
    
    // ... more agents
}
```

### Configuration-Based Agents

```kotlin
@Extension
class ConfigurableAgentProvider : AgentPlugin {
    
    private val config = loadConfig()
    
    override fun getAgents(): List<Agent> {
        return config.agents.map { agentConfig ->
            Agent(
                id = agentConfig.id,
                name = agentConfig.name,
                description = agentConfig.description,
                // ... from config
            )
        }
    }
    
    private fun loadConfig(): PluginConfig {
        // Load from resources, file, etc.
        return PluginConfig()
    }
}
```

### Dynamic Agent Loading

```kotlin
@Extension
class DynamicAgentProvider : AgentPlugin {
    
    override fun getAgents(): List<Agent> {
        // Load from database, API, file system, etc.
        return fetchAgentsFromExternalSource()
    }
    
    private fun fetchAgentsFromExternalSource(): List<Agent> {
        // Your custom logic here
        return emptyList()
    }
}
```

## Packaging for Distribution

### Create a Fat JAR

Update build.gradle.kts to include all dependencies:

```kotlin
tasks.jar {
    manifest {
        attributes(
            "Plugin-Id" to "my-agent-plugin",
            "Plugin-Version" to version,
            "Plugin-Provider" to "Your Name",
            "Plugin-Class" to "com.example.MyPlugin"
        )
    }
    
    // Include all runtime dependencies
    from(configurations.runtimeClasspath.get().map { 
        if (it.isDirectory) it else zipTree(it) 
    })
    
    // Exclude signatures
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

### Using Shadow Plugin (Alternative)

```kotlin
plugins {
    kotlin("jvm") version "1.9.25"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

shadowJar {
    archiveClassifier.set("")
    manifest {
        attributes(
            "Plugin-Id" to "my-agent-plugin",
            "Plugin-Version" to version,
            "Plugin-Provider" to "Your Name",
            "Plugin-Class" to "com.example.MyPlugin"
        )
    }
}
```

Build with:
```bash
./gradlew shadowJar
```

## Debugging Plugins

### Enable Debug Logging

In agentOS application.yml:
```yaml
logging:
  level:
    org.pf4j: DEBUG
    io.biznet.agentos.plugins: DEBUG
```

### Remote Debugging

Start agentOS with debugging:
```bash
./gradlew bootRun --debug-jvm
```

Then attach your IDE debugger to port 5005.

### Print Debugging

Simple but effective:
```kotlin
@Extension
class MyAgentProvider : AgentPlugin {
    override fun getAgents(): List<Agent> {
        println("Loading agents...")
        val agents = createAgents()
        println("Loaded ${agents.size} agents")
        return agents
    }
}
```

## Publishing Your Plugin

### Create README

**README.md:**
```markdown
# My Agent Plugin

Provides custom agents for AgentOS.

## Installation

1. Download `my-agent-plugin-1.0.0.jar`
2. Copy to agentOS `plugins/` directory
3. Restart agentOS or reload via API

## Agents Provided

- **hello-agent**: Greets users

## Configuration

No configuration required.

## License

MIT
```

### Version Your Plugin

Use semantic versioning in plugin.properties:
```properties
plugin.version=1.0.0
```

And in build.gradle.kts:
```kotlin
version = "1.0.0"
```

### Create Release

```bash
# Tag the release
git tag -a v1.0.0 -m "Release version 1.0.0"
git push origin v1.0.0

# Build release artifact
./gradlew clean jar

# Upload to releases
# Upload build/libs/my-agent-plugin-1.0.0.jar to GitHub releases
```

## Example: Real-World Plugin

Here's a complete example of a useful plugin:

```kotlin
package com.example

import io.biznet.agentos.agents.domain.Agent
import io.biznet.agentos.agents.domain.AgentStatus
import io.biznet.agentos.agents.domain.ContextType
import io.biznet.agentos.plugins.AgentPlugin
import org.pf4j.Extension
import java.io.File

@Extension
class FileBasedAgentProvider : AgentPlugin {
    
    override fun getPluginId(): String = "file-based-agents"
    
    override fun getAgents(): List<Agent> {
        val configFile = File("agents-config.json")
        if (!configFile.exists()) {
            return emptyList()
        }
        
        return try {
            parseAgentsFromFile(configFile)
        } catch (e: Exception) {
            System.err.println("Failed to load agents: ${e.message}")
            emptyList()
        }
    }
    
    private fun parseAgentsFromFile(file: File): List<Agent> {
        // Parse JSON/YAML/etc and create agents
        // This is a simplified example
        return listOf(
            Agent(
                id = "file-agent-1",
                name = "File Agent 1",
                description = "Loaded from file",
                version = "1.0.0",
                capabilities = listOf("file-based"),
                requiredContext = setOf(ContextType.GENERAL),
                tags = setOf("file", "dynamic"),
                priority = 5,
                status = AgentStatus.ACTIVE
            )
        )
    }
}
```

## Troubleshooting

### Plugin Not Found

**Problem:** Plugin doesn't appear in `/api/plugins`

**Solutions:**
1. Check plugin.properties exists in JAR
2. Verify plugin.properties values
3. Check agentOS logs for errors
4. Ensure JAR is in plugins/ directory

### Agents Not Loading

**Problem:** Plugin loads but agents don't appear

**Solutions:**
1. Check `@Extension` annotation
2. Verify `AgentPlugin` interface implementation
3. Check `getAgents()` returns non-empty list
4. Reload agents: `POST /api/plugins/reload-agents`

### ClassNotFoundException

**Problem:** Classes not found at runtime

**Solutions:**
1. Include dependencies in JAR
2. Use shadowJar or fat JAR
3. Check classloader isolation
4. Don't bundle agentOS classes

## Next Steps

- [Plugin System Documentation](./PLUGIN_SYSTEM.md)
- [Example Plugin Source](../example-plugin/)
- [AgentOS API Reference](./AGENT_SERVICE.md)

## Quick Reference

```bash
# Create project
mkdir my-plugin && cd my-plugin

# Build
./gradlew jar

# Deploy
cp build/libs/*.jar /path/to/agentos/plugins/

# Upload
curl -X POST http://localhost:8080/api/plugins/upload \
  -F "file=@build/libs/my-plugin.jar"

# List plugins
curl http://localhost:8080/api/plugins

# Reload plugin
curl -X POST http://localhost:8080/api/plugins/my-plugin/reload

# Check agents
curl http://localhost:8080/api/agents | jq '.[] | select(.tags[] | contains("my-plugin"))'
```

---

**Happy plugin development!** 🎉
