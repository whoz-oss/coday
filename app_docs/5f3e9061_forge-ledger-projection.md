# Forge Ledger : projection générique en lecture seule

Cette étape ajoute une cartographie et un adaptateur pur pour projeter le journal Forge Ledger vers le vocabulaire générique Coday, sans déplacer l’autorité : le ledger Forge reste la source primaire et aucun chemin d’écriture ni store générique n’est utilisé.

## Ce qui a changé

- `docs/forge-ledger-mapping.md` décrit les événements Forge couverts, y compris les événements de démarrage effectivement émis (`agent_execution_started`, `story_edit_started`, `story_oracles_started`) en plus des événements listés dans le périmètre initial. Pour chaque événement, le document précise les transitions V3, les preuves V5 et les interactions humaines V5 déduites.
- La cartographie recense explicitement les champs qui ne peuvent pas entrer dans la whitelist `FACT_KEYS` actuelle (`policyVersion`, `evidenceSetHash`, `requiredDecision`, états d’exécution, listes de fichiers, hashes, etc.). Elle propose des options pour l’étape 2 sans les appliquer et consigne les pertes d’identité, les différences `attempt`/`revision`, les limites de portée story/epic et les ambiguïtés de statut.
- `factory/src/domain/forge-bmad/forge-ledger-projection.ts` expose `projectForgeLedgerToGeneric(events)` ainsi que les types de projection. Le module ne fait aucun I/O et ne modifie pas ses entrées. Il produit :
  - des `WorkflowEvidenceInput` pour les résultats d’agent, artefacts, résultats d’oracle et décisions humaines ; chaque candidat est contrôlé par `validateWorkflowEvidenceInput`, avec les candidats invalides conservés et annotés ;
  - des ouvertures/réponses d’interactions humaines pour G1 ;
  - des transitions `ready`, `running`, `waiting_human`, `completed`, `failed` ou `blocked` avec des identifiants de workflow/étape dérivés des run IDs ;
  - `unmappedEvents`, qui signale les événements inconnus et chaque champ non représentable, au lieu de les abandonner silencieusement.
- `factory/tests/test-forge-ledger-projection.mjs` fournit une suite offline framework-free. Elle utilise à la fois un ledger créé par `createEpicRun`/`parseForgeLedger` et un scénario synthétique complet, vérifie les quatre kinds de preuves, la revalidation publique, les interactions et transitions, le signalement des champs hors whitelist, les hashes d’artefact invalides, les événements inconnus, la décision rejetée et la pureté/immutabilité du module.
- `specs/5f3e9061_forge_ledger_projection.md` conserve le plan et les contraintes de cadrage de cette livraison.

## Utilisation et vérification

La projection reçoit des événements déjà parsés :

```ts
const projection = projectForgeLedgerToGeneric(parseForgeLedger(filePath))
```

Elle ne lit pas elle-même le fichier. Consulter `projection.evidences`, `projection.interactions`, `projection.transitions` et `projection.unmappedEvents`; les champs non whitelistés ne sont jamais injectés dans `facts`.

Lancer le test offline avec :

```sh
node factory/tests/test-forge-ledger-projection.mjs
```

Le test utilise le hook de résolution TypeScript du dépôt et retourne explicitement le code 0 si tous les cas passent, 1 sinon. Le document de mapping rappelle également les commandes Nx pour la vérification affectée ; aucune modification de `forge-ledger.mjs`, `forge-ledger.ts`, de `FACT_KEYS`, des migrations/adapters SQL, du bundle runtime ou de `agentos/**` n’est incluse dans cette étape.
