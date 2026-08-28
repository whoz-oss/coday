# factory/lib — Modules de l'orchestrateur

Ce répertoire contient les douze modules qui composent l'orchestrateur factory.
Chaque module a une responsabilité unique et des frontières explicites.
Aucun module n'écrit de prose LLM dans le registre ni ne dépend de l'état de ce qu'il mesure.

---

## Vue d'ensemble — composition dans le workflow

```
run.mjs (point d'entrée, hors lib/)
  │
  ├── shutdown.mjs          ← enregistre SIGTERM avant tout
  │     └── active-case.mjs ← lit les cases actifs
  │     └── agentos.mjs     ← tue les cases actifs
  │     └── registry.mjs    ← écrit run_end en fail
  │
  └── workflow (ex. us-loop.mjs, hors lib/)
        │
        ├── registry.mjs          ← createRun / startPhase / passPhase / failPhase / endRun
        ├── jira.mjs              ← fetchJiraTicket (phase fetch-ticket)
        ├── plan.mjs              ← parsePlan / checkPlanFiles / compareClaims
        ├── domains.mjs           ← définit les oracles back/front
        ├── oracle-command.mjs    ← buildOracleCommand (résolution projets Nx)
        ├── oracle.mjs            ← runCommand / snapshotDiff / diffSince / countTaskOutcomes
        ├── agentos.mjs           ← createCase / runAgentTurn / preflightAgent / preflightWorkspace
        │     └── active-case.mjs ← setActiveCaseId / clearActiveCaseId
        │
        └── (boucle de revue)
              ├── review.mjs              ← parseReviewResult / aggregateReviews / toReviewFacts
              ├── review-engine.mjs       ← runReview (parallèle, préflight, agrégation)
              └── review-agentos-adapter.mjs ← makeReviewAgentOps (câblage AgentOS réel)
```

Séquence typique d'une phase agent dans un workflow :

1. `registry.startPhase` — écrit immédiatement `status: fail` dans le JSONL.
2. `oracle.snapshotDiff` — empreintes SHA-256 des fichiers git modifiés/non trackés.
3. `agentos.runAgentTurn` — poste un message, poll jusqu'à quiescence du case.
4. `oracle.diffSince` — détecte les fichiers dont le contenu a changé depuis le snapshot.
5. `oracle-command.buildOracleCommand` — construit la commande effective (projets Nx directs si `filesArg`).
6. `oracle.runCommand` — exécute la commande, verdict = `exitCode === 0`.
7. `registry.passPhase` ou `registry.failPhase` — clôture la phase avec les faits.

---

## Modules

### active-case.mjs

Registre en mémoire des cases AgentOS en cours de polling dans le processus courant.

Existe pour briser la dépendance circulaire `agentos → shutdown → agentos`.
Sans ce module, `shutdown.mjs` devrait importer `agentos.mjs`, qui importe
lui-même `active-case.mjs` pour écrire, et `shutdown.mjs` pour lire — cycle impossible.

**Exports principaux**

| Fonction | Rôle |
|---|---|
| `registerActiveCase(caseId, label?)` | Enregistre un case actif. Idempotent. |
| `unregisterActiveCase(caseId)` | Retire un case du registre. Idempotent. |
| `getActiveCaseIds()` | Retourne un snapshot (copie) des caseIds actifs. |
| `setActiveCaseId(caseId)` | API legacy — délègue vers `registerActiveCase`. |
| `clearActiveCaseId(caseId)` | API legacy — délègue vers `unregisterActiveCase`. |
| `getActiveCaseId()` | API legacy — retourne le premier case actif ou null. |

**Invariants**

- Aucune dépendance externe : uniquement `node:fs` pour l'observabilité de test.
- Si `FACTORY_ACTIVE_CASE_FILE` est défini, écrit le caseId dans ce fichier à l'enregistrement et le supprime quand le registre est vide. Usage exclusif des tests d'intégration (`test-shutdown-operational.sh`). Les erreurs I/O sont silencieusement ignorées.

---

### agentos.mjs

Client REST pour l'API AgentOS. Seul module autorisé à appeler le serveur AgentOS.

**Exports principaux**

