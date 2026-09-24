# BMad Skills — Index

Stateful workflow skills for the BMad lifecycle. Loaded by BmadOrchestrator and BmadBuilder.

Skill routing depends on shape. The test: does `workflow.md` exist at the skill root?

- **Step-file skills** (`workflow.md` present): MUST be rendered via the render-skill script before reading. They contain unresolved tokens (`{workflow.*}`, `{{.key}}`, `[[bmad-snapshot:...]]`) that only the renderer resolves.
- **SKILL.md skills** (no `workflow.md`): read `SKILL.md` directly via FILES. The renderer deliberately skips these. They resolve their own customization at activation time via `resolve-customization`.

## Skill Registry

| Skill | Shape | Path | Owner | Gate | Purpose |
|---|---|---|---|---|---|
| bmad-prd | SKILL.md | `coday/skills/bmad/bmad-prd/SKILL.md` | BmadOrchestrator | Gate 1 | PRD creation, update, validation — elicitation-first, fast or coaching path |
| bmad-ux | SKILL.md | `coday/skills/bmad/bmad-ux/SKILL.md` | BmadOrchestrator | Gate 1 (before architecture) | UX design spine (DESIGN.md + EXPERIENCE.md) — required before bmad-architecture on project bootstrap |
| bmad-spec | SKILL.md | `coday/skills/bmad/bmad-spec/SKILL.md` | BmadOrchestrator | Gate 1 | Distill intent into canonical 5-field SPEC kernel, companions, and stories |
| bmad-architecture | SKILL.md | `coday/skills/bmad/bmad-architecture/SKILL.md` | BmadOrchestrator | Gate 1→2 | Architecture spine — invariants, boundaries, dependency rules |
| bmad-create-epics-and-stories | SKILL.md | `coday/skills/bmad/bmad-create-epics-and-stories/SKILL.md` | BmadOrchestrator | Gate 2 | PRD → epics → stories with full ACs |
| bmad-sprint-planning | SKILL.md | `coday/skills/bmad/bmad-sprint-planning/SKILL.md` | BmadOrchestrator | Gate 2 | Readiness gate — ACs complete, no open blockers, DoD met |
| bmad-testarch-atdd | SKILL.md | `coday/skills/bmad/bmad-testarch-atdd/SKILL.md` | BmadOrchestrator | Gate 2 | Red-phase test scaffolds generated alongside stories |
| bmad-review | SKILL.md | `coday/skills/bmad/bmad-review/SKILL.md` | BmadOrchestrator + Reviewer | Gates 1,2,3 | Multi-lens adversarial review — adversarial, edge-case, verification-gap |
| bmad-retrospective | SKILL.md | `coday/skills/bmad/bmad-retrospective/SKILL.md` | BmadOrchestrator | Post-epic | Evidence-based epic retro — feeds findings into next PRD cycle |
| bmad-help | SKILL.md | `coday/skills/bmad/bmad-help/SKILL.md` | BmadOrchestrator | Anytime | Context-aware workflow orientation, next-step recommendations, and BMad module Q&A |
| factory-bmad-projection | SKILL.md | `coday/skills/bmad/factory-bmad-projection/SKILL.md` | ProductEngineer | Gates 1–4 | Declares the `bmad-story` domain workflow and maps Jira Story/BMAD gate facts to the generic Factory run protocol |
| bmad-build | step-file | render via render-skill script → rendered `workflow.md` | BmadBuilder | Gate 3 | Autonomous story implementation — red-green-refactor, task by task |
| bmad-build-auto | step-file | render via render-skill script → rendered `workflow.md` | BmadBuilder | stories mode | Unattended story implementation — no human checkpoints, HALTs with terminal status instead of asking |

### Gate 1: `bmad-prd` vs `bmad-spec`

Both `bmad-prd` and `bmad-spec` produce Gate 1 inputs for `bmad-architecture`. They are alternative pathways, not mandatory sequential steps — do not run both blindly together. Choose based on intent:
- **`bmad-prd`**: Elicitation-first, PM-document oriented workflow for product shaping, problem discovery, and narrative alignment.
- **`bmad-spec`**: Distillation-first workflow producing a canonical 5-field SPEC kernel and structured companion package.

If both artifacts exist for the same initiative:
- The SPEC package is the authoritative downstream contract.
- The PRD serves as source/rationale context and must be listed as an absorbed source or adopted companion in the SPEC package as appropriate.
- `bmad-architecture` consumes the SPEC package when available; otherwise it consumes the PRD or raw inputs.

## Usage

**Step-file skills (bmad-build, bmad-build-auto):**
```
BmadBuilder:
  → call render-skill PROJECT_SCRIPT: --project-root {project-root} --skill {project-root}/coday/skills/bmad/<skill-name>/
  → script returns: absolute path to rendered workflow.md
  → read rendered workflow.md via FILES
  → follow step by step
```

**SKILL.md skills (all others):**
```
BmadOrchestrator:
  → read coday/skills/bmad/<skill-name>/SKILL.md directly via FILES
  → if skill instructs resolve_customization.py: use resolve-customization script
      with --skill <absolute-skill-dir> --key workflow
  → internal file references (references/, assets/) are relative to the skill dir
```

## State Files

All workstream runtime state lives under `forge/bmad/workstreams/<slug>/`:
- `planning-artifacts/` — PRD, UX, architecture spine, specs, epics, memlogs
- `implementation-artifacts/` — stories, sprint state, API contracts
- `test-artifacts/` — ATDD scaffolds and test evidence
