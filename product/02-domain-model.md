# Domain Model

## Core Concepts

### User

The User represents either a human developer or a technical account running Coday. They are identified by a unique
username and:

- Have their own configuration preferences
- Can access one or more projects
- Own personal memories that influence AI interactions
- Control integrations through their API keys and credentials

Key characteristics:

- Persistent preferences and settings
- Personal memory bank
- Integration credentials
- Command history

### Project

A Project defines the operational context for Coday, encompassing:

- Configuration of available AI agents and their capabilities
- Definition of available tools and their parameters
- Command handlers for specific project needs
- Shared knowledge base and memory bank
- Integration settings specific to the project

Key aspects:

- Provides contextual boundaries
- Defines available capabilities
- Maintains project-specific memory
- Configures integration points
- Sets up development environment context

### Thread

A Thread represents an ongoing interaction context between User and AI. It:

- Maintains its own event history
- Manages token limits for AI context
- Tracks file and tool states
- Provides optimized views of its history
- Ensures continuity of conversation

Key characteristics:

- Self-contained interaction space
- State management unit
- History optimization
- Context window management
- Tool state tracking

### Events

Events are atomic units of interaction that flow through the system. Types include:

- User inputs and AI responses
- Tool requests and their results
- System state changes
- Error conditions
- Configuration changes

Key aspects:

- Carry full context
- Thread-scoped
- Immutable
- Sequential
- Traceable

### Tools

Tools are defined capabilities that allow AI to interact with the environment:

- File system operations
- Git commands
- Integration APIs
- System commands
- Memory operations

Characteristics:

- Stateless implementation
- Thread-managed state
- Clear input/output contract
- Error handling patterns
- Documentation requirements

### Integration

An Integration represents a group of related tools and configurations that enable interaction with external systems:

- API configuration (URL, credentials)
- Related tool definitions
- Specific handlers if needed
- Error handling patterns

Key aspects:

- Self-contained functionality
- Consistent configuration pattern
- Clear documentation requirements
- Specific error handling

### Handler Loop

The Handler Loop is the core command processing mechanism:

- Commands are queued in FIFO order
- Each command is matched against handlers sequentially
- First accepting handler processes the command
- Handlers can add new commands to the queue
- Unmatched commands default to AI handling

Characteristics:

- Sequential processing
- Dynamic command generation
- Command transformation
- Fallback mechanism

### Agent

An Agent represents an AI model (LLM) with a specific configuration and purpose:

- Dedicated instructions shaping behavior and expertise
- Assigned set of tools defining capabilities
- Access to specific memories and knowledge
- Defined role and responsibilities

Key aspects:

- Specialized purpose and behavior
- Configured tool access
- Memory access levels
- Response patterns
- Interaction style

### Memory

Memory represents the system's knowledge persistence:

- Project-level shared knowledge
- User-specific preferences and patterns
- Tool operation results

Key aspects:

- Hierarchical (Project/User)
- Contextual relevance
- Temporal validity
- Access patterns
- Update mechanisms

## Domain Relationships

```mermaid
graph TD
    User --> Project["Project(s)"]
    User --> UM["User Memory"]
    Project --> PM["Project Memory"]
    Project --> Tools
    Project --> Agents["AI Agents"]
    Project --> Handlers["Command Handlers"]

    subgraph "Thread Context"
        Thread --> Events
        Events --> Tools
        Tools --> TS["Tool State"]
    end

    User --> Thread
    Project --> Thread
    UM --> Thread
    PM --> Thread
%% Styling
    classDef memory fill: #f9f, stroke: #333, stroke-width: 2px
    class UM, PM, TM memory
    classDef state fill: #bbf, stroke: #333, stroke-width: 2px
    class TS state
```

This graph shows how:

1. Users operate within Projects
2. Projects define available Tools and Agents
3. Threads provide interaction context
4. Memory exists at multiple levels
5. Tools maintain state within Threads
6. Events flow through Thread context

The relationships emphasize:

- Clear ownership hierarchies
- Context boundaries
- State management responsibilities
- Memory access patterns
- Tool execution scopes