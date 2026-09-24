# Sources d'autorité de la Factory

Ce document distingue ce qui fait autorité aujourd'hui, pendant la migration et dans l'architecture cible. Il évite qu'un fichier généré, une documentation ou deux implémentations coexistantes soient modifiés comme des sources concurrentes.

## Stage shutdown opérationnel

`factory/src/entrypoints/factory-operational.ts` est la source du bundle généré `factory/runtime/factory-operational.mjs`. Ce bundle porte l'unique registre active-case consommé par `run.mjs` et `lib/agentos.mjs`; il n'importe jamais l'ensemble de `agentos.mjs`.

Depuis le Stage 4B, le run courant et l’état once-only sont exclusivement dans `src/lib/registry.ts`, fermé dans le bundle opérationnel. `lib/registry.mjs` ne contient aucun état et réexporte ce bundle pour préserver les importeurs legacy. Les résolveurs de gates restent dans `lib/review-gate.mjs` et sont injectés dans l’application de shutdown. L’artefact existant reste périmé jusqu’à sa reconstruction par la toolchain isolée.

## Autorités actuelles

| Domaine | Source d'autorité actuelle | Notes |
|---|---|---|
| Runtime legacy | Fichiers `.mjs` historiques exécutés sous `factory/` | Ils restent canoniques pour les consommateurs non basculés au Stage 2. |
| Cluster opérationnel shutdown/active-case/registry | `factory/src/` et `factory/src/entrypoints/factory-operational.ts` | Autorité TypeScript; le bundle généré est partagé, la façade registry est stateless. |
| Contrats et invariants des modules | Comportement des `.mjs`, complété par `factory/lib/README.md` | En cas de conflit, le runtime observé prime ; le conflit documentaire doit être corrigé. |
| Dispatch et workflows disponibles | `factory/run.mjs` et modules référencés | La structure illustrative d'un README n'est pas exhaustive. |
| Commandes oracle | Modules de domaine/oracle concernés | Ni l'agent ni sa prose ne peuvent remplacer cette autorité. |
| Verdict oracle | Code de sortie du processus lancé par la Factory | L'interprétation textuelle n'est pas un verdict. |
| État du checkout mesuré | Système de fichiers et Git observés par les modules de mesure | Les affirmations d'un agent ne font pas autorité. |
| État et événements AgentOS | API AgentOS derrière l'adaptateur Factory | L'ordre et les statuts suivent le contrat backend documenté. |
| Faits d'un run | Registre JSONL append-only et artefacts référencés | La prose LLM n'est pas une preuve. |
| Décision de migration | `ADR_TYPESCRIPT_MIGRATION.md` | Elle fixe la cible et les décisions Stage 0B appliquées au Stage 1. |

## Autorités futures

| Domaine | Source d'autorité cible | Statut |
|---|---|---|
| Code maintenu d'un module migré | Source TypeScript stricte `.ts` | Devient canonique uniquement après bascule explicite. |
| JavaScript runtime | Artefact ESM généré à partir des sources TypeScript | Exécutable et distribuable, mais non édité comme source. |
| Règles de compilation | `factory/toolchain/tsconfig.json`, `build.mjs` et manifeste npm isolé | Stage 1 : strict, `tsc --noEmit`, esbuild, sans héritage racine/pnpm/Nx. |
| Compatibilité runtime | Node `>=22.12.0` et bundle ESM autonome | Fixé pour le Stage 1. |
| Artefact runtime opérationnel | `factory/runtime/factory-operational.mjs` | Généré, non éditable manuellement; autonomie partielle car run/gates restent injectés depuis legacy. |
| Diagnostics de build | Sourcemap et metafile sous `factory/dist/` | Non runtime, ignorés et régénérables. |
| Assets et imports dynamiques | Règles de packaging Factory | À préciser quand un module candidat en introduira. |

## Règles pendant la coexistence `.mjs` / `.ts`

1. Un module n'a qu'une source d'autorité à un instant donné.
2. Avant sa bascule, le `.mjs` existant reste canonique et exécuté ; un éventuel `.ts` de préparation ne doit pas diverger silencieusement ni être chargé au runtime.
3. Après sa bascule, le `.ts` est canonique ; le JavaScript correspondant est généré et ne reçoit pas de correctif manuel durable.
4. Un import ne doit jamais choisir implicitement entre deux implémentations selon la disponibilité d'un outil ou de `node_modules`.
5. La documentation décrit la bascule, mais ne la réalise pas.

Depuis le Stage 4A, `src/lib/active-case.ts` est l'unique source d'autorité du registre et `runtime/factory-operational.mjs` son unique artefact runtime. Le prototype `lib/active-case.mjs`, l'entrypoint et le bundle de contrat dédiés ont été supprimés après bascule de tous les importeurs.

## Conflits et résolution

### Documentation contre code actuel

Le code exécuté et ses effets observables priment. La documentation doit être alignée sans changer le comportement dans un stage documentaire.

### `.mjs` actuel contre `.ts` préparatoire

Tant que la bascule n'est pas déclarée et validée, le `.mjs` prime. Une divergence est un défaut de migration, pas une invitation à sélectionner la version la plus récente.

### Source TypeScript basculée contre artefact généré

La source TypeScript prime. L'artefact doit être régénéré par la toolchain isolée ; il ne doit pas être corrigé à la main, sauf rollback temporaire vers un artefact antérieur connu comme sain.

### Factory contre produit mesuré

Pour les règles de la Factory, ses sources et contrats priment. Pour le résultat d'un oracle, les faits mesurés dans le produit priment sur toute déclaration d'agent. Le produit ne peut pas redéfinir les dépendances de démarrage de la Factory.

### Factory contre AgentOS

AgentOS fait autorité sur l'état de ses cases et son protocole public. La Factory fait autorité sur l'interprétation de ces faits dans son workflow, les préflights, les oracles et le verdict final. Aucun détail interne non contractuel d'AgentOS ne doit devenir une source d'autorité Factory.

## Rollback et autorité

Un rollback restaure une autorité antérieure clairement identifiée : module `.mjs` canonique avant bascule ou artefact ESM précédemment validé après bascule. Il ne crée pas une troisième implémentation et ne réécrit pas les registres historiques.
