# Prompting Strategies

How you communicate with agents significantly impacts the quality of responses. This guide covers effective prompting techniques for working with Coday agents.

## The Foundation: Providing Context Efficiently

Before diving into prompting techniques, it's essential to address a practical bottleneck: **your ability to provide context quickly and accurately**. The quality of agent responses depends heavily on the information you provide, but typing detailed context can be slow and error-prone.

### Input Methods

**Speech-to-Text Integration**
- Coday includes built-in voice input capabilities
- Alternatively, use your OS accessibility features (dictation)
- Significantly faster than typing for explanatory context
- Particularly effective for describing problems, goals, and workflows

**Copy-Paste for Precision**
- Variable names, method names, class names, service names
- File paths and error messages
- Configuration values and API endpoints
- Eliminates typos that could mislead the agent

**Keyboard Proficiency**
- If typing is your primary input method, consider improving typing skills
- Touch typing enables faster, more detailed prompts
- Reduces cognitive load when formulating complex requests

### Critical Data to Provide Accurately

**Identifiers** (always copy-paste):
- `UserAuthenticationService` not "user auth service"
- `processPaymentTransaction()` not "the payment method"
- `src/api/v2/handlers/webhook.ts` not "the webhook file"

**Why This Matters**

Ambiguous or incorrect identifiers force the agent to guess, leading to:
- Solutions for the wrong component
- Hallucinated method names or APIs
- Time wasted on clarification rounds

Invest in efficient input methods early—it pays dividends throughout your work with AI agents.

## Understanding LLM Psychology

Large Language Models are trained to provide confident, immediate answers. This creates specific behavioral patterns that affect how you should interact with them.

### The "Answer Immediately" Pressure

**What's Happening:**
LLMs are optimized to generate responses that *appear* correct on first attempt. This creates an internal pressure to:
- Provide a complete answer immediately
- Fill gaps with plausible-sounding information (hallucination)
- Commit to a solution path early, then rationalize it

**Implications for Prompting:**

**❌ High-Pressure Prompts:**
```
Implement the complete authentication system with all edge cases handled.
It must be production-ready.
```
This amplifies the pressure to appear comprehensive, increasing hallucination risk.

**✅ Exploratory Prompts:**
```
What are the key components we'd need for an authentication system?
Let's discuss the options before implementing.
```
This allows the agent to explore without committing prematurely.

### When to Be Directive vs. Open

**Be Directive When:**
- Requirements are crystal clear
- Following established patterns
- Making simple, well-defined changes
- Time is critical and scope is narrow

```
Add input validation to the email field: must match email regex,
max 255 characters, trim whitespace. Use the validator from utils/validation.ts
```

**Be Open When:**
- Exploring solutions to complex problems
- Architectural decisions with trade-offs
- Uncertain about best approach
- Learning about existing code

```
We need to handle file uploads up to 100MB. What are our options?
Consider trade-offs between memory usage, processing time, and complexity.
```

### Breaking the Hallucination Loop

**The Problem:**
Once an LLM commits to an incorrect path, it tends to defend and elaborate on that path rather than backtrack.

**Prevention Strategy:**

1. **Start with Questions:**
```
"What information do you need to solve this?"
"What are the potential approaches?"
"What are the risks with each approach?"
```

2. **Validate Before Implementation:**
```
"Before implementing, let's verify: does the User model actually have a 'preferences' field?
Check the schema first."
```

3. **Invite Course Correction:**
```
"If any of these assumptions are wrong, let me know before proceeding:
- We're using PostgreSQL
- The users table has a 'metadata' JSON column
- We want to avoid migrations for now"
```

4. **Break Into Stages:**
```
Stage 1: "Analyze the current authentication flow"
Stage 2: "Identify where rate limiting should be added"
Stage 3: "Propose an implementation approach"
Stage 4: "Implement the solution"
```

### Multi-Turn Conversations vs. Mega-Prompts

**Mega-Prompt Approach** (Higher Risk):
```
Implement a complete user management system with authentication,
authorization, profile management, password reset, email verification,
rate limiting, and audit logging. Use best practices.
```

Problems:
- Agent must make many assumptions
- Errors compound across components
- Difficult to course-correct mid-implementation
- High cognitive load on the agent

**Multi-Turn Approach** (Lower Risk):
```
Turn 1: "Let's design a user management system. What are the core components?"
Turn 2: "Good. Let's start with authentication. What's the flow?"
Turn 3: "I prefer JWT. How should we handle refresh tokens?"
Turn 4: "That works. Now implement the login endpoint following that design."
```

Benefits:
- Validate assumptions at each stage
- Catch errors early
- Build shared understanding
- Reduce hallucination risk
- Agent has clear, focused tasks

### Embracing Suggestions and Options

**Fixed Mindset:**
```
"Implement it using Redis for caching."
```

**Growth Mindset:**
```
"I was thinking Redis for caching, but I'm open to alternatives.
What would you recommend considering we already use PostgreSQL?"
```

The agent might reveal:
- PostgreSQL has adequate caching for your scale
- You'd need to manage another service (Redis)
- Simpler solution with query optimization

**Key Principle:**
LLMs have broad knowledge but lack your specific context. Collaboration works best:
- You provide context and constraints
- Agent provides options and trade-offs
- You make informed decisions
- Agent implements with precision

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
