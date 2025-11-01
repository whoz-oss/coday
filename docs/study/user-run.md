# User Run: Core Interaction Unit

## Context

The User Run concept emerged as a fundamental unit of interaction in Coday, providing clear boundaries for message
organization, state management, and thread operations. It represents a complete cycle from user input to final
resolution, including all intermediate steps and agent interactions.

## Core Concept

A User Run is triggered by either:

- Direct user input through the prompt loop
- System-triggered events (like async tool completions)

It encompasses the entire cycle of:

1. Initial request processing
2. Command queue execution
3. Agent interactions
4. Tool operations
5. Final resolution

## Architecture Benefits

### Thread Structure

- Natural segmentation of conversation history
- Clear units for summarization
- Logical points for thread operations (fork, edit, rerun)
- Clean boundaries for state management

### State Management

- Contained orchestration context per run
- Clear token accounting boundaries
- Defined scope for resource tracking
- Natural error recovery points

### Operation Support

- Thread forking from any run
- Edit and re-run capabilities
- Selective run summarization
- Run-level metadata and tagging

## Async Tool Integration

Async tool operations that complete after their initial run are handled through system-triggered User Runs:

1. Initial Run:
    - User request initiates tool operation
    - Tool returns "in progress" status
    - Run completes normally

2. Completion Run:
    - Async process completes
    - New User Run triggered by system
    - Marked as system-triggered for clarity
    - Processes completion normally

Benefits:

- Maintains run simplicity
- No complex status tracking
- Clean handling of long operations
- Natural flow in thread history

## Implementation Guidelines

### Run Lifecycle

1. **Initialization**
    - Capture triggering input
    - Set up clean state
    - Initialize orchestration context

2. **Execution**
    - Process command queue
    - Handle agent interactions
    - Track resource usage

3. **Completion**
    - Ensure all immediate operations resolved
    - Finalize state
    - Update thread context

### State Tracking

- Command queue status
- Resource usage (tokens, API calls)
- Tool operations
- Orchestration decisions

### Metadata

- Run type (user/system-triggered)
- Timestamp information
- Resource usage summary
- Key decisions/actions taken

## Thread Integration

### Basic Operations

- Append new runs
- Summarize old runs
- Fork from specific run
- Edit and rerun

### Advanced Features

- Run tagging/labeling
- Run importance scoring
- Selective summarization
- Run grouping

## Interaction Patterns

### Normal Flow

1. User input creates new run
2. Run processes to completion
3. Returns to prompt loop

### Async Pattern

1. Initial run with async tool
2. Tool completion triggers new run
3. Completion run processes
4. Returns to prompt loop

### Error Recovery

1. Run encounters error
2. State contained to current run
3. Clean recovery possible
4. User can edit/retry run

## Future Considerations

### Enhanced Operations

- Run templates for common patterns
- Run export/import
- Run analytics
- Performance optimization

### Thread Management

- Run-based thread pruning
- Intelligent summarization
- Run importance scoring
- Cross-run analysis

### User Experience

- Clear run boundaries in UI
- Run navigation/jumping
- Run search/filtering
- Run comparison

## Next Steps

1. **Core Implementation**
    - Define UserRun class structure
    - Implement basic lifecycle
    - Integrate with AiThread

2. **Enhanced Features**
    - Implement fork/edit capabilities
    - Add run metadata support
    - Develop summarization strategy

3. **Tooling Support**
    - Add run-aware tooling
    - Implement async completion
    - Develop run analytics

4. **UI Enhancement**
    - Show run boundaries
    - Indicate system-triggered runs
    - Support run navigation

The User Run concept provides a robust foundation for structuring interactions in Coday, offering clear boundaries for
operation while maintaining flexibility for advanced features and future enhancements.