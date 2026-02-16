# Team Library

Foundation library for Agent Teams feature in Coday.

## Overview

This library provides the core data structures and logic for coordinating multiple agents working together as a team. It implements:

- **TaskList**: Shared work items with dependency tracking
- **Mailbox**: Inter-agent messaging system
- **TeammateSession**: Persistent agent session with idle/wake loop
- **Team**: Team management and coordination
- **TeamService**: Team lifecycle management

## Architecture

Agent Teams allow a lead agent to coordinate multiple persistent agent sessions (teammates) working together with:
- Peer-to-peer communication via Mailbox
- Shared task list with dependency resolution
- Persistent sessions that stay alive across multiple interactions
- Idle/wake cycle for efficient resource usage

## Usage

```typescript
import { TeamService, Team, TaskList, Mailbox } from '@coday/team'

// Create a team service
const teamService = new TeamService(interactor)

// Create a team
const team = teamService.createTeam('LeadAgentName')

// Create tasks with dependencies
const task1 = team.taskList.createTask('Implement feature X')
const task2 = team.taskList.createTask('Write tests for feature X', [task1.id])
const task3 = team.taskList.createTask('Review implementation', [task1.id, task2.id])

// Spawn teammates
const teammate1 = teamService.spawnTeammate(team, agent1, parentThread, 'Work on task 1')
const teammate2 = teamService.spawnTeammate(team, agent2, parentThread, 'Work on task 2')

// Teammates can communicate
team.mailbox.send('teammate1', 'teammate2', 'I finished task 1, you can start now')

// Cleanup when done
await teamService.cleanupTeam(team.id)
```

## Key Features

### TaskList
- Create tasks with dependencies
- Automatic dependency resolution
- Task claiming with locking
- Track task status (pending → in_progress → completed)

### Mailbox
- Direct messaging between agents
- Broadcast to all team members
- Promise-based waiting for messages
- Wake-up mechanism for idle agents

### TeammateSession
- Persistent agent session
- Idle/wake/stopped state management
- Automatic error recovery
- Graceful shutdown

### TeamService
- Create and manage teams
- Spawn and shutdown teammates
- Track team lifecycle
- Cleanup resources

## Testing

The library includes comprehensive Jest tests:

```bash
nx test team
```

All 50 tests pass, covering:
- Task creation and dependencies
- Task claiming and completion
- Message sending and receiving
- Wait/wake mechanisms
- Team lifecycle management

## Status

✅ Phase 1 Complete: Foundation library implemented
- All core classes implemented
- Comprehensive tests passing
- TypeScript compilation successful
- Ready for integration with tools and services
