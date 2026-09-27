# Plan directeur — W8 : porter l'instrument de validation Node en Kotlin

> Statut : plan directeur de la vague W8. Source de conception :
> `docs/factory-instrument-kotlin-design.md`. Ce document fixe l'objectif, les invariants et le
> découpage des vagues W8. Il ne remplace pas la conception, il en extrait la feuille de route.
>
> Deux axes cohabitent :
> 1. **l'instrument de mesure** (W8.1, fait) : la bibliothèque pure `factory-verification-core` ;
> 2. **le session runner déclaratif** (W8.2 → W8.4, puis W6b) : le modèle de session, la résolution
>    de capacité, les handlers agent-turn, la projection cockpit et la coupe Node.

---

## 1. Objectif

Porter l'**instrument de validation** aujourd'hui implémenté en Node sous `factory/` (`factory/run.mjs`,
`factory/workflows/*`, `factory/lib/*`, diagnostics, oracles, worker-runtime, bundle opérationnel) vers
un **artefact Kotlin autonome**, sans changer les contrats observables (registre JSONL, verdict
`exitCode === 0`, protocole de gates humains, forme des faits).

L'instrument est le composant qui **mesure** une modification de code : il lance des oracles
déterministes, écrit un registre append-only de faits, et n'émet jamais d'opinion LLM.

## 2. Invariant fondateur — « artefact autonome »

> « L'instrument ne doit pas dépendre de la santé de ce qu'il mesure. »
> (`factory/README.md:13`)

Conséquences, non négociables :

- **Bibliothèque pure, pas service.** `factory-verification-core` est une bibliothèque Kotlin/JVM
  (type:lib), pas un service Spring, pas un plugin PF4J, pas un exécutable.
- **Zéro dépendance framework.** Aucune dépendance Spring (Core/Boot/Data/JDBC), aucun client HTTP
  (OkHttp/Ktor/RestClient), aucun PF4J, aucun driver de base de données. Uniquement : Kotlin stdlib,
  API du JDK (`ProcessBuilder`, `java.nio.file`, `java.security.MessageDigest`, `java.time`) et une
  lib JSON légère (Jackson) pour le JSONL.
- **Autonome par construction.** Le module compile et s'exécute même si Spring, PostgreSQL, AgentOS et
  `node_modules` sont indisponibles ou en panne.
- **Frontière réseau = contrat HTTP.** L'instrument ne doit jamais importer les classes internes Kotlin
  d'AgentOS ni du produit (`factory/DEPENDENCY_MATRIX.md:28`). Le client AgentOS (W8.2) reste HTTP.

## 3. Règle d'autorité — une seule source de vérité

