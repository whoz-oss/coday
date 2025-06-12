# Unified Cleanup Architecture for Coday

## Overview

This document describes the comprehensive cleanup architecture implemented to handle all termination scenarios in Coday, ensuring proper resource management across terminal and web modes.

## Problem Statement

Previously, Coday had inconsistent cleanup behavior:
- Multiple cleanup methods with unclear responsibilities
- Terminal mode didn't cleanup Coday resources on exit
- Web server had inconsistent cleanup logic
- MCP resources (Docker containers) were only cleaned in some scenarios

## Architecture Solution

### Clear Method Responsibilities

#### `stop()` - Graceful Operation Stop
**Purpose**: Stop current AI processing while preserving state
**Scope**: Current operation only
**Use cases**: User interruption, conversation pause
**Behavior**:
- Preserves thread and context state
- Allows clean completion of current operation
- Prevents new processing steps
- Keeps Coday instance alive for potential resume
- Auto-saves thread state

#### `cleanup()` - Conversation End Cleanup
**Purpose**: Clean up resources at the end of a conversation
**Scope**: Conversation-level resources
**Use cases**: Normal conversation end (exit command, oneshot completion)
**Behavior**:
- Stops MCP servers and Docker containers
- Cleans AI client connections
- Preserves thread state and Coday instance
- Keeps instance ready for new conversations
- Clears context but maintains services

#### `kill()` - Force Terminate Everything
**Purpose**: Force terminate and destroy the instance
**Scope**: Entire Coday instance
**Use cases**: Forced termination (Ctrl+C, process exit, application shutdown)
**Behavior**:
- Stops all processing immediately
- Calls cleanup() for resource management
- Destroys the Coday instance completely
- Cleans up all resources and configurations

## Implementation Details

### Terminal Mode Cleanup

#### Normal Process Exit
```typescript
process.on('exit', () => {
  interactor.cleanup() // Only cleanup readline interface
})
```

#### Forced Termination (Ctrl+C, SIGTERM)
```typescript
const handleForcedTermination = async (signal: string) => {
  console.log(`\nReceived ${signal}, cleaning up...`)
  try {
    await coday.kill()      // Full Coday cleanup
    interactor.cleanup()    // Terminal cleanup
  } catch (error) {
    console.error('Error during cleanup:', error)
  } finally {
    process.exit(0)
  }
}

process.on('SIGINT', () => handleForcedTermination('SIGINT'))
process.on('SIGTERM', () => handleForcedTermination('SIGTERM'))
```

### Web Mode Cleanup

#### Client Disconnection
```typescript
terminate(immediate: boolean = false): void {
  if (immediate) {
    this.cleanup() // Full cleanup
    return
  }
  
  // Conversation cleanup but keep Coday alive for reconnection
  if (this.coday) {
    this.coday.cleanup() // Clean conversation resources
  }
  
  // Schedule full cleanup after timeout
  setTimeout(() => {
    if (client.isExpired()) {
      this.cleanup() // Full cleanup after timeout
    }
  }, SESSION_TIMEOUT)
}
```

#### Session Expiration
```typescript
private cleanup(): void {
  this.subscription?.unsubscribe()
  
  if (this.coday) {
    this.coday.kill() // Full Coday destruction
    delete this.coday
  }
  
  // Clear timeouts and resources
}
```

### Coday Core Cleanup Chain

#### Normal Conversation End
```typescript
async run(): Promise<void> {
  try {
    // Main conversation loop
    do {
      // ... conversation logic ...
    } while (!this.context?.oneshot)
  } finally {
    // Always cleanup resources when conversation ends normally
    await this.cleanup()
  }
}
```

#### Resource Cleanup Implementation
```typescript
async cleanup(): Promise<void> {
  try {
    // Stop MCP servers (including Docker containers)
    if (this.services.agent) {
      await this.services.agent.kill()
    }
    
    // Reset AI client provider for fresh connections
    this.aiClientProvider.cleanup()
    
    // Clear context but keep services available
    this.context = null
    this.handlerLooper = undefined
    this.aiHandler = undefined
    
  } catch (error) {
    console.error('Error during MCP cleanup:', error)
    // Don't throw - cleanup should be best-effort
  }
}
```

