# AgentOS

A modular Spring Boot application for orchestrating AI agents with a plugin system based on PF4J.

## Architecture

AgentOS uses **Gradle Composite Builds** with centralized dependency management via **Version Catalog**:

```
agentos/
├── gradle/
│   └── libs.versions.toml    # 🎯 Centralized version management
├── agentos-sdk/               # Plugin SDK (interfaces + PF4J)
│   └── settings.gradle.kts    # Independent build configuration
├── agentos-service/           # Spring Boot service
│   └── settings.gradle.kts    # Independent build configuration
└── examples/
    └── simple-plugin/         # Example plugin
```

### agentos-sdk

The SDK module contains:
- Plugin interfaces and extension points
- Core domain models
- Only dependency: **PF4J** (Plugin Framework for Java)

This module is published to GitHub Packages and can be used by external plugin developers.

**Version**: `1.0.0` (defined in `gradle/libs.versions.toml`)

### agentos-service

The service module contains:
- Spring Boot application
- Agent orchestration logic
- REST API endpoints
- Spring AI integrations
- Dependencies: Spring Boot, Spring AI, PF4J-Spring, and **agentos-sdk**

**Version**: `0.0.1-SNAPSHOT` (defined in `gradle/libs.versions.toml`)

## Prerequisites

- Java 17+
- Gradle 9+ (wrapper included)
- Node.js 20+ (for Nx build orchestration)

## Building

### Build all modules

```bash
cd agentos
./gradlew build
```

### Build specific modules

```bash
# Build SDK only
./gradlew :agentos-sdk:build

# Build Service only (automatically builds SDK if needed)
./gradlew :agentos-service:build
```

### Build modules independently

Thanks to composite builds, each module can be built independently:

```bash
# Build SDK independently
cd agentos-sdk
../gradlew build

# Build Service independently
cd agentos-service
../gradlew build
```

### Build with Nx (recommended for CI)

```bash
# Install Nx globally
npm install -g nx

# Build SDK
nx build agentos-sdk

# Build Service (automatically builds SDK first)
nx build agentos-service

# Build only affected projects (in PR context)
nx affected -t build --base=origin/main
```

## Running

### Run the service

```bash
cd agentos
./gradlew :agentos-service:bootRun
```

Or with Nx:

```bash
nx bootRun agentos-service
```

The API will be available at `http://localhost:8080`

## Testing

### Test all modules

```bash
cd agentos
./gradlew test
```

### Test with Nx

```bash
# Test all
nx test agentos-sdk
nx test agentos-service

# Test only affected
nx affected -t test --base=origin/main
```

## Publishing the SDK

The SDK can be published to Maven Local or GitHub Packages:

```bash
# Publish to Maven Local (~/.m2/repository)
./gradlew :agentos-sdk:publishToMavenLocal

# Publish to GitHub Packages
./gradlew :agentos-sdk:publish
```

Or with Nx:

```bash
nx publish agentos-sdk
```

### Using the SDK in your plugin

Add to your plugin's `build.gradle.kts`:

```kotlin
repositories {
    mavenLocal() // For local development
    
    maven {
        url = uri("https://maven.pkg.github.com/whoz-oss/coday")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    compileOnly("io.whozoss.agentos:agentos-sdk:1.0.0")
}
```

## Version Management

All dependency versions are centralized in `gradle/libs.versions.toml`:

```toml
[versions]
kotlin = "1.9.25"
springBoot = "3.5.7"
agentosSdk = "1.0.0"
agentosService = "0.0.1-SNAPSHOT"

[libraries]
pf4j = { module = "org.pf4j:pf4j", version.ref = "pf4j" }
# ... more libraries
```

### Updating a dependency version

1. Edit `gradle/libs.versions.toml`
2. Rebuild: `./gradlew clean build`

All modules will automatically use the updated version.

### Updating module versions

Module versions are also defined in `gradle/libs.versions.toml`:

```toml
[versions]
agentosSdk = "1.1.0"      # Update SDK version here
agentosService = "0.2.0"  # Update Service version here
```

## CI/CD

The project uses GitHub Actions with Nx for efficient CI/CD:

- **On PR**: Uses `nx affected` to build/test only changed projects
- **On Main Push**: Publishes SDK to GitHub Packages

## Project Structure

```
agentos/
├── gradle/
│   └── libs.versions.toml           # 🎯 Centralized version catalog
├── settings.gradle.kts              # Root configuration (composite builds)
├── build.gradle.kts                 # Root build configuration
├── nx.json                          # Nx workspace configuration
├── project.json                     # Nx project configuration
│
├── agentos-sdk/
│   ├── settings.gradle.kts         # SDK independent configuration
│   ├── build.gradle.kts            # SDK build (uses libs.*)
│   ├── project.json                # Nx SDK configuration
│   └── src/
│       └── main/kotlin/
│           └── io/biznet/agentos/
│               └── sdk/            # Plugin interfaces
│
├── agentos-service/
│   ├── settings.gradle.kts         # Service independent configuration
│   ├── build.gradle.kts            # Service build (uses libs.*)
│   ├── project.json                # Nx Service configuration
│   └── src/
│       └── main/
│           ├── kotlin/
│           │   └── io/biznet/agentos/
│           │       ├── AgentosApplication.kt
│           │       ├── agents/     # Agent registry
│           │       ├── orchestrator/
│           │       └── plugins/    # Plugin loading
│           └── resources/
│               └── application.yml
│
└── examples/
    └── simple-plugin/
        ├── build.gradle.kts        # Example plugin
        └── src/main/kotlin/
```

## Key Features

