# Software Factory — architecture et invariants

> Document de référence. Décrit l'orchestrateur tel qu'il fonctionne réellement au
> 28 août 2026, après le run WZ-27053 réussi et la correction A9/F32.
> Les sections marquées **[PLANIFIÉ]** ne sont pas encore implémentées.

---

## 1. Le problème

Un agent qui produit du code produit aussi, gratuitement, un récit de ce qu'il a fait.
Ce récit est cohérent, bien structuré, écrit avec assurance. Et il est parfois faux —
non par malveillance, mais parce qu'un modèle de langage optimise la plausibilité,
pas la véracité.

Une session de travail du 18 août 2026, encadrée par un architecte humain qui relisait,
a produit **quatre affirmations fausses**. Trois venaient d'une consigne imprécise.
Aucune de mauvaise foi. Toutes convaincantes.

| Incident | Ce qui s'est passé |
|---|---|
| `UP-TO-DATE` lu comme un succès | Gradle n'avait rien réexécuté ; le rapport annonçait un build vert |
| « Build vert » sur un arbre cassé | `frontend-test` transpile sans vérifier les types ; trois erreurs TypeScript passaient inaperçues |
| Un résumé pris pour un oracle | Un résumé de vidéo contenait des éléments absents du dépôt réel ; deux critiques portaient sur des défauts inexistants |
| Deux délégations sur le même disque | Un agent a compilé un arbre qu'un autre modifiait, produisant un faux échec assorti d'une explication plausible |

Ces quatre incidents ne sont pas des anecdotes : **ce sont les spécifications**.
Automatiser cette boucle en gardant les mêmes angles morts revient à industrialiser
la production d'affirmations plausibles et fausses.

---

## 2. Architecture générale

### 2.1 Vue d'ensemble

```
run.mjs <workflow>
  ├── lib/registry.mjs       Registre JSONL, fail-closed, append-only
  ├── lib/agentos.mjs        Client REST AgentOS (cases, events, preflight)
  ├── lib/active-case.mjs   Singleton : caseId du tour en cours (A5/F24)
  ├── lib/shutdown.mjs       Handler SIGTERM gracieux
  ├── lib/oracle.mjs         runCommand, countTaskOutcomes, snapshotDiff, diffSince
  ├── lib/domains.mjs        Commandes oracle par domaine (front/back), en dur
  ├── lib/oracle-command.mjs buildOracleCommand, résolution projets propriétaires
  ├── lib/plan.mjs           parsePlan, checkPlanFiles, compareClaims
  ├── lib/jira.mjs           extractTicketId, extractAdfText, fetchJiraTicket
  └── workflows/
       ├── us-loop.mjs          Workflow analyste + éditeur en boucle structurée
       ├── fix-loop.mjs         Boucle éditeur seul (pas d'analyste)
       ├── smoke.mjs            Workflow de test minimal
       └── verify-back.mjs      Vérification Gradle seule
```

L'orchestrateur tourne en Node.js, hors AgentOS. Il est un client REST, pas une
évolution de plateforme. Quelques centaines de lignes suffisent.

### 2.2 Frontière BMAD

L'artefact visible par les agents est **le message posté dans le case AgentOS**,
construit par le workflow en code. Les agents ne voient jamais :

- les commandes oracle (ni leur nom, ni leur résultat)
- le registre JSONL
- le snapshot de diff
- les verdicts de phase

Un agent qui connaît son oracle peut optimiser pour l'oracle. Un agent qui ne le
connaît pas optimise pour la tâche. C'est la frontière BMAD.

**Aucune sortie de LLM n'entre dans le registre.** Le registre contient des faits :
noms de phases, statuts, durées, fichiers touchés, compteurs d'outils, codes de
sortie. Jamais de texte généré.

---

## 3. Workflow us-loop

### 3.1 Séquence implémentée

