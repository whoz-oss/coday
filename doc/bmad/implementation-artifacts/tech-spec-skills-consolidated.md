---
title: 'Skills System — Spec consolidée (V1 + UI + V2)'
type: 'feature'
created: '2025-03-20'
status: 'done'
consolidated: '2025-03-21'
context:
  - doc/bmad/project-context.md
  - doc/bmad/implementation-artifacts/comparison-openclaw-skills.md
original_specs:
  - doc/bmad/implementation-artifacts/tech-spec-wip.md
  - doc/bmad/implementation-artifacts/tech-spec-skills-ui.md
  - doc/bmad/implementation-artifacts/tech-spec-skills-v2.md
---

# Skills System — Spec consolidée

> **Consolidation des 3 specs originales en un document unique.**
> Toutes les features sont implémentées et le build passe (29/29 tasks).

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Les instructions de workflow (step files BMAD, guides) sont chargees via `mandatoryDocs` — integralement dans le system prompt au boot. Cela gaspille des milliers de tokens par thread. Il n'existe pas de mecanisme de progressive disclosure. De plus, l'IHM ne permet pas de configurer les skills, et il manque des features de robustesse (gating, baseDir, limites, import ZIP, skill-creator).

**Approach:** Systeme de Skills a 3 niveaux inspire d'OpenClaw, avec UI Angular et features avancees :

### Phase 1 — Core L1/L2/L3 (spec originale: tech-spec-wip.md)
- **L1 Metadata** (~100 tokens/skill) : `name` + `description` extraits du frontmatter YAML, injectes dans le system prompt
- **L2 Instructions** (<5k tokens) : corps du SKILL.md, charge via tool `SKILL__load_skill` (JIT)
- **L3 Resources** : fichiers additionnels references dans le SKILL.md, lus via FILE tools existants

### Phase 2 — UI Angular (spec originale: tech-spec-skills-ui.md)
- Section "Skills" dans agent-form, calquee sur le pattern Mandatory Documents
- Gestion dynamique (ajout/suppression de chemins SKILL.md)

### Phase 3 — Features avancees (spec originale: tech-spec-skills-v2.md)
- **Gating** : `requires.bins` et `requires.env` dans le frontmatter — skills non satisfaits exclus du L1
- **{baseDir}** : resolution dans le L2 pour que les scripts referencent leur propre dossier
- **Limites** : `maxSkillsInPrompt` et `maxSkillFileBytes` configurables dans `coday.yaml`
- **Upload ZIP** : route API + bouton IHM pour importer un skill package
- **Skill Creator** : skill pour creer d'autres skills

## Boundaries & Constraints

**Always:**
- Suivre le pattern `AssistantToolFactory` existant
- Imports `@coday/*` — jamais de chemins relatifs cross-lib
- Exporter dans `index.ts` de chaque lib modifiee
- Retrocompatibilite totale : `mandatoryDocs`/`optionalDocs` inchanges, SKILL.md sans `requires` fonctionne
- Composants Angular standalone, injection via `inject()`
- Pas de point-virgule, guillemets simples (conventions projet)
- Le gating exclut du L1 — le skill devient invisible pour le LLM

**Never:**
- Charger le corps L2 des skills au boot
- Modifier le comportement de `mandatoryDocs` ou `optionalDocs`
- Executer du code issu du ZIP sans validation prealable
- Ecraser un skill existant sans confirmation explicite
- Ajouter de dependance circulaire entre libs

</frozen-after-approval>

## Code Map

### Phase 1 — Core
- `libs/model/src/lib/skill.ts` — Type `SkillMetadata` (name, description, path, entrypoint?, requires?)
- `libs/model/src/lib/with-docs.ts` — Ajout `skills?: string[]` a `WithDocs`
- `libs/model/src/index.ts` — Exports
- `libs/function/src/lib/parse-skill-files.ts` — `parseSkillFiles()` avec gating + limites + CRLF normalization
- `libs/function/src/index.ts` — Export
- `libs/function/package.json` — Dependance `yaml`
- `libs/integration/src/lib/skill.tools.ts` — `SkillTools` : `list_skills` + `load_skill` avec {baseDir} resolution
- `libs/integration/src/index.ts` — Export
- `libs/function/src/lib/get-formatted-docs.ts` — Section "Available Skills" L1 dans le system prompt
- `libs/agent/src/lib/agent.service.ts` — Parsing centralise dans `tryAddAgent()`, instanciation directe SkillTools

