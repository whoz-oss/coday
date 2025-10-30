# Filesystem Agents Plugin

## Overview

The Filesystem Agents Plugin loads agents dynamically from YAML files in a directory. This is ideal for:
- Agents that change frequently
- Non-developer agent management
- Configuration-driven agent definitions
- Quick agent updates without rebuild
- Dynamic agent discovery

## Features

- ✅ Load agents from YAML files
- ✅ Hot reload on plugin reload
- ✅ No rebuild required for changes
- ✅ User-friendly YAML format
- ✅ Automatic agent discovery
- ✅ Rich metadata support

## YAML Format

### Complete Example (Howzi Agent)

```yaml
name: Howzi
description: Specialized AI assistant for Angular code analysis
aiProvider: anthropic
modelSize: BIG
modelName: BIG

mandatoryDocs:
  - ./doc/coday/howzi/howzi-description.md
  - ./doc/coday/howzi/howzi-best-practices.md

optionalDocs:
  - path: ./doc/coday/howzi/howzi-code-analysis.md
    description: Code analysis task prompts
  - path: ./doc/coday/howzi/howzi-component-creation.md
    description: Component creation guidance

instructions: |
  You are a frontend Angular expert...
  
  ## Technical Context
  - Angular 19+
  - TypeScript 5.7+
  
  ## Core Responsibilities
  1. Analyze code
  2. Provide recommendations

integrations:
  GITHUB:
  FILES:
  AI:
  JIRA:

capabilities:
  - angular-analysis
  - code-review
  - best-practices

contexts:
  - CODE_REVIEW
  - GENERAL

tags:
  - angular
  - frontend
  - code-quality

priority: 10
version: 1.0.0
status: ACTIVE
```

### Minimal Example

```yaml
name: Simple Agent
description: A simple agent for testing
capabilities:
  - general-assistance
contexts:
  - GENERAL
tags:
  - simple
priority: 5
```

## YAML Fields

### Required Fields

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `name` | String | Agent display name | "Howzi" |
| `description` | String | What the agent does | "Angular code analyzer" |

### Optional Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `aiProvider` | String | null | AI provider (anthropic, openai) |
| `modelSize` | String | null | Model size (BIG, LARGE, MEDIUM) |
| `modelName` | String | null | Specific model name |
| `mandatoryDocs` | List<String> | null | Required documentation paths |
| `optionalDocs` | List<OptionalDoc> | null | Optional docs with descriptions |
| `instructions` | String | null | Agent instructions/prompt |
| `integrations` | Map | null | Available integrations |
| `capabilities` | List<String> | auto-generated | Agent capabilities |
| `contexts` | List<String> | [GENERAL] | Context types |
| `tags` | List<String> | [filesystem-agent] | Tags for filtering |
| `priority` | Integer | 5 | Priority (1-10) |
| `version` | String | "1.0.0" | Agent version |
| `status` | String | "ACTIVE" | Agent status |

### Context Types

Valid values for `contexts`:
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

### Status Values

Valid values for `status`:
- `ACTIVE` - Agent is available
- `INACTIVE` - Agent is disabled
- `MAINTENANCE` - Agent is under maintenance
- `DEPRECATED` - Agent is deprecated

## Configuration

### Default Directory

By default, agents are loaded from: `./agents/`

### Custom Directory

Set via system property:
```bash
java -Dagentos.agents.directory=/path/to/agents
```

Or environment variable:
```bash
export AGENTOS_AGENTS_DIRECTORY=/path/to/agents
```

## Building

```bash
cd agentos/filesystem-plugin
../gradlew jar
```

Output: `build/libs/filesystem-plugin-1.0.0.jar`

## Installation

### Method 1: Upload via API
```bash
curl -X POST http://localhost:8080/api/plugins/upload \
  -F "file=@build/libs/filesystem-plugin-1.0.0.jar"
```

### Method 2: Manual Copy
```bash
cp build/libs/filesystem-plugin-1.0.0.jar ../plugins/
# Restart agentOS or reload plugins
```

## Creating Agent Files

