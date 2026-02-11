# AgentOS Quick Start Guide

Get up and running with AgentOS in 5 minutes!

## Prerequisites

- ✅ Java 17 or higher
- ✅ Node.js 20+ (for Nx)
- ✅ Git

## Step 1: Clone and Navigate

```bash
cd agentos
```

## Step 2: Install Nx (Optional but Recommended)

```bash
npm install -g nx@latest
```

## Step 3: Build the Project

### Option A: Using the script

```bash
chmod +x scripts/*.sh
./scripts/build-all.sh
```

### Option B: Using Gradle directly

```bash
./gradlew build
```

### Option C: Using Nx

```bash
nx build agentos-sdk
nx build agentos-service
```

## Step 4: Configure Environment

Create a `.env` file or export variables:

```bash
export OPENAI_API_KEY="your-openai-key"
export ANTHROPIC_API_KEY="your-anthropic-key"
```

Or create `agentos-service/src/main/resources/application-local.yml`:

```yaml
spring:
  ai:
    openai:
      api-key: your-openai-key
    anthropic:
      api-key: your-anthropic-key
```

## Step 5: Run the Service

### Option A: Using the script

```bash
./scripts/run-service.sh
```

### Option B: Using Gradle

```bash
./gradlew :agentos-service:bootRun
```

### Option C: Using Nx

```bash
nx bootRun agentos-service
```

## Step 6: Verify It's Running

Open your browser or use curl:

```bash
# Health check
curl http://localhost:8080/actuator/health

# Should return:
# {"status":"UP"}
```

## Step 7: Test the API

### List all agents

```bash
curl http://localhost:8080/api/agents | jq
```

### Query agents by context

```bash
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "contextTypes": ["CODE_REVIEW"],
    "minPriority": 7
  }' | jq
```

## Next Steps

### Create Your First Plugin

1. **Create a new Gradle project:**

```bash
mkdir my-plugin
cd my-plugin
```

2. **Create `build.gradle.kts`:**

```kotlin
plugins {
    kotlin("jvm") version "1.9.25"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    compileOnly("io.whozoss.agentos:agentos-sdk:1.0.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}

tasks.jar {
    manifest {
        attributes(
            "Plugin-Id" to "my-plugin",
            "Plugin-Version" to version,
            "Plugin-Provider" to "Your Name",
            "Plugin-Class" to "com.example.MyPlugin"
        )
    }
}
```

3. **Implement your plugin:**

```kotlin
class MyPlugin : Plugin()

@Extension
class MyAgentProvider : AgentPlugin {
    override fun provideAgents(): List<Agent> {
        return listOf(
            Agent(
                id = "my-agent",
                name = "My Custom Agent",
                description = "Does something cool",
                capabilities = listOf("custom"),
                contexts = listOf(ContextType.GENERAL),
                priority = 8
            )
        )
    }
}
```

4. **Build and deploy:**

```bash
./gradlew jar
cp build/libs/my-plugin-1.0.0.jar /path/to/agentos/agentos-service/plugins/
```

5. **Restart AgentOS and verify:**

```bash
curl http://localhost:8080/api/agents | jq '.[] | select(.id == "my-agent")'
```

## Common Tasks

### Building

```bash
# Clean build
./gradlew clean build

# Build SDK only
nx build agentos-sdk

# Build with tests
./gradlew build test
```

### Testing

```bash
# All tests
./gradlew test

# SDK tests only
nx test agentos-sdk

# Service tests only
nx test agentos-service
```

### Development

```bash
# Run with auto-reload (limited due to PF4J)
./gradlew :agentos-service:bootRun

# Run with specific profile
./gradlew :agentos-service:bootRun --args='--spring.profiles.active=local'
```

### Publishing

```bash
# Publish SDK to local Maven
./gradlew :agentos-sdk:publishToMavenLocal

# Publish to GitHub Packages
export GITHUB_ACTOR=your-username
export GITHUB_TOKEN=your-token
./scripts/publish-sdk.sh
```

## Troubleshooting

### Build Fails

```bash
# Clear caches and rebuild
./gradlew clean build --refresh-dependencies

# Check Java version
java -version  # Should be 17+
```

### Service Won't Start

1. Check logs in console
2. Verify ports are available:
   ```bash
   lsof -i :8080
   ```
3. Check configuration in `application.yml`

### Plugin Not Loading

1. Check plugin JAR is in `plugins/` directory
2. Verify manifest attributes in JAR:
   ```bash
   jar xf my-plugin.jar META-INF/MANIFEST.MF
   cat META-INF/MANIFEST.MF
   ```
3. Check logs for plugin loading errors

### API Keys Not Working

1. Verify environment variables:
   ```bash
   echo $OPENAI_API_KEY
   ```
2. Check `application.yml` configuration
3. Restart the service after changing keys

## IDE Setup

### IntelliJ IDEA

1. **Open Project:**
   - File → Open → Select `agentos` directory
   - Import as Gradle project

2. **Configure SDK:**
   - File → Project Structure → Project
   - Set SDK to Java 17

3. **Run Configuration:**
   - Add new "Spring Boot" configuration
   - Main class: `io.whozoss.agentos.AgentosApplication`
   - Module: `agentos-service.main`
   - Add environment variables

### VS Code

1. **Install Extensions:**
   - Kotlin Language
   - Gradle for Java
   - Spring Boot Extension Pack

2. **Open Folder:**
   - Open `agentos` directory

3. **Run:**
   - Use Gradle tasks view
   - Or terminal: `./gradlew :agentos-service:bootRun`

## Useful Endpoints

```bash
# Health check
curl http://localhost:8080/actuator/health

# Metrics
curl http://localhost:8080/actuator/metrics

# List all agents
curl http://localhost:8080/api/agents

# Get specific agent
curl http://localhost:8080/api/agents/{agentId}

# Query agents
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{"contextTypes": ["CODE_REVIEW"]}'

# List plugins
curl http://localhost:8080/api/plugins

# Reload plugin (if supported)
curl -X POST http://localhost:8080/api/plugins/{pluginId}/reload
```

## Learning Resources

- **Project Documentation:**
  - [README.md](README.md) - Overview
  - [AgentOS SDK README.md](agentos-sdk/README.md) - SDK guide

- **External Resources:**
  - [Spring Boot Docs](https://spring.io/projects/spring-boot)
  - [Spring AI Docs](https://spring.io/projects/spring-ai)
  - [PF4J Docs](https://pf4j.org/)
  - [Nx Docs](https://nx.dev/)

## Getting Help

- **GitHub Issues:** https://github.com/whoz-oss/coday/issues
- **Discussions:** https://github.com/whoz-oss/coday/discussions

## What's Next?

1. ✅ Service is running
2. 📝 Explore the API
3. 🔌 Create your first plugin
4. 📚 Read the architecture docs
5. 🚀 Deploy to production

---

**You're all set!** 🎉
