# Plan directeur — W8 : porter l'instrument de validation Node en Kotlin

> Statut : plan directeur de la vague W8. Source de conception :
> `docs/factory-instrument-kotlin-design.md`. Ce document fixe l'objectif, les invariants et le
> découpage W8.1 → W8.7. Il ne remplace pas la conception, il en extrait la feuille de route.

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
| **W8.2** | Client AgentOS Kotlin + active-case + quiescence : endpoints (dont `step-result-binding` interne), DTOs, `runAgentTurn`, préflights, kill/interrupt. | W8.1 | Fake HTTP AgentOS, rejeu `agentos-smoke`. |
| **W8.3** | Gates & shutdown : protocole review-gate (signature unique, single-use, secret), SIGTERM, `endRun fail`. | W8.2 | Tests single-use/secret/stale, `test-shutdown` porté. |
| **W8.4** | Workflows `fix-loop` puis `us-loop` : budgets, briefs, gardes, phases, révisions/revue. | W8.3 | Fakes AgentOS + oracles déterministes, comparaison phases/statuts/facts. |
| **W8.5** | Diagnostics + worker-runtime (si conservés) : smoke, backend-oracle-check ; wiring worker local. | W8.4 | Rejeu des diagnostics Node. |
| **W8.6** | Bascule `LegacyRunService` : remplacer `node factory/run.mjs` par l'instrument Kotlin ; corriger les écarts de spawn (catégorisation des args, propagation d'env dont le secret de gate, suivi runId, parsing des signaux de gate). | W8.5 | Run nominal de bout en bout + run AgentOS down (échec propre tracé). |
| **W8.7** | Suppression Node : retirer bundle, toolchain TS, `.mjs`, dashboard ; mettre à jour `coday.yaml`/docs. | W8.6 | La suite Node ne tourne plus, le Kotlin couvre tout. |

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

## 8. Risques principaux (conception §6.3)

1. Transport des gates humains (W8.3).
2. Fidélité de `runAgentTurn` — quiescence multi-tours, F7 (W8.2).
3. Autonomie si l'instrument devient module Spring (tranché : bibliothèque pure, W8.1).
4. Parité du format JSONL (W8.1 le garantit).
5. Timeouts/groupes de process pour les oracles longs Gradle/Nx (W8.1).
