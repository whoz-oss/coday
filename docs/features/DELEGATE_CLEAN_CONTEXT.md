# Delegate Clean Context Mode

## Overview

The delegate tool can now operate in two modes:
1. **Default mode** (fork with history): The delegated agent sees all previous conversation history
2. **Clean context mode**: The delegated agent starts with a completely empty thread

This feature is controlled by the `CODAY_DELEGATE_CLEAN_CONTEXT` environment variable.

## Motivation

When delegating tasks to specialized agents, sometimes the full conversation history is not needed and can:
- Pollute the context with irrelevant information
- Increase token usage unnecessarily
- Confuse the delegated agent with unrelated context

Clean context mode allows for more focused, isolated task execution.

## Configuration

### Environment Variable

Set the environment variable to enable clean context mode:

```bash
export CODAY_DELEGATE_CLEAN_CONTEXT=true
```

Or inline when starting Coday:

```bash
CODAY_DELEGATE_CLEAN_CONTEXT=true pnpm start
```

### Default Behavior

If the environment variable is not set or set to any value other than `"true"`, the delegate tool will use the default behavior (fork with full history).

## How It Works

### Default Mode (fork with history)

```
Parent Thread: [Msg1, Msg2, Msg3, Msg4]
                      ↓ fork()
Delegated Thread: [Msg1, Msg2, Msg3, Msg4] + new messages
                      ↓ merge()
Parent Thread: [Msg1, Msg2, Msg3, Msg4] + price from delegation
```

### Clean Context Mode

```
Parent Thread: [Msg1, Msg2, Msg3, Msg4]
                      ↓ fork(cleanContext: true)
Delegated Thread: [] + new messages (completely isolated)
                      ↓ merge()
Parent Thread: [Msg1, Msg2, Msg3, Msg4] + price from delegation
```

## Implementation Details

### Modified Components

1. **AiThread.fork()** (`libs/ai-thread/ai-thread.ts`)
   - Added optional `cleanContext` parameter (default: `false`)
   - When `true`, creates thread with empty `messages` array and empty `summary`
   - When `false`, copies all messages and summary (existing behavior)

2. **delegateFunction()** (`libs/integration/ai/delegate.function.ts`)
   - Reads `CODAY_DELEGATE_CLEAN_CONTEXT` environment variable
   - Passes `cleanContextMode` flag to `fork()` method
   - Logs the mode for debugging

3. **DelegateTools** (`libs/integration/ai/delegate.tools.ts`)
   - Detects clean context mode from environment variable
   - Provides different tool descriptions based on mode
   - In clean mode, emphasizes the need for exhaustive, self-contained task descriptions

### Tool Description Changes

**Default Mode:**
```
THREAD ISOLATION: The delegated agent works in an isolated thread with limited context. 
It will only see previous interactions with itself, not recent messages between you and the user.
```

**Clean Context Mode:**
```
⚠️ CLEAN CONTEXT MODE ACTIVE: The delegated agent will start with a COMPLETELY EMPTY thread.
It will NOT see ANY previous messages, context, or history from this conversation.

YOU MUST provide an EXHAUSTIVE and SELF-CONTAINED task description including:
- Complete background and context of the situation
- All relevant information discussed so far in this conversation
- Precise requirements and constraints
- Expected deliverables and definition of done
- Any code, data, file paths, or references needed
- ALL actions the agent should perform
```

## Merge Behavior

The `merge()` method behavior is **identical** in both modes:
- Only the **price** from the delegated thread is transferred to the parent
- Messages from the delegated thread are **not** added to the parent thread
- The result is returned as the tool response and added to the parent thread via `ToolResponseEvent`

This ensures that:
- The parent thread remains clean
- Only the final result is visible in the parent conversation
- Token costs are properly tracked

## Use Cases

### When to Use Default Mode (fork with history)

- The delegated task requires understanding of the full conversation context
- The agent needs to reference previous decisions or discussions
- Continuity with the parent conversation is important

### When to Use Clean Context Mode

- The task is completely independent and self-contained
- The parent conversation history would add noise or confusion
- You want to minimize token usage for the delegation
- The delegated agent is a specialized tool that doesn't need broader context

## Example

### Scenario: Code Review Task

**With Default Mode:**
```
User: "I've been working on authentication. Can you review the login code?"
Agent: [delegates to code-reviewer]
Code Reviewer: [sees full conversation, including auth discussion]
```

**With Clean Context Mode:**
```
User: "I've been working on authentication. Can you review the login code?"
Agent: [delegates with explicit task]
Task: "Review the authentication code in src/auth/login.ts. 
       Context: This is a new OAuth2 implementation for user login.
       Requirements: Check security best practices, error handling, and token management.
       Files: src/auth/login.ts, src/auth/oauth.ts"
Code Reviewer: [starts fresh with only this task description]
```

## Testing

To verify the feature is working:

1. Enable clean context mode:
   ```bash
   export CODAY_DELEGATE_CLEAN_CONTEXT=true
   ```

2. Start Coday with debug mode:
   ```bash
   pnpm start --debug
   ```

3. Trigger a delegation and look for debug messages:
   ```
   Delegate clean context mode: true
   ```

4. The delegated agent should not have access to any previous conversation messages.

## Backward Compatibility

- The feature is **fully backward compatible**
- Default behavior (fork with history) is unchanged
- Existing configurations and workflows continue to work
- The environment variable is optional

## Future Enhancements

Potential improvements to consider:
- Per-agent configuration (some agents always use clean context)
- Dynamic mode selection based on task complexity analysis
- Metrics on context size reduction and token savings
- User-facing configuration option in project settings