```
preflight       code  — deux rôles + colocalisation de l'éditeur
  ↓
fetch-ticket    code  — Jira (si FACTORY_TICKET fourni, sinon sauté)
  ↓
┌─ révision R (MAX_REVISION_LOOPS = 2) ────────────────────────────────────────┐
│ analyse-R     agent — analyste (lecture seule), rend un plan JSON              │
│ plan-gate-R   code  — les fichiers cités dans le plan existent-ils ?           │
│                                                                                │
│ ┌─ tentative T (MAX_FIX_LOOPS = 3) ───────────────────────────────┐       │
│ │ edit-R-T     agent — éditeur, reçoit le plan, implémente                 │       │
│ │ verify-types-R-T  code  — oracle de typage (tsc --noEmit)                │       │
│ │ verify-tests-R-T  code  — oracle de comportement (frontend-test)          │       │
│ └─────────────────────────────────────────────────────────────────────────       │
│   échec → tentative T+1 (budget MAX_FIX_LOOPS)                              │
│   épuisement des tentatives → révision R+1 (budget MAX_REVISION_LOOPS)       │
│                                                                                │
│ claims-gate-R code  — diff réel vs fichiers annoncés par le plan            │
└────────────────────────────────────────────────────────────────────────────────┘
```

**Aucune revue n'est exécutée dans us-loop.** La boucle de revue (reviewer agent)
est planifiée mais pas implémentée. Voir §7.

### 3.2 Deux points d'entrée humaine

Le workflow s'arrête et rend le contrôle à l'humain dans deux situations :

**Point 1 — `pending_question`** : l'agent appelle `queryUser`. Le case AgentOS
passe IDLE avec un `QuestionEvent` non répondu. `runAgentTurn` détecte cet état
et retourne `status: 'pending_question'`. La phase courante est marquée `fail` dans
le registre. Le run s'arrête. L'humain répond dans l'UI AgentOS, puis relance
la factory.

**Point 2 — `wroteNothing`** : l'éditeur termine sans avoir modifié aucun fichier.
Cela signifie soit que la tâche est hors périmètre (sortie honorable), soit que le
plan est mal compris. Le run s'arrête. L'humain consulte le case dans AgentOS pour
lire la réponse de l'agent, puis corrige la tâche ou le plan.

Dans les deux cas, l'arrêt est déterministe et tracé dans le registre.

### 3.3 Budgets de boucle

| Constante | Valeur | Sens |
|---|---|---|
| `MAX_FIX_LOOPS` | 3 | Tentatives de l'éditeur avant retour à l'analyste |
| `MAX_REVISION_LOOPS` | 2 | Révisions de l'analyste avant échec du run |
| `JSON_FIX_ATTEMPTS` | 2 | Reformulations de plan JSON invalide |
| `START_TIMEOUT_MS` | 30 s | Budget pour voir le case passer à RUNNING |
| `WORK_TIMEOUT_MS` | 15 min | Budget de travail d'un tour d'agent |
| `ORACLE_TIMEOUT_MS` | 20 min | Budget d'une commande oracle |

### 3.4 Le plan voyage en mémoire

