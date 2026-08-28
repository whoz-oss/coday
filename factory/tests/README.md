# factory/tests/

Scripts de test de l'orchestrateur `factory/`.

## Principe général

Tous les tests sont des fichiers `.mjs` exécutés directement par `node`, sans
framework de test extérieur. Ils suivent la même convention : code de sortie
`0` si tous les cas passent, `1` si au moins un échoue.

Les tests `.mjs` sont **hors-ligne** : aucun appel réseau, aucun AgentOS, aucun
compilateur. Ils peuvent être exécutés à tout moment sans prérequis.

`test-shutdown-operational.sh` est le seul test **opérationnel** : il nécessite
un AgentOS actif et ne doit pas être inclus dans une exécution ordinaire hors-ligne.

---

## Tests hors-ligne (`.mjs`)

### `test-f7.mjs`

**Catégorie** : unité (pur, sans I/O)

Vérifie la correction F7 : la logique de quiescence dans `runAgentTurn` doit
s'ancrer sur le *dernier* événement `RUNNING`, pas le premier. Simule des
séquences d'événements représentant des scénarios de redirection multi-agents
(IDLE intermédiaires, ERROR/KILLED sur le second tour, etc.).

Aucune dépendance. Les fonctions testées sont redéfinies localement (pas
d'export disponible dans `agentos.mjs`).

```bash
node factory/tests/test-f7.mjs
```

---

### `test-dashboard-chronology.mjs`

**Catégorie** : statique (lecture de fichiers locaux)

Vérifie la chronologie du Gantt du dashboard : ordre des phases, positions
relatives, rendu HTML des barres compactes, contraintes CSS de mise en page.

**Dépendances** :
- `factory/dashboard/server.mjs` (import ES module)
- `factory/dashboard/index.html` (lu via `readFileSync`)
- `factory/runs/20260820T132311Z-5b34.jsonl` (fixture de run réelle)

Ces fichiers doivent exister sur le disque. Aucun réseau requis.

```bash
node factory/tests/test-dashboard-chronology.mjs
```

---

### `test-oracle.mjs`

**Catégorie** : unité (pur, sans I/O)

Vérifie `countTaskOutcomes` dans `factory/lib/oracle.mjs`. Couvre les sorties
Nx (avec cache, sans cache, colorée ANSI, multi-projets) et Gradle (fabriquée).
Inclut les cas A9/F32 : `summaryFound` via la ligne de succès Nx, `countMismatch`,
`summaryAbsenceReason`.

```bash
node factory/tests/test-oracle.mjs
```

---

### `test-jira.mjs`

**Catégorie** : unité (pur, sans I/O)

Vérifie les deux fonctions pures de `factory/lib/jira.mjs` :
- `extractTicketId` : identifiant nu, URL `/browse/`, minuscules, cas invalides.
- `extractAdfText` : parcours récursif ADF (Atlassian Document Format).

`fetchJiraTicket` n'est pas testée ici (appel réseau réel).

```bash
node factory/tests/test-jira.mjs
```

---

### `test-review.mjs`

**Catégorie** : unité (pur, sans I/O)

Vérifie les trois fonctions de `factory/lib/review.mjs` :
- `parseReviewResult` : validation stricte du JSON produit par un reviewer
  (champs obligatoires, codes d'erreur, champs interdits, étanchéité prose).
- `aggregateReviews` : verdict agrégé, politique de véto, scores pondérés.
- `toReviewFacts` : schéma des faits JSONL, invariants de registre.

```bash
node factory/tests/test-review.mjs
```

---

### `test-review-engine.mjs`

**Catégorie** : unité (fakes injectés, sans I/O réseau)

Vérifie `runReview` dans `factory/lib/review-engine.mjs`. Toutes les fonctions
AgentOS (`createCase`, `runAgentTurn`, `killCase`, `preflightAgent`,
`listIntegrations`) sont injectées via des fakes.

Couvre : validation d'entrée, préflight (BASH/MCP/FILE_ACCESS), exécution
nominale, parallélisme, statuts d'agent (timeout, pending, error), parsing,
hash mismatch, cleanup, étanchéité des faits, verdict agrégé.

```bash
node factory/tests/test-review-engine.mjs
```

---

### `test-review-adapter.mjs`

**Catégorie** : unité (fakes injectés, sans I/O réseau)

Vérifie `makeReviewAgentOps` dans `factory/lib/review-agentos-adapter.mjs`.
Contrôle le câblage : le `namespaceId` de la closure prime sur l'argument,
les arguments sont transmis fidèlement, les valeurs de retour sont transparentes.
Inclut un test d'intégration avec `runReview` via adaptateur injecté.

```bash
node factory/tests/test-review-adapter.mjs
```

---

### `test-run-dispatch.mjs`

**Catégorie** : statique (vérification de fichiers locaux)

Vérifie la table de dispatch de `run.mjs` sans exécuter aucun workflow :
commandes catégorisées (`workflow:us-loop`, `diagnostic:agentos-smoke`…),
alias hérités avec et sans flag `deprecated`, existence des modules sur
le système de fichiers, commandes inconnues.

```bash
node factory/tests/test-run-dispatch.mjs
```

---

### `test-shutdown.mjs`

**Catégorie** : unité (lecture/écriture dans `/tmp`, sans réseau)

Vérifie la terminaison gracieuse (A5/F24) :
- `active-case.mjs` : registre multi-case, idempotence, API legacy.
- `registry.mjs` : `getCurrentRun`, `endRun` avec facts.
- Logique de shutdown multi-case simulée (fakes, pas de vrais signaux).
- Cycle de vie A5 de `runAgentTurn` : publication du case avant `postMessage`,
  visibilité depuis le handler SIGTERM, nettoyage sur échec.

Écrit des fichiers JSONL temporaires dans `factory/runs/` (nettoyés
automatiquement via `try/finally`).

```bash
node factory/tests/test-shutdown.mjs
```

---

### `test-us-loop.mjs`

**Catégorie** : unité (lecture/écriture dans `/tmp`, sans réseau)

Vérifie les fonctions pures de `factory/lib/plan.mjs` et
`factory/lib/oracle-command.mjs` :
- `extractJsonFragment`, `parsePlan` : extraction et validation du plan JSON.
- `compareClaims` : comparaison fichiers planifiés vs fichiers réellement modifiés.
- `checkPlanFiles` : existence des fichiers du plan sur le disque.
- `resolveOwnerProjects`, `buildOracleCommand` : résolution des projets Nx
  propriétaires et construction de la commande oracle ciblée.

Crée une arborescence temporaire dans `/tmp` pour tester la résolution de
`project.json` par remontée de dossiers.

```bash
node factory/tests/test-us-loop.mjs
```

---

## Test opérationnel

### `test-shutdown-operational.sh`

**Catégorie** : intégration opérationnelle (AgentOS requis)

> **Ce test NE doit PAS être inclus dans une exécution hors-ligne ordinaire.**
> Il nécessite un AgentOS actif et accessible, un namespace valide, et un agent
> configuré avec `FILE_ACCESS` pointant sur ce dépôt.

Automate le scénario SIGTERM complet contre une instance AgentOS réelle :
1. Lance `fix-loop` en arrière-plan.
2. Attend que le case AgentOS soit `RUNNING`.
3. Envoie `SIGTERM` au processus `node`.
4. Vérifie 7 assertions (code de sortie, `run_end` unique, `status=fail`,
   `terminatedBySignal=SIGTERM`, `checkoutMayBeIntermediate=true`,
   case AgentOS `KILLED`, stabilité de l'arbre de travail).

**Prérequis** :
- AgentOS actif (`AGENTOS_URL`, défaut `http://localhost:8124`)
- `FACTORY_NAMESPACE_ID` : namespace contenant `FACTORY_AGENT`
- `FACTORY_AGENT` : agent actif, sans `subAgents`, avec `FILE_ACCESS` sur ce dépôt
- `python3`, `node`, `git`, `curl` dans le `PATH`

```bash
FACTORY_NAMESPACE_ID=<id> FACTORY_AGENT=<nom> \
  bash factory/tests/test-shutdown-operational.sh
```

---

## Lancer tous les tests hors-ligne

```bash
for f in factory/tests/test-*.mjs; do
  echo "--- $f ---"
  node "$f"
done
```
