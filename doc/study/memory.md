# MEMORY

## Types

### Project & Agent memory

Memory per project and agent, capped at 1000 to keep reasonable to read all titles

### Project & User memory

Memories related to the current user. Paramount to not mix user info !!!

```typescript
interface SharedMemory {
  type: 'PROJECT'
  title: string
  content: string
  // ... other fields
}

interface UserMemory {
  type: 'USER'
  userId: string  // Required for strict isolation
  title: string
  content: string
  // ... other fields
}
```

## Usage tracking

Through a dedicated collection, with something alike:

̏```typescript
interface MemoryUsage {
  timestamp: Date
  context: string          // user prompt or internal prompt
  threadId: string         // to track usage within conversations
  memoryIds: {
    id: string
    relevanceScore?: number  // if we implement ranking
    category: 'necessary' | 'supporting'  // if we implement categorization
  }[]
}
̏```

## Smart Memory Selection

1. Initial Selection (Fast & Cheap LLM)
   Input:

       - User prompt
       - Live memory titles
       - Thread context summary (?)
       - Previous selected memory IDs (?)

   Output:

    - Selected memory IDs with:
        * Relevance score (0-1)
        * Category (necessary/supporting)
          Memory Retrieval & Usage Recording

2. Memory Retrieval & Usage Recording

    - Fetch full content of selected memories
    - Record usage (bulk operation)
    - Update memory counters

3. Final Prompt Construction

    - User prompt
    - Selected memories (ordered by relevance)
    - Thread context

## Memory management in AiThread

Dynamic accumulation:

- Keep track of used memories in thread metadata
- For each prompt, consider:
    * Already used memories (lower priority)
    * Fresh matches (higher priority)
- Use relevance scores to prevent overflow

Proposed two-pass approach:

Pass 1: Selection (fast model)

- Input: prompt + titles + minimal context
- Output: relevant memory IDs

Pass 2: Ranking (could use same model)

- Input: selected memories + prompt + thread summary
- Output: ranked list with scores

Benefits:

- Separates concerns
- More precise ranking
- Can optimize each pass independently