| Fonction | Rôle |
|---|---|
| `createCase(namespaceId, title)` | Crée un case racine. Ne pas passer `parentCaseId` — non supporté par ce endpoint. |
| `postMessage(caseId, content)` | Poste un message dans un case. Asynchrone côté serveur : le case n'est pas encore RUNNING au retour. |
| `getCase(caseId)` | Récupère l'état d'un case. |
| `listEvents(caseId)` | Liste les événements dans l'ordre chronologique garanti par le backend (tri Cypher). |
| `killCase(caseId)` | Tue un case en cours d'exécution. |
| `listAgents(namespaceId)` | Liste les agents d'un namespace. |
| `listIntegrations(namespaceId)` | Liste les IntegrationConfig persistées en base (pas celles chargées depuis le disque). |
| `preflightAgent(namespaceId, agentName)` | Vérifie : agent existe, activé, `subAgents` vide. Fail-closed. |
| `preflightWorkspace(namespaceId, agent, repoRoot)` | Vérifie que le `rootPath` FILE_ACCESS de l'agent est exactement la racine du dépôt de l'orchestrateur. Fail-closed. |
| `runAgentTurn(caseId, agentName, brief, opts?)` | Poste un message et poll jusqu'à quiescence. Retourne un objet structuré avec statut, compteurs, modèles LLM utilisés. |

**Invariants critiques**

- Le signal de fin de tour est `CaseStatusEvent` avec statut `IDLE | KILLED | ERROR`, pas `AgentFinishedEvent`. Ce dernier est émis plusieurs fois par tour (redirections, file de commandes, queryUser) et ne signifie pas la quiescence.
- `runAgentTurn` attend deux transitions successives : RUNNING d'abord, puis la quiescence après le RUNNING le plus récent. Un IDLE intermédiaire suivi d'un nouveau RUNNING n'est pas final.
- Les timestamps des événements ne sont pas utilisés pour l'ordre ni le découpage — seuls l'id de l'événement et l'ordre du tableau retourné par le backend font autorité.
- Sur timeout, le case est tué. Un agent qui continue d'écrire après le verdict de l'orchestrateur peut corrompre la phase suivante.
- `setActiveCaseId` est appelé avant tout appel réseau dans `runAgentTurn`, et `clearActiveCaseId` dans le bloc `finally` — garantit que SIGTERM peut tuer le case quel que soit le chemin de sortie.
- Dette technique : l'orchestrateur s'authentifie sous une identité humaine (`FACTORY_USER`). Un compte de service dédié est nécessaire dès qu'AgentOS le supportera.

---

### domains.mjs

Définition des oracles par domaine de compilation (`back`, `front`).

Les commandes sont versionnées dans ce fichier. Aucun agent ne les choisit ni ne rapporte leur résultat. C'est le contrat de l'orchestrateur.

**Exports principaux**

| Export | Type | Contenu |
|---|---|---|
| `domains` | `Record<string, Domain>` | `back` (1 oracle Gradle) et `front` (2 oracles Nx : `types` puis `tests`). |

**Structure d'un oracle**

```js
{ name: string, command: string, cwd: string, filesArg?: boolean }
```

`filesArg: true` sur l'oracle `tests` signale à `buildOracleCommand` de résoudre les projets Nx propriétaires des fichiers modifiés plutôt que d'utiliser la commande telle quelle.

**Variables d'environnement de surcharge**

| Variable | Oracle concerné |
|---|---|
| `FACTORY_COMMAND_BACK` | `back.build` |
| `FACTORY_CWD_BACK` | `back.build` |
| `FACTORY_COMMAND_FRONT` | `front.tests` (comportement historique) |
| `FACTORY_COMMAND_FRONT_TYPES` | `front.types` |
| `FACTORY_CWD_FRONT` | Les deux oracles front |
| `FACTORY_ROOT` | Racine du dépôt cible (défaut : deux niveaux au-dessus de `factory/lib/`) |

**Invariants**

- Le domaine `front` a deux oracles distincts (`types` puis `tests`) car le typage et le comportement sont des propriétés orthogonales. Un `&&` masquerait la mesure de la seconde si la première échoue.
- L'oracle `types` a un périmètre fixe (4 projets) insensible à la dérive de `nx affected`.
- L'oracle `build` Gradle utilise `--rerun-tasks --console=plain` : `--rerun-tasks` garantit l'exécution effective (pas de cache), `--console=plain` permet à `countTaskOutcomes` de parser la sortie.

---

### jira.mjs

Utilitaires Jira : extraction d'identifiant, parsing ADF, fetch de ticket.

Extrait de `us-loop.mjs` pour deux raisons : le dashboard (`dashboard/server.mjs`) a besoin de `fetchJiraTicket` sans importer un workflow, et ces fonctions peuvent être testées sans appel réseau.

**Exports principaux**

