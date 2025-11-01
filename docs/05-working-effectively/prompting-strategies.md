# Prompting Strategies

How you communicate with agents significantly impacts the quality of responses. This guide covers effective prompting techniques for working with Coday agents.

## Core Principles

### 1. Be Specific

**❌ Vague:**
```
Make the code better
```

**✅ Specific:**
```
Refactor the authentication module to use async/await instead of callbacks,
and add error handling for network timeouts
```

### 2. Provide Context

**❌ Minimal context:**
```
Add a new endpoint
```

**✅ Rich context:**
```
Add a new POST endpoint /api/users that accepts email and password,
validates input, hashes the password with bcrypt, stores in PostgreSQL,
and returns a JWT token. Follow the existing pattern in /api/auth/login
```

### 3. State Your Goal

**❌ Action-focused:**
```
Write a function that processes data
```

**✅ Goal-focused:**
```
I need to transform user input from the form into a format our API expects.
The API requires ISO date strings, but users enter dates as MM/DD/YYYY.
```

## Prompting Patterns

### Exploratory Questions

When you need to understand something:

```
Can you explain how the authentication flow works in this project?
Start with the login endpoint and trace through to session creation.
```

**Effective because:**
- Clear scope (authentication flow)
- Specific starting point (login endpoint)
- Defined end point (session creation)

### Implementation Requests

When you need code written:

```
Implement a rate limiting middleware for our Express API.

Requirements:
- 100 requests per IP per minute
- Return 429 status when exceeded
- Use Redis for storage (we already have a client configured)
- Follow the pattern in src/middleware/auth.ts

Place in src/middleware/rateLimit.ts
```

**Effective because:**
- Clear requirements
- Specific constraints (Redis, existing patterns)
- Defined location

### Code Review Requests

When you need feedback:

```
Review the implementation in src/services/payment.ts

Focus on:
- Error handling (especially network errors)
- Security (are we validating inputs properly?)
- Edge cases (what if amount is negative?)

We're planning to deploy this to production next week.
```

**Effective because:**
- Specific file
- Focused areas of concern
- Context about urgency/importance

### Debugging Requests

When something's wrong:

```
The login endpoint is returning 500 errors intermittently.

Error message: "Connection pool exhausted"
Frequency: About 10% of requests during peak hours
Recent changes: We added user profile images yesterday

Can you help identify the cause?
```

**Effective because:**
- Specific problem
- Relevant error details
- Context (frequency, recent changes)
- Clear ask

## Advanced Techniques

### Iterative Refinement

Start broad, then narrow:

```
1st: "Explain our caching strategy"
2nd: "How does cache invalidation work for user data specifically?"
3rd: "What happens if a cache invalidation fails?"
```

### Constraint-Based Prompting

Define what you DON'T want:

```
Refactor this function to improve readability.

Constraints:
- Don't change the public API
- Don't add external dependencies
- Keep the same performance characteristics
- Maintain backward compatibility
```

### Example-Driven Prompting

Show what you want:

```
Add error handling similar to how we handle it in src/api/users.ts:

try {
  const result = await operation()
  return { success: true, data: result }
} catch (error) {
  logger.error('Operation failed', { error, context })
  return { success: false, error: error.message }
}

Apply this pattern to src/api/products.ts
```

### Comparative Prompting

Ask for comparisons:

```
We're deciding between using WebSockets vs Server-Sent Events for real-time updates.

Compare them for our use case:
- One-way server-to-client updates
- Need to support 1000+ concurrent connections
- Messages are small (< 1KB)
- Browser support is important

What do you recommend and why?
```

## Common Pitfalls

### Too Vague

**Problem:**
```
Fix the bug
```

**Solution:**
```
The user profile page shows a 404 error when accessed directly via URL,
but works when navigated to from the dashboard. This started after we
implemented client-side routing. Can you identify and fix the issue?
```

### Too Many Tasks

**Problem:**
```
Implement authentication, add rate limiting, set up logging,
create admin dashboard, and deploy to production
```

**Solution:**
Break into separate conversations or explicitly prioritize:
```
Let's implement authentication first. We'll tackle rate limiting
and logging in follow-up conversations. For now, focus on:
1. User registration
2. Login with JWT tokens
3. Password reset flow
```

### Assuming Context

**Problem:**
```
Use the same approach as before
```

**Solution:**
```
Use the same approach as we did for the user authentication
(JWT tokens with refresh tokens stored in Redis, implemented
in src/auth/jwt.ts)
```

### Missing Success Criteria

**Problem:**
```
Make it faster
```

**Solution:**
```
Optimize the search query to return results in under 200ms
for datasets up to 10,000 records. Currently it takes 2-3 seconds.
```

## Adapting to Agent Responses

### When the Agent Misunderstands

**Don't:**
```
No, that's wrong
```

**Do:**
```
I see the confusion. Let me clarify: I need to sort by date DESC,
not ASC. The most recent items should appear first.
```

### When You Need More Detail

**Don't:**
```
Explain more
```

**Do:**
```
Can you explain the error handling part in more detail?
Specifically, how should we handle network timeouts vs validation errors?
```

### When You Want Alternatives

**Don't:**
```
Give me another option
```

**Do:**
```
That approach makes sense, but it requires adding a new dependency.
What's an alternative that uses only our existing libraries?
```

## Tips for Different Task Types

### Architectural Discussions

- Start with goals and constraints
- Ask for trade-offs explicitly
- Request multiple approaches
- Discuss implications of each choice

### Code Implementation

- Provide existing patterns to follow
- Specify error handling requirements
- Mention performance considerations
- Define where code should live

### Debugging

- Share error messages verbatim
- Describe reproduction steps
- Mention recent changes
- Include relevant context (load, environment, etc.)

### Code Review

- Be specific about concerns
- Ask for particular perspectives (security, performance, maintainability)
- Provide context about the change's purpose
- Mention any constraints or requirements

## Next Steps

- [Context and Memory](./context-and-memory.md): Manage information across conversations
- [Detecting Hallucinations](./detecting-hallucinations.md): Recognize when agents are uncertain
- [Iterative Workflows](./iterative-workflows.md): Build solutions incrementally