### Step 1: Create YAML File

Create a file in the `agents/` directory:

```bash
cd agentos
mkdir -p agents
nano agents/my-agent.yaml
```

### Step 2: Define Agent

```yaml
name: My Custom Agent
description: Does something useful
capabilities:
  - custom-capability
  - special-feature
contexts:
  - GENERAL
tags:
  - custom
  - experimental
priority: 7
```

### Step 3: Reload Plugin

```bash
curl -X POST http://localhost:8080/api/plugins/filesystem-agents/reload
```

### Step 4: Verify

```bash
curl http://localhost:8080/api/agents/my-agent | jq
```

## Agent ID Generation

The agent ID is automatically generated from the filename:
- `howzi.yaml` → `howzi`
- `security-scanner.yaml` → `security-scanner`
- `My Agent.yaml` → `my-agent`
- `API_Architect.yml` → `api-architect`

**Rules:**
- Lowercase
- Replace spaces and special chars with `-`
- Remove file extension
- Trim leading/trailing `-`

## Example Agents

### 1. Howzi (Angular Expert)

**File**: `agents/howzi.yaml`

See complete example in the YAML Format section above.

**Usage:**
```bash
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "capabilities": ["angular-analysis"],
    "contexts": ["CODE_REVIEW"]
  }' | jq
```

### 2. Security Scanner

**File**: `agents/security-scanner.yaml`

```yaml
name: Security Scanner
description: Security vulnerability analysis
aiProvider: openai
modelSize: LARGE

capabilities:
  - security-scanning
  - vulnerability-detection
  - owasp-analysis

contexts:
  - CODE_REVIEW
  - GENERAL

tags:
  - security
  - vulnerability

priority: 10
```

### 3. API Architect

**File**: `agents/api-architect.yaml`

```yaml
name: API Architect
description: REST and GraphQL API design expert
capabilities:
  - api-design
  - rest-design
  - graphql-design

contexts:
  - GENERAL

tags:
  - api
  - architecture

priority: 8
```

## Updating Agents

### Method 1: Edit and Reload

```bash
# 1. Edit the YAML file
nano agents/howzi.yaml

# 2. Reload the plugin
curl -X POST http://localhost:8080/api/plugins/filesystem-agents/reload

# 3. Verify changes
curl http://localhost:8080/api/agents/howzi | jq
```

### Method 2: Hot Swap

```bash
# Replace file and reload in one command
cp updated-howzi.yaml agents/howzi.yaml && \
curl -X POST http://localhost:8080/api/plugins/filesystem-agents/reload
```

## Advantages

### ✅ No Rebuild Required
- Edit YAML and reload
- Quick iteration
- No compilation needed

### ✅ User-Friendly
- Simple YAML format
- No programming knowledge required
- Easy for non-developers

### ✅ Version Control
- Track changes in Git
- Easy diffs
- Merge friendly

### ✅ Dynamic
- Add/remove agents without restart
- Modify agent properties
- Enable/disable agents

## Disadvantages

### ❌ Runtime Parsing
- YAML parsing at load time
- Potential for syntax errors
- No compile-time validation

### ❌ Limited Logic
- Can't add complex initialization
- No dynamic property computation
- Configuration-only

### ❌ File Management
- Need to manage YAML files
- File naming conventions
- Directory structure

## When to Use

**Use Filesystem Plugin when:**
- Agents change frequently
- Non-developers manage agents
- You want quick updates
- Configuration-driven approach
- Many agents to manage

**Use Code-Based Plugin when:**
- Agents are stable
- Need type safety
- Complex initialization required
- Prefer code over configuration

## Troubleshooting

### Plugin Loads But No Agents

**Check:**
1. Agents directory exists: `ls agents/`
2. YAML files present: `ls agents/*.yaml`
3. YAML syntax is valid: `yamllint agents/howzi.yaml`
4. Plugin logs for errors

**View logs:**
```bash
# Enable debug logging in application.yml
logging:
  level:
    io.biznet.agentos.plugins.filesystem: DEBUG
```

