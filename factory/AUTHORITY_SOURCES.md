# Sources d'autorité de la Factory

Ce document distingue ce qui fait autorité aujourd'hui, pendant la migration et dans l'architecture cible. Il évite qu'un fichier généré, une documentation ou deux implémentations coexistantes soient modifiés comme des sources concurrentes.

## Autorités actuelles

| Domaine | Source d'autorité actuelle | Notes |
|---|---|---|
| Comportement runtime | Fichiers `.mjs` exécutés sous `factory/` | Ils restent canoniques pendant le Stage 0A. |
| Contrats et invariants des modules | Comportement des `.mjs`, complété par `factory/lib/README.md` | En cas de conflit, le runtime observé prime ; le conflit documentaire doit être corrigé. |
| Dispatch et workflows disponibles | `factory/run.mjs` et modules référencés | La structure illustrative d'un README n'est pas exhaustive. |
| Commandes oracle | Modules de domaine/oracle concernés | Ni l'agent ni sa prose ne peuvent remplacer cette autorité. |
| Verdict oracle | Code de sortie du processus lancé par la Factory | L'interprétation textuelle n'est pas un verdict. |
| État du checkout mesuré | Système de fichiers et Git observés par les modules de mesure | Les affirmations d'un agent ne font pas autorité. |
| État et événements AgentOS | API AgentOS derrière l'adaptateur Factory | L'ordre et les statuts suivent le contrat backend documenté. |
| Faits d'un run | Registre JSONL append-only et artefacts référencés | La prose LLM n'est pas une preuve. |
| Décision de migration | `ADR_TYPESCRIPT_MIGRATION.md` | Elle fixe la cible, pas les outils du Stage 0B. |

## Autorités futures

| Domaine | Source d'autorité cible | Statut |
|---|---|---|
| Code maintenu d'un module migré | Source TypeScript stricte `.ts` | Devient canonique uniquement après bascule explicite. |
| JavaScript runtime | Artefact ESM généré à partir des sources TypeScript | Exécutable et distribuable, mais non édité comme source. |
| Règles de compilation | Configuration isolée de la toolchain Factory | À définir au Stage 0B ; extérieure au produit mesuré. |
| Compatibilité runtime | Contrat Node minimum et politique d'artefact | À décider au Stage 0B. |
| Assets, sourcemaps, imports dynamiques | Règles de packaging Factory | À décider au Stage 0B. |

## Règles pendant la coexistence `.mjs` / `.ts`

1. Un module n'a qu'une source d'autorité à un instant donné.
2. Avant sa bascule, le `.mjs` existant reste canonique et exécuté ; un éventuel `.ts` de préparation ne doit pas diverger silencieusement ni être chargé au runtime.
3. Après sa bascule, le `.ts` est canonique ; le JavaScript correspondant est généré et ne reçoit pas de correctif manuel durable.
4. Un import ne doit jamais choisir implicitement entre deux implémentations selon la disponibilité d'un outil ou de `node_modules`.
5. La documentation décrit la bascule, mais ne la réalise pas.

Le premier candidat prévu est `factory/lib/active-case.mjs`. Il reste toutefois entièrement sous l'autorité actuelle au Stage 0A.

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
