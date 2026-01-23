# Repository Layer - SQLite Integration

## Overview

This directory contains the SQLite-based persistence layer for Coday, starting with threads and messages.

## Architecture

### DatabaseProvider (Singleton Pattern)

Centralized connection management:
- **One database per project**: `~/.coday/projects/{projectName}/coday.db`
- **Connection pooling**: Reuses connections per project path
- **WAL mode**: Enabled for better concurrent read/write
- **Busy timeout**: 5 seconds for handling concurrent access

```typescript
// Get a database connection
const db = await DatabaseProvider.getDatabase(projectPath)

// Close specific connection
await DatabaseProvider.closeDatabase(projectPath)

// Close all connections (graceful shutdown)
await DatabaseProvider.closeAll()
```

### ThreadRepository

Handles threads and messages persistence with support for:
- **Structured data**: id, name, dates, etc.
- **Unstructured data**: JSON in `data` column for cache markers, tool-specific data, etc.

#### Schema

```sql
CREATE TABLE threads (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  agent_name TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  summary TEXT,
  data TEXT DEFAULT '{}'  -- Unstructured JSON
);

CREATE TABLE messages (
  id TEXT PRIMARY KEY,
  thread_id TEXT NOT NULL,
  timestamp TEXT NOT NULL,
  type TEXT NOT NULL,
  role TEXT,
  content TEXT NOT NULL,  -- JSON string
  FOREIGN KEY(thread_id) REFERENCES threads(id) ON DELETE CASCADE
);
```

#### Usage

```typescript
// Initialize
const repo = new ThreadRepository(projectPath)
await repo.initialize()

// Save thread with unstructured data
await repo.saveThread({
  id: 'thread-1',
  name: 'My conversation',
  agentName: 'sway',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
  data: {
    anthropic: {
      cacheMarkerMessageId: 'msg-abc'
    }
  }
})

// Add message
await repo.addMessage({
  id: 'msg-1',
  threadId: 'thread-1',
  timestamp: new Date().toISOString(),
  type: 'MessageEvent',
  role: 'user',
  content: { text: 'Hello!' }
})

// Search messages
const results = await repo.searchMessages('hello')

// Cleanup old messages
const deleted = await repo.cleanupOldMessages('2025-01-01')

// Close
await repo.close()
```

## Unstructured Data Strategy

The `data` column in `threads` table stores JSON for:
- **Cache markers** (Anthropic, OpenAI-specific)
- **Tool-specific metadata**
- **UI state** (scroll position, etc.)
- **Any provider-specific data**

This avoids creating new columns for every new feature while maintaining queryability for structured data.

### Example

```typescript
{
  anthropic: {
    cacheMarkerMessageId: 'msg-123',
    lastCacheUpdate: '2025-01-23T10:00:00Z'
  },
  openai: {
    assistantId: 'asst_abc123'
  },
  ui: {
    scrollPosition: 1234
  }
}
```

## Testing

Run the test script:
```bash
pnpm tsx libs/repository/thread-repository.test.ts
```

This validates:
- ✅ Database initialization
- ✅ Thread creation with unstructured data
- ✅ Message insertion
- ✅ Search performance
- ✅ Cleanup operations
- ✅ Connection management

## Performance Characteristics

### SQLite WAL Mode

- **Concurrent reads**: Unlimited
- **Concurrent writes**: 1 at a time (queued with 5s timeout)
- **Typical write latency**: <10ms
- **Search latency**: <50ms for thousands of messages

### Suitable For

- ✅ Local development
- ✅ Single-server deployments (up to ~20 concurrent users)
- ✅ File-based backup/sync
- ❌ Distributed deployments (use PostgreSQL instead)

## Migration Strategy

This is a **POC** - no migration from YAML yet. Once validated:
1. Create migration script from YAML to SQLite
2. Add feature flag (`--use-database`)
3. Run both systems in parallel
4. Switch to SQLite by default

## Next Steps

After validating threads/messages:
- [ ] Add more repositories (users, groups, projects)
- [ ] Implement full migration from YAML
- [ ] Add feature flag for gradual rollout
- [ ] Performance testing with real data volumes
- [ ] Consider PostgreSQL for multi-server deployments
