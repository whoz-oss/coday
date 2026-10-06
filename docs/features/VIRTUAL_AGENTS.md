# Virtual Agents from AI Models

## Overview

Virtual agents are automatically generated from all configured AI models, providing users with direct access to models without requiring custom agent configuration. This feature simplifies model selection and allows quick experimentation with different AI providers.

## Implementation

### Architecture

The virtual agent system is implemented in three key components:

1. **AiClientProvider.getAllModels()** (`libs/integration/ai/ai-client-provider.ts`)
   - Returns all available models from all configured AI providers
   - Returns array of `{name: string, providerName: string}`

2. **AgentService.generateVirtualAgentsFromModels()** (`libs/agent/agent.service.ts`)
   - Called during agent initialization
   - Creates minimal `AgentDefinition` for each model
   - Adds virtual agents to the agent definitions list

3. **Agent Routes Initialization** (`apps/server/src/agent.routes.ts`)
   - Ensures `AiClientProvider.init()` is called before agent listing
   - Virtual agents appear in agent autocomplete API

### Virtual Agent Properties

Each virtual agent has:
- **Name**: Exactly the model name (e.g., `gpt-4o`, `claude-sonnet-4.5`)
- **Description**: `"Direct access to {model.name} model ({provider})"`
- **Instructions**: Empty string (no custom instructions)
- **Provider**: Set to the model's provider name
- **Model**: Set to the model name
- **Integrations**: Not specified = access to all tools

### Behavior

Virtual agents:
- ✅ Use project description, mandatory docs, and memories
- ✅ Have access to all available tools (no restrictions)
- ✅ Have no custom personality or instructions
- ✅ Appear in agent autocomplete alongside configured agents
- ✅ Can be selected with `@model-name` syntax

## Usage Examples

### Selecting a Virtual Agent

```bash
# In the chat interface, type:
@gpt-4o What's the capital of France?

# Or use autocomplete:
@gpt  # Shows: gpt-4o, gpt-5.1, etc.
```

### Available Virtual Agents

Virtual agents are created for ALL models defined in your configuration:

From `coday.yaml`:
- `claude-opus-4-20250514` (Anthropic)
- `o4-mini` (OpenAI)
- `o3` (OpenAI)
- `gemini-2.0-flash-exp` (Google - default model)
- `mistral-large-latest` (Mistral)
- `qwen3:8b` (Ollama)

### When to Use Virtual Agents

**Use virtual agents when:**
- You want direct model access without custom instructions
- Testing different models for comparison
- You need a quick answer without agent personality
- Experimenting with a new model

**Use configured agents when:**
- You need specific instructions or personality
- You want restricted tool access
- You need custom documentation or context
- You have a specialized workflow

## Configuration

No additional configuration needed! Virtual agents are automatically generated from your AI provider configuration in `coday.yaml`, project config, or user config.

### Example AI Configuration

```yaml
ai:
  - name: anthropic
    models:
      - name: claude-opus-4-20250514
        alias: BIGGER
        contextWindow: 200000
        
  - name: openai
    models:
      - name: gpt-4o
        contextWindow: 128000
```

This creates two virtual agents:
- `claude-opus-4-20250514`
- `gpt-4o`

## Technical Details

### Initialization Order

1. `Coday.initContext()` calls `aiClientProvider.init(context)`
2. `AgentService.initialize(context)` is called
3. Loads configured agents from:
   - coday.yaml agents section
   - project local config agents
   - agent files in configured folders
4. **Calls `generateVirtualAgentsFromModels()`**
5. Virtual agent definitions added to `agentDefinitions[]`
6. Lazy loading: agents created on first use

### Agent Listing API

The REST endpoint `/api/projects/:projectName/agents` returns both configured and virtual agents:

```json
[
  {
    "name": "Sway",
    "description": "Software Agent - bridges user needs..."
  },
  {
    "name": "gpt-4o",
    "description": "Direct access to gpt-4o model (openai)"
  },
  {
    "name": "claude-opus-4-20250514",
    "description": "Direct access to claude-opus-4-20250514 model (anthropic)"
  }
]
```

### Performance

- Virtual agent definitions are lightweight (no tools or docs loaded)
- Lazy loading: actual Agent instance created only on first use
- No performance impact on initialization
- Debug logging shows: `🤖 Generated N virtual agents from available models`

## Limitations

1. **No aliases**: Virtual agents use model names only, not aliases
2. **All tools**: Cannot restrict tool access for virtual agents
3. **No custom instructions**: Virtual agents have empty instructions
4. **Name conflicts**: If a configured agent has the same name as a model, the configured agent takes precedence

## Future Enhancements

Potential improvements for future versions:

1. **Alias support**: Create virtual agents for model aliases too
2. **Tool restrictions**: Allow configuring default tool sets for virtual agents
3. **Visual distinction**: Add indicator in UI to distinguish virtual vs configured agents
4. **Model metadata**: Show model capabilities (context window, pricing) in autocomplete
5. **Quick switch**: UI dropdown for rapid model switching during conversation

## Related

- Issue #345: AI Provider Selection in UI - Switch models easily during conversation
- `libs/integration/ai/ai-client-provider.ts` - Model listing
- `libs/agent/agent.service.ts` - Virtual agent generation
- `apps/server/src/agent.routes.ts` - API integration