### YAML Parsing Errors

**Common issues:**
- Indentation problems (use 2 spaces)
- Missing quotes for special characters
- Invalid YAML syntax

**Validate YAML:**
```bash
# Use yamllint or online validators
yamllint agents/howzi.yaml
```

### Agent Not Found

**Check:**
- File extension is `.yaml` or `.yml`
- Filename generates correct ID
- Plugin was reloaded after adding file

**Debug:**
```bash
# Check plugin logs
curl http://localhost:8080/api/plugins/filesystem-agents | jq '.agentCount'

# List all agents
curl http://localhost:8080/api/agents | jq '.[] | select(.tags[] | contains("filesystem-agent"))'
```

### Directory Not Found

**Solution:**
```bash
# Create directory
mkdir -p agents

# Or set custom directory
export AGENTOS_AGENTS_DIRECTORY=/custom/path
```

## Best Practices

### 1. File Naming
```bash
# Good
howzi.yaml
security-scanner.yaml
api-architect.yaml

# Avoid
Howzi Agent.yaml  # Spaces
SECURITY.YAML     # All caps
agent_1.yaml      # Generic names
```

### 2. YAML Structure
```yaml
# Use consistent indentation (2 spaces)
name: Agent Name
description: Clear description

# Group related fields
capabilities:
  - capability1
  - capability2

contexts:
  - CONTEXT1
  - CONTEXT2

# Add comments for clarity
tags:
  - tag1  # Main category
  - tag2  # Sub-category
```

### 3. Versioning
```yaml
# Include version in YAML
version: 1.0.0

# Or in filename
howzi-v1.yaml
howzi-v2.yaml
```

### 4. Documentation
```yaml
# Link to documentation
mandatoryDocs:
  - ./doc/agent-guide.md

optionalDocs:
  - path: ./doc/advanced.md
    description: Advanced features
```

## Directory Structure

### Recommended Layout

```
agents/
├── README.md                    # Documentation
├── frontend/                    # Frontend agents
│   ├── howzi.yaml
│   └── ux-reviewer.yaml
├── backend/                     # Backend agents
│   ├── api-architect.yaml
│   └── db-optimizer.yaml
├── security/                    # Security agents
│   ├── security-scanner.yaml
│   └── compliance-checker.yaml
└── general/                     # General agents
    ├── code-reviewer.yaml
    └── doc-writer.yaml
```

### Flat Structure (Simpler)

```
agents/
├── howzi.yaml
├── security-scanner.yaml
├── api-architect.yaml
└── code-reviewer.yaml
```

**Note**: The plugin scans recursively, so both structures work!

## API Examples

```bash
# List filesystem agents
curl http://localhost:8080/api/agents | \
  jq '.[] | select(.tags[] | contains("filesystem-agent"))'

# Get Howzi agent
curl http://localhost:8080/api/agents/howzi | jq

# Query Angular agents
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "tags": ["angular"],
    "contexts": ["CODE_REVIEW"]
  }' | jq

# Query security agents
curl -X POST http://localhost:8080/api/agents/query \
  -H "Content-Type: application/json" \
  -d '{
    "capabilities": ["security-scanning"],
    "minPriority": 9
  }' | jq
```

## Capability Inference

The plugin automatically infers capabilities from:

1. **Explicit capabilities** (if provided)
2. **AI Provider**: `ai-anthropic`, `ai-openai`
3. **Model Size**: `model-big`, `model-large`, `model-medium`
4. **Integrations**: `github`, `files`, `ai`, `jira`

Example:
```yaml
aiProvider: anthropic
modelSize: BIG
integrations:
  GITHUB:
  FILES:

# Generates capabilities:
# - ai-anthropic
# - model-big
# - github
# - files
```

## Future Enhancements

- [ ] JSON support
- [ ] Remote YAML loading (HTTP/S3)
- [ ] YAML schema validation
- [ ] Agent templates
- [ ] Hot file watching
- [ ] Agent inheritance
- [ ] Multi-file agents

---

**Ready to use!** Create your YAML files and reload the plugin.