| Fonction | Rôle |
|---|---|
| `extractTicketId(input)` | Extrait un identifiant Jira depuis un identifiant brut ou une URL. Retourne null si invalide. |
| `extractAdfText(node)` | Aplatit un arbre Atlassian Document Format en texte brut avec sauts de ligne après les blocs. |
| `fetchJiraTicket(ticketId, jiraBaseUrl, jiraEmail, jiraApiToken)` | Appelle `GET /rest/api/3/issue/<ticketId>` en Basic auth. Retourne `{ ticketContent, summary, fieldCount }`. |

**Invariants**

- Aucune dépendance externe : `fetch` global et `Buffer` Node uniquement.
- `fetchJiraTicket` cherche les acceptance criteria sur les clés contenant `acceptance` (insensible à la casse) puis sur `customfield_10016`. Premier champ non vide trouvé, arrêt.
- En cas d'erreur HTTP, lève une `Error` avec le statut et les 200 premiers caractères du corps.

---

### oracle.mjs

Exécution des commandes oracle et mesure de l'état Git.

Le verdict est `exitCode === 0`, rien d'autre. Le contenu de la sortie n'est jamais interprété pour décider.

**Exports principaux**

| Fonction | Rôle |
|---|---|
| `runCommand(command, { cwd?, timeoutMs? })` | Exécute une commande shell via `spawnSync`. Retourne `{ exitCode, stdout, stderr, durationMs, timedOut }`. |
| `countTaskOutcomes(output)` | Compte les issues de tâches Gradle et Nx dans la sortie. Ce n'est pas un verdict — c'est un fait enregistré à côté. |
| `snapshotDiff(cwd)` | Retourne `{ modified: Map<path, sha256>, untracked: Map<path, sha256> }` pour l'état git courant. |
| `diffSince(before, cwd)` | Retourne les chemins dont le contenu SHA-256 a changé depuis un snapshot. |

**Invariants**

- `snapshotDiff` et `diffSince` utilisent des empreintes SHA-256 du contenu, pas des noms de fichiers ni des compteurs de lignes. Un remplacement ligne pour ligne est ainsi détecté.
- Git sert uniquement à établir la liste des fichiers à surveiller (`git diff HEAD --name-only`, `git ls-files --others`). La détection du changement vient du contenu.
- `countTaskOutcomes` gère deux grammaires : Gradle (`> Task :chemin [MARQUEUR]`) et Nx (`> nx run projet:cible [existing outputs match the cache, left as is]`). Le marqueur de cache Nx est sur la même ligne que `> nx run`, pas sur la suivante. Une vérification croisée par la ligne de synthèse Nx est effectuée.
- La sortie est tronquée à 100 000 caractères avant tout traitement.

---

### oracle-command.mjs

Construction de la commande effective d'un oracle à partir des fichiers modifiés.

Séparé de `oracle.mjs` (stabilisé, interdit de modification) et de `domains.mjs` (données uniquement).

**Exports principaux**

| Fonction | Rôle |
|---|---|
| `resolveOwnerProjects(files, repoRoot)` | Pour chaque fichier, remonte les dossiers parents jusqu'au premier `project.json` et lit son champ `name`. Retourne la liste dédupliquée des projets Nx propriétaires. |
| `buildOracleCommand(oracle, files, repoRoot)` | Construit la commande effective. |

**Logique de `buildOracleCommand`**

- Oracle sans `filesArg` : commande inchangée (périmètre fixe, cas de `types` et `build`).
- Oracle avec `filesArg: true` et liste vide : commande template retournée (la garde `wroteNothing` arrête normalement avant).
- Oracle avec `filesArg: true` et fichiers non vides : construit `pnpm nx run-many --target=<cible> --projects=proj1,proj2 --skip-nx-cache`. La cible est extraite de la commande template via `-t <val>` ou `--target=<val>`.

**Invariants**

- `--skip-nx-cache` est obligatoire : le cache Nx est partagé avec l'environnement de développement.
- Périmètre limité aux projets propriétaires directs (pas la clôture transitive). Les dépendants ont leurs propres tests en CI.
- Si aucun projet n'est trouvé ou si la cible ne peut pas être extraite, la commande template est retournée avec un avertissement console.

---

### plan.mjs

Parsing et validation des plans d'analyste, et comparaison plan/réalité.

Pas d'appel réseau, pas d'état mutable. Une seule fonction touche le système de fichiers en lecture seule : `checkPlanFiles` via `existsSync`.

**Exports principaux**

