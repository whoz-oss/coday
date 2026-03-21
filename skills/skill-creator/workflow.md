# Skill Creator Workflow

Guide for creating effective Coday skills. Follow these steps sequentially.

## Step 1: Understand the Need

Ask the user:
- What problem does this skill solve?
- What tools/binaries does it need? (for `requires.bins`)
- What env vars does it need? (for `requires.env`)
- Is this a simple skill (single SKILL.md) or complex (with step files)?

## Step 2: Plan the Structure

Decide on the skill directory layout:
```
skills/{skill-name}/
├── SKILL.md              # Required — frontmatter + instructions (or entrypoint)
├── workflow.md            # Optional — detailed workflow (use entrypoint)
├── scripts/               # Optional — executable scripts
│   └── run.py
└── references/            # Optional — reference docs for the agent
    └── api-spec.md
```

**Key decisions:**
- If instructions are long (>50 lines): use `entrypoint: ./workflow.md` in SKILL.md
- If the skill needs scripts: put them in `scripts/`, reference via `{baseDir}/scripts/`
- If the skill needs reference docs: put them in `references/`

## Step 3: Create the Structure

Create the directory and files:
```bash
mkdir -p skills/{name}/{scripts,references}
```

## Step 4: Write the SKILL.md

**Frontmatter (required fields):**
```yaml
---
name: my-skill              # hyphen-case, max 64 chars
description: Short description of what this skill does and when to use it.
entrypoint: ./workflow.md    # Optional — file to load instead of body
requires:                    # Optional — prerequisites
  bins: [python3, node]      # Binaries that must be in PATH
  env: [API_KEY]             # Env vars that must be set
---
```

**Body or entrypoint content:**
- Be concise — the context window is shared with everything else
- Use `{baseDir}` for portable paths to scripts and resources
- Default assumption: the LLM is already smart. Only add what it doesn't know.
- Prefer examples over verbose explanations

**Progressive Disclosure:**
- L1 (always loaded): name + description from frontmatter (~100 tokens)
- L2 (on demand): body/entrypoint loaded via `SKILL__load_skill`
- L3 (on demand): step files and resources loaded via FILE tools

## Step 5: Validate

Run the validation script:
```bash
python3 {baseDir}/scripts/validate_skill.py skills/{name}
```

Fix any issues reported.

## Step 6: Add the Skill to the Current Agent (PATCH mode)

**IMPORTANT**: After creating and validating the skill, you MUST automatically add it to the current agent's skills list. The user asked YOU to create this skill, so they want YOU to have it.

**Use surgical PATCH editing — NEVER do a full rewrite of coday.yaml.**

1. Read `coday.yaml` at the project root
2. Find your own agent name in the `agents:` list
   - If you cannot find your own name in `coday.yaml`, **stop** and tell the user: "I could not find my agent definition in coday.yaml. Please add the skill `{skill-name}` to your agent's skills list manually."
3. Locate the `skills:` key under your agent definition
4. Check that the new skill is NOT already present in the list (dedup — skip if already there)
5. Add exactly ONE line `      - {skill-name}` after the last existing skill entry
6. Use `editFiles` / `writeFileChunk` in **patch mode** — insert only the new line. Do NOT rewrite or regenerate the entire file

Example patch — if the last skill in the list is `existing-skill`, insert after it:
```yaml
    skills:
      - existing-skill
      - my-skill        # ← insert ONLY this line
```

**Rules:**
- Use the short skill name (e.g. `my-skill`), not the full path. The system resolves short names to `skills/{name}/SKILL.md` automatically
- Preserve existing indentation (6 spaces before the dash)
- Do NOT reorder, remove, or modify any other content in `coday.yaml`

The skill will be available on the NEXT thread/conversation (agent config is loaded at thread creation).

## Step 7: Iterate

- Tell the user the skill is created AND added to this agent
- Mention they need to start a new thread to use it
- Test the skill by starting a new conversation and triggering it
