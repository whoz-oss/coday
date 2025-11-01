# Detecting Hallucinations

AI agents can sometimes provide confident-sounding but incorrect information—a phenomenon called "hallucination." This guide helps you recognize and handle hallucinations when working with Coday.

## What Are Hallucinations?

Hallucinations occur when an agent:
- Invents facts that don't exist
- Misremembers or confuses information
- Makes confident statements about uncertain things
- Provides plausible but incorrect details

**Important**: Hallucinations aren't lies—the agent genuinely doesn't know it's wrong. The model generates plausible-sounding text based on patterns, not verified facts.

## Common Types of Hallucinations

### 1. Invented Code References

**Hallucination:**
```
The authenticate() function in src/auth/validator.ts handles this
```

**Reality:**
- File doesn't exist
- Function doesn't exist
- Function exists but does something different

**Detection**: Verify file paths and function names mentioned by the agent.

### 2. Fabricated API Details

**Hallucination:**
```
The library provides a validateEmail() method that checks format and MX records
```

**Reality:**
- Method doesn't exist
- Method exists but doesn't have those features
- Entirely different API

**Detection**: Check actual library documentation.

### 3. Misremembered Decisions

**Hallucination:**
```
As we decided earlier, we're using MongoDB for this project
```

**Reality:**
- You decided on PostgreSQL
- No database decision was made yet
- Different project context

**Detection**: Review conversation history or project documentation.

### 4. Confident Uncertainty

**Hallucination:**
```
This will definitely fix the performance issue. The query will be 10x faster.
```

**Reality:**
- No way to be certain without testing
- Optimization might not work
- Different bottleneck exists

**Detection**: Be skeptical of absolute claims about performance, behavior, or outcomes.

## Warning Signs

### Red Flags

🚩 **Overly specific details** without verification:
```
This will reduce latency by exactly 47%
```

🚩 **Confident claims about external systems**:
```
The API rate limit is 1000 requests per hour
```
(Unless the agent just read the documentation)

🚩 **References to non-existent code**:
```
The existing helper function in utils/format.ts does exactly this
```
(When you don't recognize it)

🚩 **Absolute statements**:
```
This is the only way to solve this problem
This will never fail
This always works
```

🚩 **Contradicting earlier statements**:
```
Earlier: "We use JWT tokens"
Later: "Since we're using session cookies..."
```

### Green Flags (Likely Accurate)

✅ **Uncertainty expressions**:
```
This might work, but we should test it
I'm not certain, but based on the code I see...
One approach could be...
```

✅ **Tool-verified information**:
```
[Tool: read_file] src/auth/login.ts
Based on the code I just read, the login function...
```

✅ **Qualified statements**:
```
In most cases, this pattern works well
This should improve performance, though we'd need to benchmark
```

✅ **Asking for clarification**:
```
I see two different approaches in the codebase. Which should I follow?
Can you confirm the database schema for the users table?
```

## Verification Strategies

### 1. Check Tool Usage

When the agent makes claims about your codebase, check if it used tools:

**Trustworthy:**
```
[Tool: read_file] src/config/database.ts
[Tool: search_files] Searching for "connection pool"

Based on the code, the connection pool size is set to 20...
```

**Questionable:**
```
The connection pool size is set to 20 in the database configuration
```
(No tool usage shown)

### 2. Ask for Sources

```
You: Where is that function defined?
Agent: [Tool: search_files] Searching for function definition...
        It's in src/utils/validation.ts, line 45
```

If the agent can't provide a source, be skeptical.

### 3. Request Verification

```
You: Can you verify that by reading the actual file?
Agent: [Tool: read_file] src/auth/login.ts
        Actually, I was mistaken. The function signature is different...
```

### 4. Test Specific Claims

When the agent makes specific claims:

```
Agent: The API returns a 429 status code after 100 requests per minute

You: Can you check the actual rate limiting code to confirm?
Agent: [Reads code] Actually, it's 1000 requests per hour, not 100 per minute
```

## Handling Hallucinations

### When You Detect a Hallucination

**1. Point it out clearly:**
```
You: That function doesn't exist. Can you check what's actually in that file?
```

**2. Provide correct information:**
```
You: We're using PostgreSQL, not MongoDB. Please revise based on that.
```

**3. Ask the agent to verify:**
```
You: Please read the file and verify that before proceeding.
```

### Correcting Course

**Don't:**
```
You're wrong about everything
```

**Do:**
```
I think there's some confusion. Let me clarify:
- We're using PostgreSQL (not MongoDB)
- The function is in src/db/users.ts (not src/models/user.ts)
- The column is 'email_address' (not 'email')

Can you revise based on this?
```

### Preventing Hallucinations

**1. Encourage tool usage:**
```
Before implementing, please read the existing authentication code
to understand the current pattern.
```

**2. Request verification:**
```
Can you verify the API documentation before we proceed?
```

**3. Provide explicit information:**
```
For reference, our database schema is:
- users table: id, email, password_hash, created_at
- sessions table: id, user_id, token, expires_at

Now implement the login endpoint.
```

**4. Break down complex tasks:**
Instead of:
```
Implement complete authentication system
```

Do:
```
1. First, show me the existing user model
2. Now, implement the password hashing
3. Next, create the login endpoint
...
```

## Special Cases

### External Libraries and APIs

Agents may hallucinate about third-party libraries:

```
Agent: The library has a convenient validateAll() method

You: Can you check the actual library documentation? I don't recall that method.
```

**Solution**: Ask the agent to read documentation or example code from the project.

### Best Practices and Patterns

Agents may invent "best practices":

```
Agent: The standard pattern is to always use X

You: Is that specific to our project, or a general claim?
```

**Solution**: Focus on what works for your project, not universal "best practices."

### Performance Claims

Agents may make unfounded performance claims:

```
Agent: This will be 10x faster

You: How are you measuring that? We should benchmark to confirm.
```

**Solution**: Always verify performance claims with actual testing.

## Building Healthy Skepticism

### Balance Trust and Verification

- **Trust**: Tool-verified information, qualified statements, admitted uncertainty
- **Verify**: Specific claims, external references, performance assertions, critical decisions

### Verification Checklist

For important decisions, verify:
- [ ] Does the referenced code/file actually exist?
- [ ] Did the agent use tools to check?
- [ ] Is this consistent with earlier decisions?
- [ ] Does this match external documentation?
- [ ] Are performance claims realistic?
- [ ] Is the agent expressing appropriate uncertainty?

### When in Doubt

```
You: I want to verify this before we proceed. Can you show me the actual code/config/documentation?
```

It's always okay to ask for verification.

## Tips

1. **Tool usage is your friend**: Trust information derived from tool use more than general statements
2. **Verify critical paths**: Authentication, security, data loss scenarios—verify thoroughly
3. **Test don't trust**: For performance, behavior, and edge cases—test the implementation
4. **Explicit over implicit**: Provide explicit information rather than assuming the agent knows
5. **Iterate with feedback**: Correct hallucinations immediately so they don't compound

## Next Steps

- [Iterative Workflows](./iterative-workflows.md): Build solutions incrementally with verification
- [Prompting Strategies](./prompting-strategies.md): Ask questions that minimize hallucinations
- [Context and Memory](./context-and-memory.md): Ensure agents have accurate context