| Contexte d'exécution | Statut de l'information |
| --- | --- |
| Ligne de commande locale (lancement manuel de l'instrument) | **Information** — utile pour déboguer, jamais une preuve. |
| Via `factory-service` (le control plane enregistre la preuve) | **Preuve** — seule source de vérité opposable. |

Une même primitive peut produire un fait localement **et** via le service ; seul le passage par
`factory-service` (qui persiste et attribue l'autorité) transforme cette observation en preuve. Le
moteur Kotlin doit donc rester conçu pour être **appelé par `factory-service`** (W8.6), sans que la
bibliothèque elle-même dépende du service.

## 4. Invariants de comportement (hérités, à préserver)

1. **Fail par défaut** — toute phase est écrite immédiatement au registre avec `status: 'fail'` ; elle
   ne devient `pass` que sur appel explicite de `passPhase()`.
2. **L'agent ne connaît pas son oracle** — le verdict est `exitCode === 0`, jamais dérivé d'une chaîne
   de la sortie.
3. **Aucune sortie LLM dans le registre** — uniquement des faits (noms, statuts, durées, fichiers,
   codes de sortie, compteurs).
4. **Colocalisation obligatoire** — `rootPath` de l'intégration `FILE_ACCESS` == `FACTORY_ROOT`
   (égalité stricte), fail-closed sur l'invérifiable.
5. **L'orchestrateur ne pose jamais de question** — il échoue avec un inventaire, il ne bloque pas.
6. **Garde A8 « succès vide »** — `exitCode === 0` mais `tasks.executed === 0` = échec d'infrastructure
   (pas un verdict).
7. **Plan en mémoire, jamais sur disque** — sinon il compte comme une écriture dans `snapshotDiff`.
8. **Parité du format JSONL** — le registre écrit doit rester lisible tel quel par
   `LegacyRunService` (`run_start` / `phase` / `phase_end` / `run_end`).

## 5. Périmètre (rappel de la conception §5)

- **À porter** : dispatch `run.mjs`, workflows `fix-loop`/`us-loop`, registre writer, oracle
  executor/command, plan, domains, snapshot/diff git+SHA256, client AgentOS, gates, diagnostics,
  worker-runtime, shutdown.
- **À supprimer à terme** : bundle généré, toolchain TS, `factory/**` Node, dashboard Node, dépendance
  Node dans `LegacyRunService`.
- **Réutilisable** : plugin forge PF4J, agrégat oracles `factory-service`, `HttpAgentOsProxyClient`
  (read-only), `LegacyRunService` (lecture/projection), `factory-sdk` (précédent de module Kotlin
  Spring-free).

## 6. Découpage en vagues W8.1 → W8.7

| Vague | Contenu | Dépendance | Vérification |
| --- | --- | --- | --- |
| **W8.1** | **Primitives déterministes** : `RunRegistry` (JSONL fail-par-défaut), `WorkspaceSnapshot` (git+SHA256), `OracleExecutor` (ProcessBuilder, classification, timeout, sortie bornée), `OracleCommand`, `PlanParsing`, `Domains` (résolution pure + surcharges env). Aucune dépendance AgentOS. | — | Parité de format JSONL, tests oracles déterministes. |
| **W8.2** | **Définition de session déclarative + résolution de capacité** : modèle de step `id`/`name`/`dependsOn`/`responsibility{kind,name}` (kind ∈ `agent`/`code`/`human`), validation DAG (ids uniques, dépendances existantes, acyclicité, kind valide) ; manifeste `factory/verification.json` du **repo de destination** (liste blanche `name → commande`, `VERIFICATION_NOT_DECLARED`) dans `factory-verification-core` ; `CapabilityResolver` dans `factory-service` (code → exécution bout-en-bout du script déclaré ; human → checkpoint `human_interactions` ; agent → port `AgentTurnCapability` no-op `NOT_IMPLEMENTED_YET`). Voir § 8. | W8.1 | `./gradlew clean test` de `factory-verification-core` ET `factory-service` ; résolution code bout-en-bout. |
| **W8.3** | **Handlers agent-turn + séquenceur DAG** : `AgentTurnCapability` HTTP AgentOS (client Kotlin, active-case, quiescence, `step-result-binding`), branchement de la capacité agent, déroulé complet du DAG (transitions, gates review-gate, shutdown). | W8.2 | Fake HTTP AgentOS, rejeu `agentos-smoke`, oracles déterministes. |
| **W8.4** | **Projection lanes cockpit + import** : projection lanes du cockpit, import, métriques workflow (`timing`/`retries`/evidence). | W8.3 | Fakes AgentOS + oracles déterministes, comparaison phases/statuts/facts. |
| **W8.5+ (port restant)** | Diagnostics + worker-runtime (si conservés), workflows `fix-loop`/`us-loop` (budgets, briefs, gardes). | W8.4 | Rejeu des diagnostics Node. |
| **W6b** | **Coupe Node** : basculer `LegacyRunService` sur l'instrument Kotlin (remplacer `node factory/run.mjs`, corriger les écarts de spawn : catégorisation des args, propagation d'env dont le secret de gate, suivi runId, parsing des signaux de gate), puis retirer bundle, toolchain TS, `.mjs`, dashboard et mettre à jour `coday.yaml`/docs. | W8.5 | Run nominal de bout en bout + run AgentOS down (échec propre tracé) ; la suite Node ne tourne plus, le Kotlin couvre tout. |

## 7. W8.1 — périmètre exact de cette vague

- **Livrable** : module Gradle `factory-verification-core/` (bibliothèque Kotlin/JVM pure), package
  `io.whozoss.factory.verification`.
- **Primitives** :
  1. `oracle.OracleExecutor` — `runCommand` (shell, `sh -c`) et `executeOracle` (argv sans shell),
     verdict `exitCode == 0`, timeout → kill du groupe de process, sortie bornée, classification
     `CLEAN` / `PRODUCT_REGRESSION` / `EMPTY_SUCCESS` / `ORACLE_INFRASTRUCTURE`, garde A8.
  2. `oracle.TaskOutcomeCounter` — `countTaskOutcomes` (Gradle/Nx), porté fidèlement (pur, sans verdict).
  3. `oracle.OracleCommand` — `resolveOwnerProjects`, `resolveBuildHosts`, `buildOracleCommand`.
  4. `snapshot.WorkspaceSnapshot` — `snapshot(cwd)` (git + SHA-256, sentinelle `unreadable`), `diff`,
     `wroteNothing`.
  5. `registry.RunRegistry` — writer JSONL append-only, fail-par-défaut strict, format compatible
     `LegacyRunService`.
  6. `plan.PlanParsing` — `extractJsonFragment`, `isSafePath`, `parsePlan`, `checkPlanFiles`,
     `compareClaims`.
  7. `domain.DomainResolver` — oracles par domaine + surcharges env (`FACTORY_COMMAND_*`,
     `FACTORY_CWD_*`, `FACTORY_ROOT`).
- **Ports** (interfaces seulement, aucune implémentation réseau) : `ports.AgentTurnRunner`,
  `ports.ReviewGateClient`.
- **Hors périmètre** : ne pas toucher `factory-service`, `factory-sdk`, `factory-forge-plugin`, ni les
  sources Node `factory/**`.
- **Validation** : `./gradlew clean test` du module, 100 % vert.

## 8. W8.2 — Définition de session déclarative & résolution de capacité

### 8.1 Décision — une session = une liste de steps déclaratifs

Une SESSION est une liste de STEPS déclaratifs. Le format minimal reste celui de l'ingénieur ; chaque
step porte **exactement** :

```json
{ "id": "...", "name": "...", "dependsOn": ["..."],
  "responsibility": { "kind": "agent|code|human", "name": "..." } }
```

- `id` : identifiant sûr, unique dans la session ;
- `name` : libellé humain du step ;
- `dependsOn` : ids des steps prérequis (DAG) ;
- `responsibility.kind` ∈ {`agent`, `code`, `human`} ;
- `responsibility.name` : nom résolu selon le `kind`.

AUCUNE logique d'orchestration dans le JSON : pas de `gate`, `retry`, `onFailure`, `briefTemplate`.
L'orchestration intelligente (décider quoi faire après un échec) reste à un agent Coday **externe**,
jamais dans le fichier.

### 8.2 Résolution d'un step par `(kind, name)`

- `kind=agent` → `name` = persona d'agent ; la capacité « lancer un tour d'agent » (HTTP AgentOS) est
  branchée en **W8.3**. En W8.2, le port `AgentTurnCapability` retourne `NOT_IMPLEMENTED_YET`.
- `kind=code` → `name` = une VÉRIFICATION définie **DANS LE REPO DE DESTINATION** (le dépôt produit
  validé), jamais dans le cœur ni dans un plugin.
- `kind=human` → `name` = rôle humain ; le checkpoint passe par `human_interactions`.

### 8.3 Convention du repo de destination (frontière de confiance)

- dossier `factory/` à la racine du repo de destination ;
- MANIFESTE `factory/verification.json` = liste blanche `name → { command, timeoutMs? }`, servant à la
  fois de résolution ET de frontière de confiance : seul ce qui est déclaré est exécutable ;
- scripts sous `factory/verification/<name>`. Exemple :

```json
{ "schemaVersion": "1",
  "verifications": {
    "forge-frontend-verification": {
      "command": "./factory/verification/forge-frontend-verification",
      "timeoutMs": 1800000
    }
  } }
```

Un `name` absent du manifeste n'est PAS exécutable → erreur explicite `VERIFICATION_NOT_DECLARED`.
La commande est lancée avec `cwd` = racine du repo cible, un timeout borné et une sortie tronquée ;
le verdict est `exitCode == 0` (aucune interprétation de la sortie).

### 8.4 Découpage de la vague

| Étape | Contenu | Vérification |
| --- | --- | --- |
| **W8.2** | modèle de session + validation DAG ; manifeste `factory/verification.json` (library pure, + Jackson) ; `CapabilityResolver` (code bout-en-bout, human checkpoint, agent port no-op) | `./gradlew clean test` des deux modules |
| **W8.3** | handlers agent-turn (`AgentTurnCapability` HTTP AgentOS, step-result binding, quiescence) + séquenceur DAG complet | Fake HTTP AgentOS |
| **W8.4** | projection lanes cockpit + import + métriques workflow | Fakes + oracles déterministes |
| **W6b** | coupe Node / bascule `LegacyRunService` | Run de bout en bout |

## 9. Risques principaux (conception §6.3)

1. Transport des gates humains (W8.3).
2. Fidélité de `runAgentTurn` — quiescence multi-tours, F7 (W8.3).
3. Frontière de confiance de la résolution `code` : n'exécuter QUE ce que déclare `factory/verification.json` (W8.2).
4. Autonomie si l'instrument devient module Spring (tranché : bibliothèque pure, W8.1).
5. Parité du format JSONL (W8.1 le garantit).
6. Timeouts/groupes de process pour les oracles longs Gradle/Nx (W8.1).