`runAgentTurn` retourne un champ `message` (dernier `MessageEvent` d'agent).
Le workflow lit le plan depuis ce champ et le passe au brief de l'éditeur.
L'analyste étant en `readOnly`, il ne peut pas écrire. Le workflow n'écrit pas
non plus le plan sur disque : `snapshotDiff` le compterait comme une écriture,
la garde `wroteNothing` se déclencherait sur un code inchangé.

### 3.5 Un case neuf par tentative

Réutiliser un case conserverait le récit de l'agent sur ce qu'il croit avoir fait,
qui entrerait en concurrence avec les faits. Avec un case neuf, l'agent reçoit
l'état du dossier et l'erreur brute. Effet de bord utile : un case neuf est
trivialement quiescent, la garde `case_busy` de `runAgentTurn` ne peut pas se
déclencher.

---

## 4. Invariants

### 4.1 Acteur / oracle — un rôle qui juge n'agit pas

**Un oracle est ce qui dit si le travail est bon, et que le travailleur ne peut pas
influencer.**

Trois propriétés le définissent :

- **déterministe** — même entrée, même verdict. Un LLM peut conseiller, il ne peut
  pas arbitrer.
- **indépendant de l'agent** — l'agent ne choisit pas la commande, ne la lance pas,
  ne rapporte pas son résultat.
- **binaire et terminal** — réussi ou échoué, pas de négociation.

Formulation de référence (SSSF) :

> *There is no tester agent, because running a suite is a known command and therefore code.*

Les commandes oracle sont écrites en dur dans `factory/lib/domains.mjs`, versionées
dans le dépôt. Aucun agent ne les choisit, ne les lance, ni ne rapporte leur résultat.

### 4.2 Fail-closed — une phase doit être activement marquée réussie

Chaque phase est écrite immédiatement dans le JSONL avec `status: 'fail'`. Elle ne
devient `pass` que si `passPhase()` est explicitement appelé. Si l'orchestrateur
plante en cours de route, le registre reste honnête. Implémenté dans `registry.mjs`.

### 4.3 Aucune sortie de LLM dans le registre

Le registre contient des faits : noms de phases, statuts, durées, fichiers touchés,
compteurs d'outils, codes de sortie. Jamais le texte produit par un agent. Un
registre contenant du texte généré n'est pas une preuve.

Les champs du registre ne sont pas réinscriptibles. Les faits fournis par l'appelant
sont placés sous la clé `facts`, jamais à la racine : ils ne peuvent pas écraser
`kind`, `name`, `status` ou `durationMs`. Implémenté dans `registry.mjs`.

### 4.4 Colocalisation obligatoire

L'éditeur doit écrire dans l'arbre que l'oracle compile. `preflightWorkspace` vérifie
que le `rootPath` de l'intégration `FILE_ACCESS` de l'éditeur est **exactement** égal
à `FACTORY_ROOT`. L'égalité est exacte, pas une relation d'ascendance. Fail-closed
sur l'invérifiable.

### 4.5 subAgents vide pour les rôles de phase

`DelegationTool.execute()` fait `delegations.map { async { … } }.awaitAll()` —
parallélisme inconditionnel. Un rôle de phase capable de déléguer peut relancer
plusieurs agents en parallèle sur le même disque, à l'insu de l'orchestrateur.
`preflightAgent` vérifie `subAgents === []` et refuse de partir sinon.

### 4.6 Verdict : exitCode === 0, rien d'autre

Le verdict d'un oracle est `exitCode === 0`. Ni la sortie stdout, ni stderr, ni le
décompte de tâches ne participent au verdict. `countTaskOutcomes` est un fait
enregistré à côté, pas un verdict. Un `exitCode: 0` accompagné de `executed: 0`
signale un succès qui ne porte sur rien (garde A8).

### 4.7 Le diff comme oracle de véracité

Snapshot `git diff HEAD --name-only` + `git ls-files --others --exclude-standard`
avant chaque appel d'agent, comparaison après par condensat SHA-256 du contenu
(pas compteurs de lignes — voir F15). Deux usages :

1. **garde `wroteNothing`** — l'éditeur a-t-il modifié quelque chose ?
2. **claims-gate** — ce qu'il déclare avoir modifié correspond-il au diff réel ?

Implémenté dans `oracle.mjs` : `snapshotDiff`, `diffSince`.

### 4.8 Arrêt gracieux sur SIGTERM (A5/F24)

Sur SIGTERM : (1) tuer le case AgentOS actif via `POST /api/cases/{id}/kill`,
(2) écrire `run_end` avec `status: 'fail'` et `facts.checkoutMayBeIntermediate: true`,
(3) quitter avec `exit(1)`. Idémpotence garantie par un flag `_shutdownInitiated`
positionné avant tout appel async. Prouvé opérationnellement (7/7, A5).

Le caseId actif est publié dans `active-case.mjs` dès l'entrée dans `runAgentTurn`,
avant tout appel réseau. Si `FACTORY_ACTIVE_CASE_FILE` est défini, le caseId est
écrit dans ce fichier (observabilité de test uniquement).

---

## 5. Ce qu'AgentOS fournit

### 5.1 Endpoints utilisés

| Endpoint | Usage |
|---|---|
| `POST /api/cases` | Créer un case par phase |
| `POST /api/cases/{id}/messages` | Déclencher un agent (`@nom brief`) |
| `GET /api/cases/{id}` | Statut courant |
| `GET /api/case-events/by-parentId/{id}` | Historique chronologique |
| `POST /api/cases/{id}/kill` | Tuer le case (SIGTERM, timeout) |
| `GET /api/agent-configs/by-parentId/{nsId}` | Préflight agent |
| `GET /api/integration-configs?namespaceId={id}` | Préflight workspace |

Authentification : header `X-External-User-Id` (posé par une passerelle amont).
Dette technique : l'orchestrateur s'authentifie sous une identité humaine
(`FACTORY_USER`). Un compte technique dédié est nécessaire à terme.

### 5.2 Détection de fin de tour

Le signal correct de quiescence est `CaseStatusEvent` avec
`status ∈ {IDLE, KILLED, ERROR}`, pas `AgentFinishedEvent`. Ce dernier est émis
en huit endroits du backend, dont plusieurs sont suivis d'un travail supplémentaire
(redirection, file de commandes, `queryUser`). Conclure sur le premier
`AgentFinishedEvent` produit un verdict pendant que le travail continue.

Deux attentes séquentielles : RUNNING d'abord (le POST est asynchrone côté serveur),
puis quiescence après ce RUNNING. Correction F7 : avancer `runningIndex` sur le
RUNNING le plus récent à chaque sondage, pour ne pas s'arrêter sur un IDLE
intermédiaire entre deux tours d'un même agent.

### 5.3 Cadrage des capacités

| Rôle | `FILE_ACCESS` | `readOnly` | `subAgents` |
|---|---|---|---|
| factory-analyst | FACTORY_FILES_RO | true | [] |
| factory-editor | FACTORY_FILES | false | [] |

`DelegationTool` n'est instancié que si `subAgents` est non-vide : les deux rôles
ne peuvent pas déléguer.

### 5.4 Traçabilité

Le registre JSONL (`factory/runs/<runId>.jsonl`) contient une ligne par événement :
`run_start`, `phase`, `phase_end`, `run_end`. Les `phase_end` portent dans `facts` :
caseId, statut AgentOS, agents sélectionnés, tours d'agent, appels d'outils,
modèles LLM, fichiers modifiés, codes de sortie oracle, décompte de tâches Nx.

---

## 6. Oracles front

Deux oracles en séquence. Si `verify-types` échoue, `verify-tests` n'est pas
exécuté (la tâche est inutile sans typage correct).

### 6.1 Oracle `types` — périmètre fixe

```
pnpm nx run-many --target=type-check
  --projects=aphrodite,admin,agentic-studio,copilot-chat
  --parallel=4
```

`tsc --noEmit` réel, tel qu'agrégé par le job CI `frontend-type-check` qui bloque
les PRs. Périmètre fixe : insensible à la dérive d'`affected` (un même `nx affected`
a donné 175 projets puis 1033 sur le même dépôt à quelques minutes d'intervalle).

Angle mort connu : 4 projets (zapier-client, e2e-dashboard, ml-ops-admin, pso-admin)
n'ont pas de cible `type-check`. Documenté délibérément.

### 6.2 Oracle `tests` — projets propriétaires des fichiers modifiés

```
pnpm nx run-many --target=frontend-test
  --projects=<projets propriétaires>
  --skip-nx-cache
```

La liste de projets est résolue par `buildOracleCommand` : remontée de dossiers
jusqu'au premier `project.json`, sans appel Nx. Pour 6 fichiers dans 3 libs :
3 projets au lieu de 216 (clôture transitive `affected`).

`--skip-nx-cache` est obligatoire : le cache Nx est partagé avec l'environnement
de dev, ce qui peut servir un résultat caché antérieur à la modification.

### 6.3 Pourquoi deux phases et pas un `&&`

Le typage et le comportement sont des propriétés orthogonales. Un `&&` masquerait
le résultat de la seconde dès que la première échoue : on perdrait la mesure d'une
propriété à cause de l'autre, et le registre ne porterait qu'un verdict pour deux
questions.

### 6.4 Garde A8 — succès vide

Si `exitCode === 0` mais `tasks.executed === 0`, l'oracle n'a rien exécuté :
tout a été servi par le cache. Le verdict est vrai et vide. Le run s'arrête avec
`emptySuccess: true` dans les faits. Ce n'est pas un verdict sur le travail de
l'agent — c'est un échec de l'instrument.

---

## 7. Ce qui n'est pas encore implémenté

### 7.1 Boucle de revue [FONDATION IMPLÉMENTÉE — DÉCONNECTÉE]

La fondation de la boucle de revue est implémentée dans deux modules :

- `factory/lib/review.mjs` — parsing, agrégation déterministe, projection `toReviewFacts`.
- `factory/lib/review-engine.mjs` — moteur d'exécution : lancement parallèle de reviewers
  AgentOS, preflight lecture-seule, collecte, parse, agrégat, faits JSONL-sûrs.

Les deux modules sont **déconnectés de us-loop**. Aucun reviewer n'est appelé dans
le workflow actuel. Il n'existe pas de phase `review-R` dans us-loop.

Deux points d'invocation prévus (non implémentés dans us-loop) :
1. Après le claims-gate réussi, avant la fin du run (revue de sortie).
2. Après épuisement du budget de tentatives, avant d'escalader à la révision
   (revue de blocage — aide l'analyste à reformuler).

### 7.2 Boucle de découverte [PLANIFIÉ]

Phase d'exploration préalable à l'analyse, pour les tickets dont le périmètre est
flou. **Non implémenté.**

### 7.3 Verrous par domaine [PLANIFIÉ]

L'exclusion lecteurs/écrivain par domaine de compilation est spécifiée dans
`domains.mjs` (commentaire `lock: null`) mais pas implémentée. L'orchestrateur
est actuellement séquentiel : un seul run à la fois.

### 7.4 Compte technique dédié

L'orchestrateur s'authentifie sous `FACTORY_USER` (humain). Les runs sont attribués
à quelqu'un qui ne les a pas faits. Un compte `factory-bot` est nécessaire dès
qu'AgentOS supportera les comptes de service.

---

## 8. Limites de SSSF à ne pas reproduire

Le dépôt `disler/super-simple-software-factory` a servi de référence. Ses mécanismes
(scoping déclaratif, fail-closed, gate `diff_matches_claims`, budgets de boucle) sont
repris. Ses défauts sont instructifs :

- **Oracle factice** — `quality.py` livre des `echo "PLACEHOLDER test..."` qui sortent
  en code 0. Un oracle factice est pire qu'aucun oracle : il produit une confiance sans
  fondement.
- **`bash` traverse `writes: []`** — aucune denylist de commandes. AgentOS fait mieux
  avec le plugin BASH (allowlist de commandes déclarées).
- **Audit modifiable par les audités** — le répertoire contenant la base de données
  est writable par tous les agents.
- **Pas de validation humaine** — trois `git commit` automatiques.

---

## 9. Run de référence : WZ-27053 (25 août 2026)

Premier run `us-loop` complet sur un vrai ticket Jira.

| Champ | Valeur |
|---|---|
| Run ID | `20260825T193000Z-babb` |
| Ticket | WZ-27053 (tri de la liste de talents par prénom, nom, email) |
| Domaine | front |
| Analyste | factory-analyst (claude-sonnet-4-6, 67 appels d'outils) |
| Éditeur | factory-editor (claude-sonnet-4-6, 19 appels d'outils) |
| Révision | 1 |
| Tentatives | 1 |
| Fichiers modifiés | 6 (100 % dans le plan, 0 non annoncé) |
| verify-types | pass (exitCode 0, 29 s, executed 4) |
| verify-tests | pass (exitCode 0, 15 s, executed 3, --skip-nx-cache) |
| claims-gate | claimsMatch: true |
| Durée totale | 447 s |
| Statut | pass |

Note : `summaryFound: false` dans les deux phases oracle du run WZ-27053 était correct
— les deux runs étaient frais (pas de cache), et la ligne de synthèse cache Nx est
absente sur un run entièrement frais. Corrigé par A9/F32 (parser v4) qui détecte
également la ligne de succès `NX   Successfully ran target … for N projects`,
toujours présente quelle que soit la stratégie de cache.
