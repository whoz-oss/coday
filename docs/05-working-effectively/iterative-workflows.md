# Iterative Workflows

The most effective way to work with AI agents is through iteration—building solutions incrementally with feedback loops. This guide explains how to structure your work for success.

## The Iterative Mindset

### Why Iterate?

**Traditional approach (rarely works):**
```
You: Implement complete authentication system with JWT, refresh tokens,
     password reset, email verification, and rate limiting
Agent: [Produces large implementation]
You: [Finds multiple issues, unclear where to start fixing]
```

**Iterative approach (much more effective):**
```
You: Let's start with basic JWT authentication. Just login and token generation.
Agent: [Implements basic version]
You: Good. Now add token validation.
Agent: [Adds validation]
You: Perfect. Now let's add refresh tokens.
...
```

### Benefits of Iteration

1. **Early detection**: Catch issues before they compound
2. **Course correction**: Adjust direction based on results
3. **Reduced waste**: Don't invest heavily in wrong approaches
4. **Better understanding**: Learn as you build
5. **Manageable complexity**: Handle one thing at a time

## Iterative Patterns

### 1. Understand → Plan → Implement → Verify

**Understand:**
```
You: Explain how our current authentication works
Agent: [Analyzes code and explains]
You: Got it. What would we need to change to add OAuth support?
Agent: [Outlines required changes]
```

**Plan:**
```
You: Let's break this into steps. What should we tackle first?
Agent: [Proposes sequence]
You: Makes sense. Let's start with step 1.
```

**Implement:**
```
Agent: [Implements step 1]
You: [Reviews implementation]
```

**Verify:**
```
You: This looks good, but can you add error handling for network failures?
Agent: [Adds error handling]
You: Perfect. Now let's move to step 2.
```

### 2. Spike → Refine → Polish

**Spike (quick and dirty):**
```
You: Create a quick proof-of-concept for the caching layer.
     Don't worry about error handling or edge cases yet.
Agent: [Creates basic implementation]
```

**Refine (make it work properly):**
```
You: Good. Now let's add proper error handling and cache invalidation.
Agent: [Refines implementation]
```

**Polish (make it production-ready):**
```
You: Add logging, metrics, and tests.
Agent: [Adds production concerns]
```

### 3. Explore → Decide → Execute

**Explore options:**
```
You: What are our options for implementing real-time notifications?
Agent: [Presents options: WebSockets, SSE, polling, etc.]
You: Compare WebSockets vs SSE for our use case
Agent: [Detailed comparison]
```

**Decide:**
```
You: Let's go with SSE. It's simpler and fits our one-way communication need.
```

**Execute:**
```
You: Implement SSE for notifications, following the pattern in our existing API
Agent: [Implements]
```

## Keeping Options Open

### Don't Commit Too Early

**❌ Premature commitment:**
```
You: Implement the entire feature using approach X
[Later discover approach X doesn't work for edge case Y]
[Now stuck with significant rework]
```

**✅ Keeping options open:**
```
You: Let's prototype approach X for the core functionality
Agent: [Creates prototype]
You: This works for the main case. Let's test it with edge case Y
Agent: [Tests]
You: Hmm, issues with edge case. Can we adjust or should we try approach Z?
```

### Decision Points

Build in explicit decision points:

```
You: Let's implement the happy path first, then evaluate if this approach
     can handle error cases before committing fully.

Agent: [Implements happy path]

You: Good. Now let's test with:
     - Network timeout
     - Invalid response
     - Rate limiting
     
     If any of these are problematic, we'll reconsider the approach.
```

### Reversible vs. Irreversible Decisions

**Reversible decisions** (iterate freely):
- Internal implementation details
- Function signatures (within a module)
- Data structures (not yet persisted)
- Algorithm choices

**Irreversible decisions** (iterate carefully):
- Public APIs
- Database schema (in production)
- File formats (with existing data)
- Protocol choices (with external integrations)

For irreversible decisions, iterate more carefully:
```
You: Before we finalize this API, let's think through:
     - Backward compatibility
     - Future extensibility
     - Edge cases
     
     We can't easily change this once released.
```

## Managing Iteration Cycles

### Short Cycles (Minutes)