| Fonction | Rôle |
|---|---|
| `extractJsonFragment(text)` | Extrait le premier bloc JSON d'un texte markdown (3 passes : fence json, fence plain, accolade équilibrée). |
| `isSafePath(p)` | Retourne `false` si le chemin est absolu ou contient `..`. |
| `parsePlan(agentMessage)` | Parse et valide un plan depuis la réponse de l'analyste. Retourne `{ ok, plan }` ou `{ ok, error }`. |
| `checkPlanFiles(files, repoRoot)` | Vérifie que chaque fichier du plan existe sur disque. Retourne `{ plannedFiles, missingFiles, fileCount }`. |
| `compareClaims(plannedFiles, actualModified, actualUntracked)` | Compare les fichiers annoncés aux fichiers réellement modifiés. Retourne `{ plannedFiles, actualFiles, unplannedFiles, untouchedPlannedFiles, claimsMatch }`. |

**Structure d'un plan valide**

```json
{
  "files": ["chemin/relatif/un.ts"],
  "doneWhen": "critère de terminaison vérifiable",
  "steps": ["étape 1"]
}
```

**Invariants**

- `files` : requis, non vide, chemins relatifs à la racine du dépôt uniquement.
- `doneWhen` : requis, non vide.
- `steps` : optionnel.
- `checkPlanFiles` ne couvre pas la création de fichiers neufs (`existsSync` retourne false sur un chemin inexistant intentionnel).
- Un écart entre plan et réalité (`compareClaims`) ne fait pas échouer le run — c'est un fait à porter dans le registre.

---

### registry.mjs

Registre JSONL des runs de l'orchestrateur. Un fichier par run, une ligne JSON par événement, en append pur. Le fichier n'est jamais réécrit après création.

**Exports principaux**

| Fonction | Rôle |
|---|---|
| `createRun(workflowName)` | Crée le fichier JSONL et écrit `run_start`. Publie le run courant dans `_currentRun`. |
| `startPhase(run, name, kind)` | Écrit immédiatement une ligne `phase` avec `status: fail`. |
| `passPhase(phase, facts?)` | Écrit une ligne `phase_end` avec `status: pass`. |
| `failPhase(phase, facts?)` | Écrit une ligne `phase_end` avec `status: fail`. |
| `endRun(run, status, facts?)` | Écrit la ligne finale `run_end`. |
| `getCurrentRun()` | Retourne le run courant ou null. Utilisé par `shutdown.mjs`. |

**Invariants**

1. **Statut `fail` par défaut.** `startPhase` écrit `fail` immédiatement. Si l'orchestrateur plante avant `passPhase`, le registre reste honnête.
2. **Aucune prose LLM.** Seuls des faits sont écrits : noms, statuts, durées, fichiers modifiés, codes de sortie, compteurs.
3. **Champs non réinscriptibles.** Les faits de l'appelant sont placés sous la clé `facts`, jamais à la racine. `kind`, `name`, `status`, `durationMs` ne peuvent pas être écrasés.
4. **Append pur.** `appendFileSync` à chaque écriture. Jamais de réécriture.

Les fichiers sont créés dans `factory/runs/`. Le runId est de la forme `20240115T143022Z-a3f7` : tri alphabétique = tri chronologique.

---

### review.mjs

Fondation de la boucle de revue : parsing, agrégation et projection JSONL. Aucun LLM appelé, aucun accès disque, aucun appel réseau.

**Exports principaux**

| Fonction | Rôle |
|---|---|
| `parseReviewResult(raw, config)` | Valide et normalise la sortie structurée d'un reviewer. Retourne `ParseOutcome`. |
| `aggregateReviews(outcomes, config)` | Agrège plusieurs `ParseOutcome` en un `AggregateResult` déterministe. |
| `toReviewFacts(aggregate, revisionMeta)` | Projette un `AggregateResult` en un objet plat sûr pour `passPhase`/`failPhase`. |

**Politique d'agrégation**

1. Un `ParseOutcome` invalide → verdict `invalid-output` immédiat, pas de dégradation silencieuse.
2. Finding `blocking` sur un axe `veto: true` → verdict `reject`.
3. Finding `blocking` ou `major` hors véto → verdict `request-changes`.
4. Tous findings `minor`/`info` → verdict `approve`.
5. Verdict final : le plus sévère parmi tous les reviewers (`reject > request-changes > approve`).
6. Score agrégé = moyenne pondérée par axe. Indicateur de risque uniquement, jamais un override de verdict.

**Invariants**

- La prose LLM (`title`, `evidence`, `recommendation` des findings) est validée mais non conservée dans `Finding` et jamais dans le registre.
- Les champs `revision`, `attempt`, `maxRevisions`, `maxAttempts` sont interdits dans l'output d'un reviewer. Leur présence produit un échec `FORBIDDEN_FIELD`.
- `toReviewFacts` est la seule fonction autorisant la construction de facts JSONL pour la revue. Elle n'expose aucune prose.
- `artifactDescriptor.content` est supprimé de l'output du reviewer même s'il est présent.

