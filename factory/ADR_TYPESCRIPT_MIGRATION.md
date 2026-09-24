# ADR — Migration TypeScript de la Factory

- **Statut** : accepté ; Stage 2 par entrypoint/cluster fermé
- **Portée** : Factory uniquement
- **Implémentation** : toolchain isolée et entrypoint TypeScript active-case, sans bascule des consommateurs historiques

## Contexte

La Factory mesure et orchestre le produit Coday/AgentOS. Son indépendance est une propriété de confiance : une panne du workspace, de pnpm, de Nx, d'un compilateur ou de `node_modules` du produit ne doit pas empêcher l'instrument de démarrer et d'établir son verdict.

Les modules actuels sont des sources JavaScript ESM `.mjs` exécutées directement. La migration recherchée doit apporter le typage strict sans coupler l'exécution de la Factory à sa toolchain de développement ni à celle du produit mesuré.

## Décision

Les sources cibles de la Factory seront écrites en **TypeScript strict**. Une **toolchain Factory isolée** les compilera en un **artefact JavaScript ESM autonome au runtime**.

L'artefact livré devra s'exécuter avec Node sans pnpm, Nx, compilateur TypeScript, bundler ni `node_modules`. La construction de cet artefact pourra avoir des dépendances, mais elles appartiendront exclusivement à la toolchain de build de la Factory. Cette toolchain ne devra jamais utiliser la santé, les dépendances installées, les scripts ou le graphe Nx du produit mesuré comme précondition.

L'étude Stage 0B validée fixe pour le Stage 1 minimal : Node `>=22.12.0`, vérification stricte par `tsc --noEmit`, bundle ESM autonome par esbuild, et dépendances npm isolées sous `factory/toolchain`. La configuration n'hérite d'aucun `tsconfig` racine et n'utilise ni pnpm ni Nx.

Au Stage 2, `factory/src/entrypoints/active-case-contract.ts` ferme le cluster autour de `active-case`, importe `../lib/active-case.js` selon NodeNext et exerce son contrat public. Esbuild produit le bundle ESM monofichier versionné `factory/runtime/active-case-contract.mjs`, cible Node 22.12, splitting désactivé et imports `node:*` externes. Le bundle est écrit atomiquement et porte un en-tête GENERATED/DO NOT EDIT. Sourcemap sans `sourcesContent` et metafile restent sous `factory/dist/` comme diagnostics non runtime.

## Frontière Factory / AgentOS

La Factory est l'orchestrateur et l'autorité du verdict : elle choisit les oracles, mesure l'état du checkout, applique les gates et écrit les faits. AgentOS est un système externe d'exécution d'agents, appelé via son contrat réseau. Il ne construit pas la Factory, ne fournit pas ses dépendances runtime et ne décide pas du résultat des oracles.

La Factory peut dépendre du contrat HTTP d'AgentOS pour les phases qui sollicitent un agent. Elle ne peut pas importer le code interne d'AgentOS, dépendre de son build Gradle, ni demander à AgentOS de compiler ou valider la Factory. Une indisponibilité d'AgentOS peut faire échouer une phase qui en a besoin, mais ne doit pas empêcher le runtime Factory autonome de démarrer, d'enregistrer cet échec et d'exécuter les opérations qui n'en dépendent pas.

## Bounded contexts

- **Runtime Factory** : dispatch, workflows, oracles, gates, registre, arrêt gracieux et adaptateurs runtime. Il est livré comme artefact ESM autonome.
- **Toolchain Factory** : vérification TypeScript stricte, compilation et assemblage de l'artefact. Elle est isolée du runtime et du produit.
- **Produit mesuré** : dépôt Coday, workspace pnpm/Nx et AgentOS/Gradle. Il est une cible observée, jamais une dépendance de construction ou de démarrage de la Factory.
- **AgentOS distant** : fournisseur de cases et de tours d'agents derrière une frontière HTTP explicite.
- **Preuves et état opérationnel** : registres JSONL, artefacts de diagnostic, réponses de gates et autres fichiers produits par un run. Ce sont des données runtime, pas des sources compilées.

## Coexistence temporaire `.mjs` / `.ts`

La migration sera incrémentale. Les modules `.mjs` existants restent les sources exécutées et font autorité tant que leur remplacement TypeScript n'est pas explicitement validé et basculé. Les nouveaux `.ts` ne doivent pas être chargés directement au runtime et ne peuvent devenir l'autorité par leur seule présence.

Pour chaque module migré, le changement d'autorité doit être explicite, atomique et réversible. Il faut éviter deux implémentations modifiables en parallèle : tant que le basculement n'a pas eu lieu, le `.mjs` reste canonique ; après basculement, le TypeScript devient canonique et le JavaScript correspondant est un artefact généré, non une seconde source à éditer.

## Premier candidat

`factory/lib/active-case.mjs` est le premier candidat proposé. Sa surface est réduite, son état et ses invariants sont bornés, et ses dépendances sont limitées. Il permet de valider la chaîne source stricte → artefact ESM → exécution autonome avant de migrer des modules réseau ou d'orchestration plus complexes.

Le Stage 1 a porté fidèlement ce module dans `factory/src/lib/active-case.ts`. Le Stage 2 donne autorité TypeScript au nouveau cluster fermé exposé par `factory/src/entrypoints/active-case-contract.ts`, mais ne bascule aucun consommateur historique : `factory/lib/active-case.mjs` reste l'autorité du runtime legacy. Le test `factory/tests/typescript-active-case-runtime.mjs` vérifie contrat, autonomie, relocalisation, observabilité et fraîcheur du bundle après une construction explicitement demandée. Cette étape ne prétend pas supprimer la duplication historique.

## Rollback

Le rollback doit rester possible sans reconstruire le produit mesuré :

1. conserver le chemin runtime `.mjs` actuel tant que le premier artefact TypeScript n'est pas validé ;
2. effectuer les bascules par module ou tranche cohérente, sans migration globale irréversible ;
3. en cas de problème, restaurer le dernier artefact Factory connu comme sain ou revenir au module `.mjs` canonique précédent ;
4. ne jamais corriger un échec en introduisant une dépendance runtime vers la toolchain ou le workspace produit.

Les registres et preuves produits avant le rollback restent des données historiques et ne doivent pas être réécrits.

## Conséquences

Le typage strict devient la cible des sources sans compromettre l'autonomie runtime. Le coût accepté est une séparation explicite entre source, build et artefact, ainsi qu'une période contrôlée de coexistence. Tout choix futur qui impose pnpm, Nx, TypeScript, un bundler ou `node_modules` au runtime contredirait cette ADR.

## Décisions appliquées au Stage 2

- Node minimum : `22.12.0` ;
- vérification : TypeScript strict avec `tsc --noEmit` ;
- assemblage : esbuild vers un bundle ESM autonome ;
- isolation : manifeste, lockfile et `node_modules` propres à `factory/toolchain` ;
- artefact runtime versionné : `factory/runtime/active-case-contract.mjs` ;
- diagnostics : sourcemap externe sans sources embarquées et metafile JSON sous `factory/dist/` ;
- packaging : imports `node:*` externes, splitting désactivé, écriture atomique et en-tête généré ;
- autorité : TypeScript pour le nouveau cluster ; `.mjs` historique pour les consommateurs legacy non basculés.

Le traitement des assets et imports dynamiques sera précisé lorsqu'un module candidat en introduira ; `active-case` n'en contient pas.
