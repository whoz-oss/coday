# Anatomy of a Coday Skill

## Directory Structure

```
skills/{skill-name}/
├── SKILL.md              # Required — entry point with YAML frontmatter
├── workflow.md            # Optional — detailed workflow (via entrypoint)
├── step-01-*.md           # Optional — sequential step files
├── scripts/               # Optional — executable scripts
│   ├── validate.py
│   └── generate.sh
└── references/            # Optional — reference documentation
    ├── api-spec.md
    └── examples.md
```

## SKILL.md Format

```yaml
---
name: my-skill                    # Required. hyphen-case, max 64 chars
description: What this skill does # Required. Shown in L1 (system prompt)
entrypoint: ./workflow.md         # Optional. File loaded instead of body
requires:                         # Optional. Prerequisites for gating
  bins: [python3, node]           # Binaries in PATH
  env: [API_KEY, SECRET]          # Environment variables
---

Body content here (L2 — loaded on demand via SKILL__load_skill)
```

## Progressive Disclosure (L1/L2/L3)

| Level | When Loaded | Token Cost | Content |
|-------|------------|------------|---------|
| **L1 Metadata** | Always (boot) | ~100 tokens/skill | `name` + `description` from frontmatter |
| **L2 Instructions** | On demand (`load_skill`) | <5k tokens | Body of SKILL.md or entrypoint file |
| **L3 Resources** | On demand (FILE tools) | Unlimited | Step files, scripts, references |

**Key principle:** Only L1 is always in the prompt. L2 and L3 are loaded JIT.

## Portable Paths with {baseDir}

Use `{baseDir}` in your skill content to reference files relative to the SKILL.md:

```markdown
Run the script: `python3 {baseDir}/scripts/generate.py`
See reference: Read `{baseDir}/references/api-spec.md`
```

`{baseDir}` is replaced at runtime with the absolute path to the skill directory.

## Gating with requires

Skills with unmet prerequisites are excluded from L1 (invisible to the LLM):

```yaml
requires:
  bins: [python3]        # Checked via `which`
  env: [OPENAI_API_KEY]  # Checked via process.env
```

If any requirement fails, the skill is silently excluded with a warning log.

## Best Practices

1. **Concise is key** — The context window is shared. Only add what the LLM doesn't already know.
2. **Use entrypoint** for complex skills — Keep SKILL.md as a thin router.
3. **Use {baseDir}** for all internal paths — Makes skills portable.
4. **Add requires** for external dependencies — Prevents confusion when tools are missing.
5. **One skill = one concern** — Don't bundle unrelated capabilities.
6. **Prefer examples over explanations** — The LLM learns faster from examples.