- 🏗️ **Composite Builds**: Independent build for each module
- 📦 **Version Catalog**: Centralized dependency management
- 🔌 **Plugin System**: PF4J-based extensibility
- ⚡ **Nx Integration**: Fast, incremental builds in CI
- 📤 **SDK Publishing**: Reusable SDK for plugin developers
- 🧪 **Full Testing**: Unit and integration tests
- 🚀 **GitHub Actions**: Automated CI/CD pipeline
- 🎯 **Type-Safe Dependencies**: IDE autocompletion with `libs.*`

## Development

### Adding a new plugin
To create a new plugin please read [AgentOS SDK README.md](agentos-sdk/README.md) which describe how
to create a new project using AgentOS SDK.
A plugin should be deployed in the plugins directory of your AgentOS Service instance.
In case you use docker-compose you should ensure the proper location of the directory.

### Modifying the SDK

When you modify the SDK:

1. Make your changes in `agentos-sdk/src/`
2. Build: `./gradlew :agentos-sdk:build`
3. Publish locally: `./gradlew :agentos-sdk:publishToMavenLocal`
4. The service will automatically use the updated SDK

To release a new version:

1. Update version in `gradle/libs.versions.toml`:
   ```toml
   agentosSdk = "1.1.0"
   ```
2. Build and test: `./gradlew :agentos-sdk:build`
3. Publish: `./gradlew :agentos-sdk:publish`

### Modifying the Service

The service automatically depends on the SDK via composite builds. Changes to the SDK are immediately visible during development.

### Adding a new dependency

1. Add version to `gradle/libs.versions.toml`:
   ```toml
   [versions]
   newLib = "1.2.3"
   
   [libraries]
   new-lib = { module = "com.example:library", version.ref = "newLib" }
   ```

2. Use in `build.gradle.kts`:
   ```kotlin
   dependencies {
       implementation(libs.new.lib)
   }
   ```

### Available dependency bundles

The version catalog includes several bundles for common dependency groups:

```kotlin
// Kotlin common dependencies
implementation(libs.bundles.kotlin.common)

// Kotlin coroutines
implementation(libs.bundles.kotlin.coroutines)

// Spring AI (Anthropic, OpenAI, MCP)
implementation(libs.bundles.spring.ai)

// Testing (Kotlin test + MockK)
testImplementation(libs.bundles.testing.common)
```

## Configuration

Edit `agentos-service/src/main/resources/application.yml`:

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}

agentos:
  plugins:
    dir: plugins
```

## Verification

Run the verification script to ensure the composite build configuration is working:

```bash
chmod +x verify-composite-build.sh
./verify-composite-build.sh
```

This script will:
- ✅ Verify all configuration files
- ✅ Build all modules
- ✅ Publish SDK locally
- ✅ Test independent builds
- ✅ Verify generated artifacts

## Tool Registration for Plugins

When registering a `StandardTool` from a plugin with Spring AI, always implement `ToolCallback` directly using `DefaultToolDefinition`. **Do not use `MethodToolCallback`.**

### Why

`MethodToolCallback` generates the LLM-facing JSON schema by reflecting on the Java method signature. If your wrapper method is `invoke(jsonArgs: String?)`, the LLM receives a schema with a single opaque `jsonArgs` property — not your tool's real parameters (e.g. `timezone`). The LLM therefore sends empty arguments and cannot use the tool correctly.

### Correct pattern

```kotlin
fun wrapTool(tool: StandardTool<*>): ToolCallback =
    object : ToolCallback {
        private val definition =
            DefaultToolDefinition.builder()
                .name(tool.name)
                .description(tool.description)
                .inputSchema(tool.inputSchema)  // verbatim — no reflection override
                .build()

        override fun getToolDefinition() = definition

        override fun call(toolInput: String): String =
            tool.executeWithJson(toolInput)  // plugin-classloader-safe deserialization
    }
```

This ensures:
- The schema the LLM sees matches exactly what the tool declares in `inputSchema`
- Deserialization stays inside the plugin classloader via `executeWithJson`
- Works with Spring AI 1.1.x (Spring Boot 3.x) and is forward-compatible with 2.x

### Conversation history safety

When replaying history that contains `ToolRequestEvent` entries, normalise `null` or blank `args` to `"{}"` before constructing `AssistantMessage.ToolCall`. Spring AI's `AnthropicChatModel` calls `ModelOptionsUtils.jsonToMap()` on the arguments string, which throws `MismatchedInputException` on an empty string:

```kotlin
val safeArgs = event.args?.takeIf { it.isNotBlank() } ?: "{}"
AssistantMessage.ToolCall(event.toolRequestId, "function", event.toolName, safeArgs)
```

## Troubleshooting

### Nx command not found

Install Nx globally:
```bash
npm install -g nx@latest
```

### Gradle build fails

Clean and rebuild:
```bash
./gradlew clean build --refresh-dependencies
```

### IDE doesn't recognize `libs.*`

1. Refresh Gradle dependencies:
   ```bash
   ./gradlew --refresh-dependencies
   ```
2. In IntelliJ IDEA: File → Invalidate Caches → Invalidate and Restart
3. Verify `enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")` is in `settings.gradle.kts`

### "Could not find agentos-sdk"

The service can't find the SDK. Publish it locally:
```bash
./gradlew :agentos-sdk:publishToMavenLocal
```

### Plugin not loading

Check that:
1. Plugin JAR is in `agentos-service/plugins/` directory
2. Plugin implements the correct interfaces from `agentos-sdk`
3. Spring Boot DevTools is disabled (it causes classloader conflicts)

## Documentation

- [AgentOS SDK README.md](agentos-sdk/README.md) - How to develop plugins using AgentOS SDK
- [Quick Start](QUICKSTART.md) - Get started quickly

## License

Apache License 2.0

---

**Built with Spring Boot 3.5 + Kotlin 2 + Spring AI + PF4J + Gradle Composite Builds**