For well-understood tasks:
```
You: Add input validation to the login endpoint
Agent: [Adds validation]
You: Also validate email format
Agent: [Adds email validation]
You: Perfect, done.
```

### Medium Cycles (Hours)

For moderate complexity:
```
Session 1: Understand current architecture
Session 2: Design new feature
Session 3: Implement core functionality
Session 4: Add error handling
Session 5: Add tests
Session 6: Refactor and polish
```

### Long Cycles (Days/Weeks)

For major features:
```
Week 1: Research and design
Week 2: Implement MVP
Week 3: Iterate based on feedback
Week 4: Production hardening
```

## Feedback Loops

### Internal Feedback (You → Agent)

```
You: The error handling is good, but can we make error messages more specific?
Agent: [Improves messages]
You: Better. Now add examples of valid input to each error.
Agent: [Adds examples]
```

### Code Feedback (Code → Agent)

```
Agent: [Implements feature]
You: Let's run the tests
Agent: [Tool: run_command] npm test
        [Tests fail]
You: Fix the failing tests
Agent: [Analyzes failures and fixes]
```

### External Feedback (Team/Users → Agent)

```
You: The team reviewed the PR and suggested using the repository pattern
     instead of direct database calls. Can you refactor?
Agent: [Refactors to use repository pattern]
```

## Course Correction

### When to Pivot

Signs you should change direction:
- Approach isn't working for key requirements
- Complexity is growing faster than value
- Better alternative becomes apparent
- Fundamental assumption was wrong

**How to pivot:**
```
You: I think we're going down the wrong path. Let's step back.

     The original goal was [X]. We've tried [approach Y] but it's
     getting complicated because [reason].
     
     Let's try [alternative approach Z] instead.
```

### When to Persist

Don't give up too easily:
- Minor obstacles can often be overcome
- Learning curve is normal
- Refactoring can simplify complexity
- Initial messiness is okay

**How to persist:**
```
You: This is getting messy, but I think we're on the right track.
     Let's finish the implementation, then refactor for clarity.
```

## Anti-Patterns

### ❌ The Big Bang

```
You: Implement the entire system in one go
Agent: [Produces massive implementation]
You: [Overwhelmed, unclear where issues are]
```

**Solution**: Break into smaller pieces.

### ❌ No Verification

```
You: Implement A
Agent: [Implements A]
You: Now implement B
Agent: [Implements B]
You: Now implement C
Agent: [Implements C]
[Never verified A or B work]
```

**Solution**: Verify each step before proceeding.

### ❌ Premature Optimization

```
You: Implement this feature and make it super fast and handle all edge cases
Agent: [Overengineers solution]
```

**Solution**: Make it work first, optimize later.

### ❌ Analysis Paralysis

```
You: Let's explore all possible approaches before deciding
Agent: [Presents 10 options]
You: Let's analyze the trade-offs of each in detail
[Hours later, still deciding]
```

**Solution**: Explore enough to make informed decision, then commit and iterate.

## Best Practices

### 1. Start Simple

```
You: Let's implement the simplest version that could work
Agent: [Simple implementation]
You: Good. Now let's add [one complexity at a time]
```

### 2. Verify Continuously

```
You: [After each significant change]
     Can you verify this works by [running tests/checking output/etc.]?
```

### 3. Document Decisions

```
You: Let's document why we chose this approach
Agent: [Adds comments explaining decision]
```

### 4. Celebrate Progress

```
You: Great! That works. Let's save this progress before moving on.
[Commit or note checkpoint]
```

### 5. Know When to Stop

```
You: This is good enough for now. We can optimize later if needed.
```

## Tips

1. **One thing at a time**: Focus on single concerns
2. **Test early, test often**: Verify before building on top
3. **Embrace messiness**: First drafts are rarely perfect
4. **Refactor as you go**: Clean up after each major addition
5. **Document as you learn**: Capture decisions and rationale
6. **Trust the process**: Iteration feels slower but is actually faster

## Next Steps

- [Agent Design](./agent-design.md): Design agents for iterative workflows
- [Prompting Strategies](./prompting-strategies.md): Prompts that support iteration
- [Conversation Management](../03-using-coday/conversation-management.md): Manage iterative conversations
