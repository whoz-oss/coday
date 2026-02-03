# AgentOS SDK

The AgentOS SDK provides the core interfaces and extension points for developing plugins for the AgentOS platform.

## Overview

The SDK is a lightweight library that contains:
- Plugin interfaces based on PF4J
- Core domain models (Agent, Capability, Context, etc.)
- Extension points for custom implementations

**Dependencies:** Only PF4J - No Spring Boot, No Spring AI

## Installation

### From GitHub Packages

Add the repository and dependency to your `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/whoz-oss/coday")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    compileOnly("whoz-oss.agentos:agentos-sdk:1.0.0")
}
```

### From Local Build

If you're developing locally:

```bash
cd agentos
./gradlew :agentos-sdk:publishToMavenLocal
```

Then in your plugin:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    compileOnly("whoz-oss.agentos:agentos-sdk:1.0.0")
}
```

## Core Interfaces

### AgentPlugin
TbD

## Creating a Plugin

### 1. Project Setup

Create a new Gradle project:

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "1.9.25"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/whoz-oss/coday")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    compileOnly("whoz-oss.agentos:agentos-sdk:1.0.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}

tasks.jar {
    manifest {
        attributes(
            "Plugin-Id" to "my-plugin",
            "Plugin-Version" to version,
            "Plugin-Provider" to "Example Inc.",
            "Plugin-Class" to "com.example.MyPlugin"
        )
    }
}
```

### 2. Implement Plugin

```kotlin

class MyPlugin : Plugin() {
    
    override fun start() {
        println("My Plugin started!")
    }
    
    override fun stop() {
        println("My Plugin stopped!")
    }
}

@Extension
class MyAgentProvider : AgentPlugin {
    
    override fun provideAgents(): List<Agent> {
        return listOf(
            Agent(
                id = "my-custom-agent",
                name = "My Custom Agent",
                description = "Does something awesome",
                capabilities = listOf("custom-capability", "analysis"),
                contexts = listOf(ContextType.GENERAL, ContextType.CODE_REVIEW),
                tags = listOf("custom", "example"),
                priority = 8,
                status = AgentStatus.ACTIVE,
                version = "1.0.0"
            )
        )
    }
}
```

### 3. Build and Deploy

```bash
# Build the plugin
./gradlew jar

# Copy to AgentOS plugins directory
cp build/libs/my-plugin-1.0.0.jar /path/to/agentos/plugins/
```

### 4. Verify

Start AgentOS and check the logs:

```bash
cd agentos
./gradlew :agentos-service:bootRun
```

You should see:
```
My Plugin started!
Registered agent: my-custom-agent
```

## API Reference

### AgentPlugin Interface

```kotlin
interface AgentPlugin {
    /**
     * Provides a list of agents that this plugin contributes.
     * Called once when the plugin is loaded.
     */
    fun provideAgents(): List<Agent>
}
```

### Agent Data Class

```kotlin
data class Agent(
    val id: String,                    // Unique identifier
    val name: String,                  // Display name
    val description: String,           // What the agent does
    val capabilities: List<String>,    // What it can do
    val contexts: List<ContextType>,   // Where it applies
    val tags: List<String> = emptyList(), // Searchable tags
    val priority: Int = 5,             // 1-10, higher = preferred
    val status: AgentStatus = ACTIVE,  // Current status
    val version: String = "1.0.0"      // Plugin version
)
```

## Best Practices

### 1. Plugin Naming

- Use descriptive, unique plugin IDs
- Follow reverse domain naming: `com.company.product-plugin`
- Keep names short but meaningful

### 2. Agent Priority

- 1-3: Low priority, fallback agents
- 4-6: Normal priority, general purpose
- 7-8: High priority, specialized agents
- 9-10: Critical priority, core functionality

### 3. Capabilities

- Use lowercase, hyphenated names
- Be specific: `java-code-review` not `review`
- Document capabilities in plugin README

### 4. Contexts

- Choose the most specific context
- Multiple contexts are OK
- GENERAL is a catch-all

### 5. Versioning

- Use semantic versioning (MAJOR.MINOR.PATCH)
- Update version on interface changes
- Document breaking changes

## Troubleshooting

### Plugin not loading

Check:
1. JAR is in the correct directory
2. Manifest attributes are correct
3. Plugin implements the right interfaces
4. No dependency conflicts

### ClassNotFoundException

- Ensure all dependencies are bundled in JAR
- Or use `compileOnly` for SDK

### Agent not appearing

- Check plugin started successfully
- Verify `provideAgents()` returns non-empty list
- Check agent ID is unique

## Examples

See the [examples](../examples/) directory for:
- `simple-plugin` - Basic agent plugin
- Advanced examples coming soon

## Contributing

To contribute to the SDK:

1. Fork the repository
2. Make changes in `agentos-sdk/`
3. Update version if needed
4. Submit a pull request

## License

Apache License 2.0

## Support

- GitHub Issues: https://github.com/whoz-oss/coday/issues
- Documentation: https://github.com/whoz-oss/coday/tree/main/agentos/docs

---

**Version:** 1.0.0  
**Built with:** PF4J 3.13.0 + Kotlin 1.9.25
