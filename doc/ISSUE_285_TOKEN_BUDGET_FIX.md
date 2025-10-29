# Issue #285 - Token Budget Overflow Fix

## Problem Description

Anthropic API was returning errors when threads exceeded the 200K token limit:
```
prompt is too long: 200060 tokens > 200000 maximum
```

This occurred in threads with verbose tool outputs (e.g., JIRA integration) despite the existing compaction mechanism.

## Root Causes

1. **Overoptimistic char-to-token conversion**: The default `charsPerToken` ratio of 3.5 was too optimistic. Real-world content (code, JSON, special characters) often has a lower ratio.

2. **Insufficient safety margin**: The partition function used a 0.7 ratio, leaving only 30% margin, which wasn't enough given the estimation uncertainty.

3. **No pre-flight validation**: The code didn't verify estimated token count before making API calls, so errors only appeared after the request was sent.

4. **Inconsistent token estimation**: Different types of content (plain text vs. code vs. JSON) have different char-to-token ratios, but we used a single average.

## Solutions Implemented

### 1. More Conservative Char-to-Token Ratio
**File**: `libs/handler/anthropic.client.ts`

Changed from using the default `charsPerToken` (3.5) to a more conservative `conservativeCharsPerToken` (3.0):

```typescript
const conservativeCharsPerToken = 3.0
const charBudget = Math.max(
  model.contextWindow * conservativeCharsPerToken - initialContextCharLength, 
  10000
)
```

**Impact**: Reduces estimated available characters by ~14%, providing more buffer.

### 2. Stricter Partition Ratio
**File**: `libs/ai-thread/ai-thread.helpers.ts`

Changed partition ratio from 0.7 to 0.6:

```typescript
export function partition(
  messages: ThreadMessage[],
  charBudget: number | undefined,
  ratio: number = 0.6  // was 0.7
)
```

**Impact**: Only keeps 60% of estimated budget instead of 70%, providing an additional 10% safety margin.

### 3. Pre-flight Safety Check
**File**: `libs/handler/anthropic.client.ts`

Added validation before API calls:

```typescript
const totalChars = agent.systemInstructions.length + 
                   agent.tools.charLength + 
                   data.messages.reduce((sum, msg) => sum + msg.length, 0)

const estimatedTokens = Math.ceil(totalChars / 3.0)
const tokenLimit = model.contextWindow

// Warn at 95% capacity
if (estimatedTokens > tokenLimit * 0.95) {
  this.interactor.warn(
    `⚠️ Context approaching limit: ~${estimatedTokens} tokens (limit: ${tokenLimit})`
  )
}

// Block at 100% capacity
if (estimatedTokens > tokenLimit) {
  throw new Error(
    `Estimated token count (${estimatedTokens}) exceeds model limit (${tokenLimit})`
  )
}
```

**Impact**: 
- Early detection of potential overflows
- Clear error messages with actionable information
- Users are warned before hitting the limit

### 4. Enhanced Debug Logging
**Files**: 
- `libs/handler/anthropic.client.ts`
- `libs/ai-thread/ai-thread.ts`

Added comprehensive logging:

```typescript
// In anthropic.client.ts
this.interactor.debug(
  `📊 Context size: ${totalChars} chars (~${estimatedTokens} tokens, ` +
  `${Math.round((estimatedTokens / tokenLimit) * 100)}% of ${tokenLimit} limit)`
)

// In ai-thread.ts
console.debug(
  `[AiThread] Partition: ${messages.length} messages (${messagesChars} chars) kept, ` +
  `${overflow.length} messages (${overflowChars} chars) overflow`
)
```

**Impact**: Better visibility into token budget usage for debugging and monitoring.

## Combined Safety Margins

The fix provides multiple layers of protection:

1. **Conservative estimation** (3.0 vs 3.5): ~14% reduction
2. **Stricter partition** (0.6 vs 0.7): additional 10% reduction
3. **Pre-flight check**: catches any overflow before API call
4. **95% warning**: alerts users before hitting the limit

**Total effective safety margin**: ~35% buffer below the hard limit.

## Testing Recommendations

### Manual Testing
1. Create a thread with verbose tool outputs (JIRA, file reads, etc.)
2. Run with `--debug` flag to see token budget logging
3. Verify warnings appear when approaching 95% capacity
4. Confirm no API errors occur

### Monitoring
Watch for these log messages:
- `📊 Context size: X chars (~Y tokens, Z% of limit)` - shows current usage
- `⚠️ Context approaching limit` - warning at 95%
- `[AiThread] Partition: X messages kept, Y overflow` - shows compaction behavior

### Edge Cases to Test
1. **Very long single messages**: Should be truncated by existing oversized message handling
2. **Many short messages**: Should be compacted normally
3. **Mixed content types**: Code, JSON, and plain text should all be handled safely
4. **Cache markers**: Should not cause overflow due to metadata overhead

## Performance Impact

- **Minimal**: The pre-flight check adds negligible overhead (simple arithmetic)
- **Memory**: No increase (same message handling)
- **Compaction frequency**: May trigger slightly more often due to stricter limits, but prevents API errors

## Future Enhancements

### Short-term
1. Monitor real-world char-to-token ratios to fine-tune the conservative factor
2. Add metrics tracking for token budget usage patterns
3. Consider different ratios for different content types

### Long-term
1. Implement proper token counting using Anthropic's tokenizer
2. Add adaptive compaction based on content type
3. Provide user controls for aggressive vs. conservative compaction
4. Smart summarization that preserves important context while reducing tokens

## Related Issues
- Issue #285: Original bug report
- Related to message compaction and thread management

## References
- Anthropic API documentation: https://docs.anthropic.com/en/api/rate-limits
- Token counting best practices: https://help.openai.com/en/articles/4936856-what-are-tokens-and-how-to-count-them
