# AI Models Update Guide

## Essential URLs

### Anthropic
- **Official Pricing**: https://www.anthropic.com/pricing
- **API Docs**: https://docs.anthropic.com/en/docs/about-claude/models
- ⚠️ Look for "Latest models" and "Legacy models" to identify new ones

### Google Gemini
- **Model List**: https://ai.google.dev/gemini-api/docs/models
- **Pricing**: https://ai.google.dev/pricing
- **Gemini 3**: https://ai.google.dev/gemini-api/docs/gemini-3
- ⚠️ Pay attention to "Preview" vs "Stable" models

### OpenAI
- **Model List**: https://developers.openai.com/api/docs/models
- **Pricing**: https://developers.openai.com/api/docs/pricing
- **Python SDK**: https://github.com/openai/openai-python (check README for models in examples)
- ⚠️ Exclude specialized models (Codex, Chat, Realtime, Audio)

## Update Procedure

### 1. Identify new models
- Check pricing pages for "Latest" vs "Legacy" models
- Verify release dates (knowledge cutoff, latest update)
- Note models marked as "Deprecated"

### 2. Collect specs
For each model, gather:
- **Context window** (in tokens)
- **Max output tokens**
- **Input price** (per 1M tokens)
- **Output price** (per 1M tokens)
- **Cache read price** (if available)
- **Cache write price** (if available, especially Anthropic)

### 3. Key considerations

**Anthropic**
- Two-tier pricing: ≤200k tokens vs >200k tokens
- Cache write AND cache read (two different prices)
- Generations: 4.5 → 4.6 (Opus, Sonnet), Haiku stays at 4.5

**Google**
- Two-tier pricing: ≤200k tokens vs >200k tokens
- Generations: 2.5 → 3.x
- Distinguish "Preview" (testing) vs stable
- Very large context window (1M tokens)

**OpenAI**
- Exclude `-codex` models (for Codex application)
- Exclude `-chat-latest` models (for ChatGPT)
- Exclude `realtime`, `audio`, `transcribe`, `tts` models
- Exclude `-pro` models (require Responses API, not Chat Completions)
- Generations: 5.1 → 5.2
- No cache write, only cache read

### 4. Files to modify

```
libs/handler/src/lib/anthropic.client.ts  → ANTHROPIC_DEFAULT_MODELS
libs/handler/src/lib/google.client.ts     → models (in GoogleClient)
libs/handler/src/lib/openai.client.ts     → OPENAI_DEFAULT_MODELS
```

### 5. Aliases to respect

- **BIG**: Default flagship model, good quality/price balance
- **BIGGEST**: Most powerful premium model (if available)
- **SMALL**: Fast economical model

### 6. Verification

```bash
# Compile
nx run handler:build

# Manually test model names
# (create a thread with each provider and verify the model is accepted)
```

## Pitfalls to avoid

❌ Don't confuse general models with specialized ones (Codex, etc.)
❌ Don't forget to update all 3 providers
❌ Don't ignore cache prices (especially Anthropic: write AND read)
❌ Don't mix generations (keep consistency per provider)
❌ Don't forget to check context windows (they can change)
❌ Don't include models requiring different APIs (e.g., Responses API)

## Commit convention

```
feat: update AI model definitions to latest versions (Anthropic X.X, Google Gemini X, OpenAI GPT-X.X)
```

## Update frequency

- **Quarterly**: Check all 3 providers for new models
- **Immediate**: If a model becomes "deprecated" or "legacy"
- **Occasional**: If major pricing changes are announced