### Phase 2 — UI
- `apps/client/src/app/core/services/agent-crud-api.service.ts` — `skills?: string[]` dans AgentDefinition client + `uploadSkillZip()`
- `apps/client/src/app/components/agent-form/agent-form.component.ts` — Propriete skills, methodes add/remove, upload ZIP
- `apps/client/src/app/components/agent-form/agent-form.component.html` — Section Skills + bouton upload ZIP
- `apps/client/src/app/components/agent-form/agent-form.component.scss` — Styles skill-row/skill-field

### Phase 3 — Features avancees
- `libs/model/src/lib/skills-config.ts` — Type `SkillsConfig` (maxSkillsInPrompt, maxSkillFileBytes)
- `libs/model/src/lib/project-description.ts` — `skillsConfig?: SkillsConfig` dans ProjectDescription
- `libs/service/package.json` — Dependance `adm-zip`
- `libs/service/src/lib/agent-crud.service.ts` — `uploadSkillZip()` avec path traversal guard + merge skills dans update()
- `apps/server/src/lib/agent.routes.ts` — Route `POST /api/projects/:projectName/skills/upload`
- `skills/skill-creator/` — Skill + script validation + reference anatomy

## Tasks & Acceptance

### Phase 1 — Core L1/L2/L3

- [x] `libs/model/src/lib/skill.ts` — Type `SkillMetadata { name, description, path, entrypoint?, requires? }`
- [x] `libs/model/src/lib/with-docs.ts` — Ajout `skills?: string[]` a `WithDocs`
- [x] `libs/model/src/index.ts` — Export des types skill + skills-config
- [x] `libs/function/package.json` — Dependance `"yaml": "catalog:"`
- [x] `libs/function/src/lib/parse-skill-files.ts` — `parseSkillFiles()` avec frontmatter YAML, gating, limites, CRLF normalization
- [x] `libs/function/src/index.ts` — Export `parseSkillFiles`
- [x] `libs/integration/src/lib/skill.tools.ts` — `SkillTools` avec `list_skills` + `load_skill` + entrypoint + {baseDir} resolution
- [x] `libs/integration/src/index.ts` — Export `SkillTools`
- [x] `libs/function/src/lib/get-formatted-docs.ts` — Section "Available Skills" L1 dans system prompt
- [x] `libs/agent/src/lib/agent.service.ts` — Parsing centralise + instanciation directe SkillTools + propagation skillsConfig

### Phase 2 — UI Angular

- [x] `apps/client/src/app/core/services/agent-crud-api.service.ts` — `skills?: string[]` dans AgentDefinition client
- [x] `apps/client/src/app/components/agent-form/agent-form.component.ts` — Propriete skills, methodes addSkillRow/removeSkillRow, init/build
- [x] `apps/client/src/app/components/agent-form/agent-form.component.html` — Section "Skills" avec liste dynamique
- [x] `apps/client/src/app/components/agent-form/agent-form.component.scss` — Styles `.skill-row` / `.skill-field`
- [x] `libs/service/src/lib/agent-crud.service.ts` — Merge `skills` dans update()

### Phase 3 — Gating, baseDir, limites

- [x] `libs/model/src/lib/skills-config.ts` — Type `SkillsConfig { maxSkillsInPrompt?, maxSkillFileBytes? }`
- [x] `libs/model/src/lib/skill.ts` — `requires?: { bins?: string[], env?: string[] }` dans SkillMetadata
- [x] `libs/model/src/lib/project-description.ts` — `skillsConfig?: SkillsConfig` dans ProjectDescription
- [x] `libs/function/src/lib/parse-skill-files.ts` — Gating (check bins/env), limites, CRLF normalization
- [x] `libs/integration/src/lib/skill.tools.ts` — Resolution `{baseDir}` dans les 2 branches (body + entrypoint)

### Phase 3 — Upload ZIP