#### Force Termination
```typescript
async kill(): Promise<void> {
  this.killed = true
  this.stop()
  
  try {
    await this.cleanup() // Clean resources first
  } catch (error) {
    console.error('Error during kill cleanup:', error)
  }
  
  this.handlerLooper?.kill()
  this.interactor.kill()
}
```

## MCP Resource Management

### Cleanup Chain for MCP Docker Containers
```
Coday.cleanup()
→ AgentService.kill()
→ Toolbox.kill()
→ McpToolsFactory.kill()
→ Client.close() + Process.kill()
→ Docker containers stopped
```

### Scenarios Covered
1. **Normal exit command**: `cleanup()` → MCP containers stopped
2. **Oneshot completion**: `cleanup()` → MCP containers stopped
3. **Web client disconnection**: `cleanup()` → MCP containers stopped
4. **Session expiration**: `kill()` → Full cleanup including MCP
5. **Ctrl+C termination**: `kill()` → Full cleanup including MCP
6. **Process termination**: `kill()` → Full cleanup including MCP

## Benefits Achieved

### 1. Consistent Resource Management
- MCP Docker containers are **always** stopped when they should be
- No resource leaks regardless of termination method
- Predictable cleanup behavior across all scenarios

### 2. Clear Separation of Concerns
- `stop()`: Operation-level control
- `cleanup()`: Conversation-level resource management
- `kill()`: Instance-level destruction

### 3. Robust Error Handling
- Best-effort cleanup that doesn't break termination
- Graceful degradation when cleanup fails
- Comprehensive error logging

### 4. Mode-Agnostic Design
- Same cleanup logic works for terminal and web modes
- Consistent behavior regardless of interface
- Unified architecture across all entry points

## Testing Scenarios

### Terminal Mode
1. **Normal exit**: Type `exit` → Conversation cleanup → MCP stopped
2. **Oneshot completion**: Command finishes → Conversation cleanup → MCP stopped
3. **Ctrl+C**: Force termination → Full cleanup → MCP stopped
4. **Process kill**: SIGTERM → Full cleanup → MCP stopped

### Web Mode
1. **Client disconnect**: Browser closes → Conversation cleanup → MCP stopped
2. **Session timeout**: 8h idle → Full cleanup → MCP stopped
3. **Server shutdown**: Process termination → Full cleanup → MCP stopped
4. **Client reconnect**: Previous session → Resume without resource leak

### Error Scenarios
1. **MCP cleanup fails**: Error logged, termination continues
2. **Network issues**: Cleanup attempted, graceful fallback
3. **Resource conflicts**: Best-effort cleanup, no hanging

## Configuration Examples

### MCP Docker Server (from coday.yaml)
```yaml
mcp:
  servers:
    - id: github
      name: GIT-PLATFORM
      enabled: true
      command: docker
      args:
        - run
        - -i
        - --rm
        - -e
        - GITHUB_PERSONAL_ACCESS_TOKEN
        - ghcr.io/github/github-mcp-server
```

This Docker-based MCP server will now be properly stopped in **all** termination scenarios.

## Files Modified

### Core Architecture
- `libs/coday.ts`: Unified cleanup methods and conversation-end cleanup
- `libs/integration/ai/ai-client-provider.ts`: Added cleanup() method

### Terminal Mode
- `apps/terminal/terminal.ts`: Comprehensive signal handling and cleanup

### Web Mode
- `apps/web/server/server-client.ts`: Unified cleanup architecture integration

## Backward Compatibility

- ✅ No breaking changes to existing APIs
- ✅ All existing functionality preserved
- ✅ Enhanced cleanup is additive only
- ✅ Existing termination behaviors improved, not changed

This unified architecture ensures that Coday properly manages all resources across all termination scenarios, providing a robust and predictable cleanup experience.