---

### review-engine.mjs

Moteur d'exécution de la boucle de revue. Lance les reviewers en parallèle via AgentOS, collecte et agrège leurs sorties.

**Export principal**

| Fonction | Rôle |
|---|---|
| `runReview(params)` | Lance tous les reviewers en parallèle, agrège les résultats, retourne `ReviewExecutionResult`. |

**Paramètres de `runReview`**

`namespaceId`, `subject` (SubjectDescriptor), `reviewers` (ReviewerDef[]), `config` (ReviewConfig), `revisionMeta`, `agentOps` (AgentOps injecté), `brief?`, `timeoutMs?`, `startTimeoutMs?`.

**Invariants**

- Les fonctions AgentOS sont injectées via `agentOps` — le moteur est testable sans réseau.
- Les caseIds des reviewers sont stockés dans un `Map` local, pas dans `active-case.mjs` (réservé au tour d'éditeur séquentiel).
- Sur échec d'un reviewer, tous les cases encore actifs sont tués en best-effort avant de retourner.
- L'ordre des résultats est déterministe : celui de l'entrée `reviewers`, indépendamment de l'ordre de complétion AgentOS.
- Préflight de chaque reviewer avant lancement : agent existe, activé, `subAgents` vide, aucune intégration mutante (BASH, MCP_STDIO, WEBHOOK, FILE_WRITE, GIT), toutes les FILE_ACCESS en `readOnly: true`.
- Le champ `content` du `SubjectDescriptor` est inclus dans le brief mais n'apparaît jamais dans `ReviewExecutionResult.facts`.
- Si le reviewer inclut un `artifactDescriptor.hash` différent du hash fourni, échec immédiat `ARTIFACT_HASH_MISMATCH`.
- `ReviewExecutionResult.rawOutputs` contient la prose brute des reviewers pour affichage humain uniquement — jamais dans les faits.

---

### review-agentos-adapter.mjs

Adaptateur qui câble les fonctions de `agentos.mjs` vers l'interface `AgentOps` attendue par `review-engine.mjs`.

**Export principal**

| Fonction | Rôle |
|---|---|
| `makeReviewAgentOps(namespaceId)` | Retourne un `AgentOps` concret câblé sur `agentos.mjs`. |

Le `namespaceId` est capturé en closure et passé aux opérations qui en ont besoin (`createCase`, `preflightAgent`, `listIntegrations`). `runAgentTurn` et `killCase` prennent un `caseId` directement.

**Invariants**

- Seul point d'import de `agentos.mjs` pour la revue.
- Aucune logique métier : câblage pur.
- Les tests de `review-engine.mjs` continuent d'utiliser des fakes injectables, indépendamment de cet adaptateur.

---

### shutdown.mjs

Gestionnaire d'arrêt gracieux sur SIGTERM.

**Exports principaux**

| Fonction | Rôle |
|---|---|
| `initShutdownHandler({ log? })` | Enregistre le handler SIGTERM. À appeler une seule fois depuis `run.mjs` avant le lancement du workflow. |
| `markCompleted()` | Désarme le handler. À appeler depuis `run.mjs` après le retour normal du workflow. |

**Séquence sur SIGTERM**

1. Snapshot des caseIds actifs via `active-case.getActiveCaseIds()`.
2. Kill de chaque case en best-effort via `agentos.killCase`, tous en parallèle (`Promise.allSettled`).
3. Écriture de `run_end` avec `status: fail`, `checkoutMayBeIntermediate: true` (si des cases étaient actifs), `terminatedBySignal: 'SIGTERM'` — via `registry.endRun`.
4. `process.exit(1)`.

**Invariants**

- `_shutdownInitiated` est positionné avant tout appel async : un second signal est ignoré.
- `_completed` désarme le handler : si le signal arrive après la complétion normale, `endRun` n'est pas appelé une deuxième fois.
- Un échec de kill sur un case ne bloque pas les autres kills ni l'écriture de `run_end`.
- SIGINT n'est pas géré — uniquement SIGTERM.
- `checkoutMayBeIntermediate: true` est durable (fichier JSONL append-only) : toute analyse ultérieure du checkout doit en tenir compte.

---

## Dépendances entre modules

```
active-case  <── agentos
                  ^
shutdown ─────────┤
  └── registry    │
                  │
domains ─────── (workflow)
oracle-command ──┘
oracle
plan
jira
registry

review <─── review-engine <─── review-agentos-adapter ─> agentos
```

`active-case` n'a aucune dépendance interne — c'est le seul moyen de briser le cycle `agentos <-> shutdown`.