- [x] `pnpm-workspace.yaml` + `libs/service/package.json` — Dependance `adm-zip`
- [x] `libs/service/src/lib/agent-crud.service.ts` — `uploadSkillZip()` avec path traversal guard
- [x] `apps/server/src/lib/agent.routes.ts` — Route `POST /api/projects/:projectName/skills/upload`
- [x] `apps/client/src/app/core/services/agent-crud-api.service.ts` — `uploadSkillZip()` service client
- [x] `apps/client/src/app/components/agent-form/agent-form.component.ts` — `onSkillZipSelected()` + UI upload
- [x] `apps/client/src/app/components/agent-form/agent-form.component.html` — Bouton upload ZIP

### Phase 3 — Skill Creator

- [x] `skills/skill-creator/SKILL.md` — Skill de creation avec workflow guide
- [x] `skills/skill-creator/scripts/validate_skill.py` — Script de validation frontmatter + structure
- [x] `skills/skill-creator/references/skill-anatomy.md` — Documentation anatomy d'un skill Coday

### Acceptance Criteria

**Core:**
- Given un agent YAML avec `skills: ["./skills/bmad-quick-dev.md"]`, when le thread demarre, then le system prompt contient la section L1 ET les tools SKILL sont disponibles
- Given `SKILL__load_skill({ name: "bmad-quick-dev" })`, then le corps markdown (sans frontmatter) est retourne
- Given un SKILL.md avec `entrypoint: ./workflow.md`, then le contenu du fichier entrypoint est retourne
- Given un agent sans skills, then aucun tool SKILL n'est expose
- Given un SKILL.md avec frontmatter invalide, then warning + fichier ignore
- Given `SKILL__load_skill({ name: "inexistant" })`, then message d'erreur avec liste des skills disponibles

**UI:**
- Given un agent avec skills YAML, when edition, then les skills sont pre-remplis dans le formulaire
- Given ajout d'un skill dans le formulaire + sauvegarde, then le YAML contient le champ skills
- Given suppression de tous les skills + sauvegarde, then le champ skills est absent du YAML

**Gating/Limites:**
- Given un SKILL.md avec `requires: { bins: ["python3"] }` et python3 absent, then le skill est exclu du L1 et des tools
- Given `{baseDir}` dans un SKILL.md, then remplace par le chemin absolu du dossier du skill
- Given `maxSkillsInPrompt: 5` et 10 skills, then seuls 5 sont injectes avec warning

**Upload ZIP:**
- Given un ZIP contenant un SKILL.md valide, when upload, then extraction dans `skills/{name}/`
- Given un ZIP avec path traversal (`../../../etc/passwd`), then rejet 400
- Given un skill existant + overwrite=false, then 409 Conflict

**Skill Creator:**
- Given le skill-creator charge, when un agent suit le workflow, then un nouveau skill valide est cree

## Reviews effectuees

### Phase 1 — Core (3 rounds, 9 reviews)
- `review-acceptance.md`, `review-blind-hunter.md`, `review-edge-cases.md` (v0.1)
- `review-v2-acceptance.md`, `review-v2-blind-hunter.md`, `review-v2-edge-cases.md` (v0.2)
- `review-v3-acceptance.md`, `review-v3-blind-hunter.md`, `review-v3-edge-cases.md` (v0.3)

### Phase 2 — UI (3 reviews)
- `review-skills-ui-acceptance.md`, `review-skills-ui-blind.md`, `review-skills-ui-edges.md`

### Phase 3 — V2 Features (3 rounds, 9 reviews)
- `review-skills-v2-acceptance.md`, `review-skills-v2-blind.md`, `review-skills-v2-edges.md` (v0.1)
- `review-skills-v2.1-acceptance.md`, `review-skills-v2.1-blind.md`, `review-skills-v2.1-edges.md` (v0.2)
- `review-skills-v2.2-acceptance.md`, `review-skills-v2.2-blind.md`, `review-skills-v2.2-edges.md` (v0.3)

## Spec Change Log

### v1.0 — 2025-03-21 — Consolidation

**Consolidation des 3 specs originales :**
- `tech-spec-wip.md` (V1 Core L1/L2/L3 — cree 2025-03-20, 3 rounds reviews)
- `tech-spec-skills-ui.md` (UI Angular — cree 2025-07-18, 1 round reviews)
- `tech-spec-skills-v2.md` (V2 Gating/Upload/Creator — cree 2025-07-19, 3 rounds reviews)

Toutes les taches sont completees. Le build passe (29/29 tasks NX).
Status passe de `draft` a `done`.

Les specs originales sont conservees comme reference historique.
