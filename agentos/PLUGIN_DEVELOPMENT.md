# AgentOS Plugin Development Guide

This guide explains how to create plugins for AgentOS using the SDK.

## Overview

AgentOS uses [PF4J](https://pf4j.org/) as its plugin framework. Plugins can extend the system by implementing:
- **Agent Plugins**: AI agents that process user requests
- **Tool Plugins**: Tools that agents can use to perform actions

## Prerequisites

- JDK 17 or higher
- Gradle or Maven
- Basic knowledge of Kotlin (or Java)

## Creating an Agent Plugin

### 1. Setup Project

Create a new Gradle project and add the SDK dependency:

**build.gradle.kts**:
```kotlin
plugins {
    kotlin("jvm") version "1.9.25"
    id("org.pf4j.plugin") version "0.6.0"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
    mavenLocal() // If SDK is published locally
}

dependencies {
    compileOnly("io.biznet.agentos:agentos-sdk:0.0.1-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.jar {
    manifest {
        attributes(
            "Plugin-Class" to "com.example.MyAgentPlugin",
            "Plugin-Id" to "my-agent-plugin",
            "Plugin-Version" to version,
            "Plugin-Provider" to "Your Name"
        )
    }
}
```

### 2. Implement AgentPlugin Interface

Create your agent implementation:

**src/main/kotlin/com/example/MyAgent.kt**:
```kotlin
package com.example

import io.biznet.agentos.sdk.*
import org.pf4j.Extension

@Extension
class MyAgent : AgentPlugin {
    
    override suspend fun execute(input: AgentInput): AgentOutput {
        // Your agent logic here
        val response = processInput(input.message)
        
        return AgentOutput(
            message = response,
            metadata = mapOf(
                "processingTime" to System.currentTimeMillis(),
                "model" to "my-model-v1"
            ),
            conversationId = input.conversationId
        )
    }
    
    override fun getMetadata(): AgentMetadata {
        return AgentMetadata(
            name = "my-agent",
            description = "A custom agent that does amazing things",
            version = "1.0.0",
            capabilities = listOf("chat", "analysis", "summarization")
        )
    }
    
    private fun processInput(message: String): String {
        // Implement your agent logic
        return "Processed: $message"
    }
}
```

### 3. Create Plugin Class

**src/main/kotlin/com/example/MyAgentPlugin.kt**:
```kotlin
package com.example

import org.pf4j.Plugin
import org.pf4j.PluginWrapper

class MyAgentPlugin(wrapper: PluginWrapper) : Plugin(wrapper) {
    
    override fun start() {
        println("MyAgentPlugin started")
    }
    
    override fun stop() {
        println("MyAgentPlugin stopped")
    }
}
```

### 4. Build and Deploy

```bash
# Build the plugin
./gradlew build

# Copy to AgentOS plugins directory
cp build/libs/my-agent-plugin-1.0.0.jar /path/to/agentos/plugins/

# Restart AgentOS
```

## Creating a Tool Plugin

Tools are capabilities that agents can use. Here's how to create one:

**src/main/kotlin/com/example/MyTool.kt**:
```kotlin
package com.example

import io.biznet.agentos.sdk.*
import org.pf4j.Extension

@Extension
class MyTool : ToolPlugin {
    
    override suspend fun execute(parameters: Map<String, Any>): ToolResult {
        return try {
            val input = parameters["input"] as? String
                ?: return ToolResult(false, null, "Missing 'input' parameter")
            
            val result = performAction(input)
            
            ToolResult(
                success = true,
                output = result
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                output = null,
                error = e.message
            )
        }
    }
    
    override fun getDefinition(): ToolDefinition {
        return ToolDefinition(
            name = "my-tool",
            description = "Performs a specific action",
            parameters = listOf(
                ToolParameter(
                    name = "input",
                    description = "The input string to process",
                    type = ParameterType.STRING,
                    required = true
                ),
                ToolParameter(
                    name = "options",
                    description = "Optional configuration",
                    type = ParameterType.OBJECT,
                    required = false,
                    defaultValue = emptyMap<String, Any>()
                )
            )
        )
    }
    
    private fun performAction(input: String): String {
        // Tool implementation
        return "Result: $input"
    }
}
```

## Advanced: Agent with External Dependencies

If your agent needs external libraries (e.g., HTTP client, database driver):

**build.gradle.kts**:
```kotlin
dependencies {
    compileOnly("io.biznet.agentos:agentos-sdk:0.0.1-SNAPSHOT")
    
    // External dependencies - will be bundled in plugin JAR
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
}

tasks.jar {
    // Bundle dependencies in the JAR
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

## Advanced: Agent with Spring AI Integration

Example using Spring AI within a plugin:

**build.gradle.kts**:
```kotlin
dependencies {
    compileOnly("io.biznet.agentos:agentos-sdk:0.0.1-SNAPSHOT")
    
    implementation("org.springframework.ai:spring-ai-openai:1.0.3")
    implementation("org.springframework.ai:spring-ai-anthropic:1.0.3")
}
```

**src/main/kotlin/com/example/AIAgent.kt**:
```kotlin
@Extension
class AIAgent : AgentPlugin {
    
    private val anthropicClient = AnthropicChatClient(
        AnthropicApi(System.getenv("ANTHROPIC_API_KEY"))
    )
    
    override suspend fun execute(input: AgentInput): AgentOutput = withContext(Dispatchers.IO) {
        val prompt = Prompt(input.message)
        val response = anthropicClient.call(prompt)
        
        AgentOutput(
            message = response.result.output.content,
            conversationId = input.conversationId
        )
    }
    
    override fun getMetadata() = AgentMetadata(
        name = "ai-agent",
        description = "Agent powered by Anthropic Claude",
        version = "1.0.0",
        capabilities = listOf("chat", "reasoning")
    )
}
```

## Testing Your Plugin

### Unit Tests

**src/test/kotlin/com/example/MyAgentTest.kt**:
```kotlin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MyAgentTest {
    
    @Test
    fun `test agent execution`() = runBlocking {
        val agent = MyAgent()
        val input = AgentInput(message = "test")
        val output = agent.execute(input)
        
        assertEquals("Processed: test", output.message)
    }
    
    @Test
    fun `test agent metadata`() {
        val agent = MyAgent()
        val metadata = agent.getMetadata()
        
        assertEquals("my-agent", metadata.name)
        assertEquals("1.0.0", metadata.version)
    }
}
```

### Integration Testing with AgentOS

1. Build your plugin: `./gradlew build`
2. Copy to plugins directory: `cp build/libs/*.jar /path/to/agentos/plugins/`
3. Start AgentOS: `cd /path/to/agentos && ./gradlew :agentos-service:bootRun`
4. Test via API:

```bash
# List agents (should include your agent)
curl http://localhost:8080/api/agents

# Execute your agent
curl -X POST http://localhost:8080/api/agents/my-agent/execute \
  -H "Content-Type: application/json" \
  -d '{
    "message": "Hello, agent!",
    "conversationId": "test-123"
  }'
```

## Plugin Best Practices

### 1. Error Handling

Always handle errors gracefully:

```kotlin
override suspend fun execute(input: AgentInput): AgentOutput {
    return try {
        // Your logic
        AgentOutput(message = "Success")
    } catch (e: Exception) {
        log.error("Error executing agent", e)
        AgentOutput(
            message = "An error occurred: ${e.message}",
            metadata = mapOf("error" to true)
        )
    }
}
```

### 2. Logging

Use a logging framework:

```kotlin
import org.slf4j.LoggerFactory

@Extension
class MyAgent : AgentPlugin {
    private val log = LoggerFactory.getLogger(MyAgent::class.java)
    
    override suspend fun execute(input: AgentInput): AgentOutput {
        log.info("Executing agent with input: ${input.message}")
        // ...
    }
}
```

### 3. Configuration

Allow configuration via environment variables or system properties:

```kotlin
@Extension
class ConfigurableAgent : AgentPlugin {
    private val apiKey = System.getenv("MY_AGENT_API_KEY")
        ?: throw IllegalStateException("MY_AGENT_API_KEY not set")
    
    private val timeout = System.getProperty("my.agent.timeout", "30000").toLong()
    
    // ...
}
```

### 4. Resource Management

Clean up resources properly:

```kotlin
class MyAgentPlugin(wrapper: PluginWrapper) : Plugin(wrapper) {
    private var httpClient: OkHttpClient? = null
    
    override fun start() {
        httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    
    override fun stop() {
        httpClient?.dispatcher?.executorService?.shutdown()
        httpClient = null
    }
}
```

### 5. Metadata

Provide comprehensive metadata:

```kotlin
override fun getMetadata() = AgentMetadata(
    name = "my-agent",
    description = "A detailed description of what this agent does, " +
                  "including its capabilities and use cases",
    version = "1.0.0",
    capabilities = listOf(
        "natural-language-processing",
        "sentiment-analysis",
        "text-summarization"
    )
)
```

## Plugin Directory Structure

Recommended project structure:

```
my-agent-plugin/
├── build.gradle.kts
├── settings.gradle.kts
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   └── com/example/
│   │   │       ├── MyAgentPlugin.kt
│   │   │       ├── MyAgent.kt
│   │   │       └── MyTool.kt
│   │   └── resources/
│   │       └── plugin.properties (optional)
│   └── test/
│       └── kotlin/
│           └── com/example/
│               ├── MyAgentTest.kt
│               └── MyToolTest.kt
└── README.md
```

## Publishing Your Plugin

### Maven Local

```bash
./gradlew publishToMavenLocal
```

### Maven Repository

Configure publishing in `build.gradle.kts`:

```kotlin
plugins {
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            
            groupId = "com.example"
            artifactId = "my-agent-plugin"
            version = "1.0.0"
        }
    }
    repositories {
        maven {
            url = uri("https://your-maven-repo.com/releases")
            credentials {
                username = project.findProperty("mavenUser") as String?
                password = project.findProperty("mavenPassword") as String?
            }
        }
    }
}
```

## Examples Repository

Check out example plugins in the AgentOS repository:
- Simple Echo Agent
- HTTP Tool Plugin
- OpenAI Integration Agent

## Troubleshooting

### Plugin Not Loaded

1. Check logs: `docker logs agentos-service | grep pf4j`
2. Verify `plugin.properties` or JAR manifest
3. Ensure plugin class is in manifest: `Plugin-Class`

### ClassNotFoundException

Your plugin dependencies might not be bundled. Use fat JAR:

```kotlin
tasks.jar {
    from(configurations.runtimeClasspath.get().map { 
        if (it.isDirectory) it else zipTree(it) 
    })
}
```

### Plugin Conflicts

If multiple plugins use the same dependency with different versions, use shading:

```kotlin
plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

tasks.shadowJar {
    relocate("com.google.gson", "com.example.shaded.gson")
}
```

## Support

- Documentation: [README.md](README.md)
- API Reference: [SDK JavaDoc](agentos-sdk/build/docs/javadoc)
- Issues: [GitHub Issues](https://github.com/whoz-oss/coday/issues)

## License

See [LICENSE](../LICENSE) file.
