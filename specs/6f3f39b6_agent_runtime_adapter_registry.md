# Plan — Phase 3 : AgentRuntimeAdapter explicite + Registry des cases actifs

> Factory gouvernée & Workstream Agent — Phase 3.
> Code autoritaire : **Kotlin / Spring** sous `factory-service/`.
> Branche de travail : `sbx/coday-agentos-adapter-recovery-be9b` (déjà checkout).
> Baseline commit : `32000ee8`. Tout nouveau commit reste sur `sbx` après `32000ee8`.
> **Ne pas** toucher aux migrations Flyway/Neo4j ni au pipeline de release.

---

## 1. État des lieux (ce qui existe déjà)

Tout vit dans `factory-service/src/main/kotlin/io/whozoss/factory/adapter/agentos/` :

| Fichier | Rôle |
|---|---|
| `AgentOsExecutionAdapter.kt` | Interface Factory→AgentOS : `createOrRecoverExecution`, `startTurn`, `observeTurn` (2 overloads), `reconcile`, `persistedEvents`, `answerQuestion` (2), `interrupt`, `kill`. Idempotence par `attemptId`. |
| `DefaultAgentOsExecutionAdapter.kt` | Implémentation Spring (RestClient + `AgentOsSseClient`). Maps internes : `executions` (attemptId→ExecutionRecord{caseId, namespaceId, externalUserId, capabilityToken}) et `interruptReasons` (caseId→reason). |
| `AgentOsExecutionVerdict.kt` | Sealed : `Succeeded`, `WaitingHuman`, `Failed`, `Interrupted`, `Indeterminate`. |
| `VerdictDeriver.kt` | Fonction pure sur les events durables. `IDLE`→WaitingHuman/Indeterminate (jamais Succeeded depuis du free-text), `ERROR`→Failed, `KILLED`→Interrupted (si intent) sinon Failed. Prend le **dernier** `CaseStatusEvent`. |
| `HighWaterMark.kt` | `HighWaterMark(timestamp, lastEventId)`, `EventCheckpoint` (dedup + high-water mark, `covers()`/`advance()`), `HighWaterMarkStore` (map `(caseId,attemptId)→EventCheckpoint`). |
| `AgentOsSseClient.kt` | Observation SSE robuste : reconnexion backoff bornée, REST catch-up à chaque reconnexion, dedup par checkpoint, timeout→`Indeterminate(OBSERVATION_TIMEOUT)`, budget reconnexion épuisé→`Indeterminate(RECONNECT_BUDGET_EXHAUSTED)`. Les erreurs transport dans `streamOnce` → `StreamOutcome.Dropped` (reconnecte). |
| `ObservationEscalationPolicy.kt` | Escalade d'un `Indeterminate` : reconcile→reconnect→kill→post-kill reconcile ; ne produit **jamais** `Succeeded`. |
| `CaseEventView.kt` | Vue JSON d'un `CaseEvent`. Constantes de types, `QUIESCENT_STATUSES = {IDLE, KILLED, ERROR}`, `isTransient()`. |
| `AgentOsAdapterConfiguration.kt` / `AgentOsAdapterProperties.kt` | Wiring du bean + propriétés `factory.adapter.agentos.*`. |

**Consommateurs** :
- `capability/CapabilityExecutionService.kt` → `executeRemoteTurn(...)` (lignes ~600-700) : create → startTurn → observeTurn → escalation.
- `agentattempt/service/BridgeRecoveryWorker.kt` : recovery (createOrRecover + startTurn + observeTurn).
- `agentattempt/service/BridgeCancellationService.kt` : interrupt + reconcile.

Tests existants : `DefaultAgentOsExecutionAdapterTest.kt`, `AgentOsSseClientTest.kt`, `VerdictDeriverTest.kt`, `HighWaterMarkTest.kt`, `ObservationEscalationPolicyTest.kt`, `SseFrameParserTest.kt`, helper `FakeAgentOsSseServer.kt`. Stack test : **JUnit5 + MockRestServiceServer + mockk + assertj**.

**Pattern `@PreDestroy` de référence** : `config/EmbeddedNeo4jConfiguration.kt` (import `javax.annotation.PreDestroy`, `@PreDestroy fun ...`).

### Gaps à combler (ce qui manque pour Phase 3)
1. **Pas d'abstraction explicite** nommée couvrant *tout* le cycle de vie (create / bind trusted ctx / start / observe / history / interrupt / kill / **close-seal**). `close/seal` et le **binding explicite de l'environmentRef/revision** n'existent pas dans le contrat.
2. **Pas de baseline explicite avant un turn** : le checkpoint est keyé `(caseId, attemptId)`. Un **nouveau turn sur un case réutilisé** (ou nouvel `attemptId`) démarre avec un checkpoint vide → le replay durable ré-expose l'ancien `IDLE`/`AgentFinishedEvent` → `VerdictDeriver` prend le dernier `CaseStatusEvent` (l'ancien) → **verdict prématuré**. C'est exactement le bug visé par l'acceptance (a).
3. **Pas de support multi-tour explicite** : rien ne capture une baseline par turn pour enchaîner plusieurs turns sur le même case.
4. **Reconciliation après restart** : le checkpoint in-memory est perdu au restart (fail-safe = full replay) mais sans baseline persistée on retombe sur le bug #2.
5. **Pas de `ActiveCaseRegistry`** : aucun suivi énumérable des cases actifs.
6. **Pas de Graceful Shutdown** interrompant/killant les cases enregistrés.
7. **`runtime unreachable` partiellement géré** : `observeTurn` mappe déjà les erreurs vers `Indeterminate` côté `CapabilityExecutionService`, mais `reconcile(caseId)` de `DefaultAgentOsExecutionAdapter` **propage l'exception** si `listEvents` échoue (runtime injoignable) au lieu de renvoyer un `Indeterminate` explicite. Il manque une classification dédiée `RUNTIME_UNREACHABLE`.

---

## 2. Objectif de la Phase 3

Rendre le contrat de runtime **explicite et complet**, capturer une **baseline par turn**, tenir un **registre énumérable des cases actifs**, fournir un **shutdown gracieux**, et garantir qu'un **runtime injoignable** devient toujours `Indeterminate` (jamais `Succeeded`).

Invariants non négociables :
- La Factory est l'unique autorité ; aucune fin inférée du silence/prose.
- Les identités (`caseId`, `namespaceId`, `runtimeId`, `capability`, `environmentRef/revision`) viennent d'une **frontière de confiance** (le `DurableAgentAttempt`), **jamais** des arguments du modèle LLM.
- Tentative terminale immuable (ne pas modifier la logique de transition terminale existante).

---

## 3. Fichiers à créer

### 3.1 `adapter/agentos/TrustedCaseBinding.kt` (NOUVEAU)
Value object immuable transportant les identités de la frontière de confiance :
```kotlin
package io.whozoss.factory.adapter.agentos

/**
 * Trusted identities bound to an AgentOS case at the Factory boundary.
 * Every field originates from the Factory authority (DurableAgentAttempt /
 * resolved capability / work environment) — never from LLM-supplied arguments.
 */
data class TrustedCaseBinding(
    val caseId: String,
    val namespaceId: String,
    val attemptId: String,
    val runtimeId: String? = null,
    val capabilityToken: String? = null,
    val environmentRef: String? = null,
    val environmentRevision: Int? = null,
    val externalUserId: String? = null,
)
```

### 3.2 `adapter/agentos/AgentRuntimeAdapter.kt` (NOUVEAU — abstraction explicite)
Interface explicite documentant **tout** le contrat de cycle de vie. `AgentOsExecutionAdapter` l'**étend** (super-interface) pour rendre l'abstraction testable sans casser les injecteurs existants (qui continuent d'injecter `AgentOsExecutionAdapter`).

```kotlin
package io.whozoss.factory.adapter.agentos

/**
 * Explicit, runtime-agnostic contract driving an agent execution (case).
 * The Factory is the sole authority: identities come from a TrustedCaseBinding,
 * never from LLM arguments; no verdict is ever derived from silence.
 */
interface AgentRuntimeAdapter {

    /** 1. Create the case for an attempt, or recover the existing one (idempotent by attemptId). */
    fun createOrRecoverExecution(binding: TrustedCaseBinding, workflowId: String, stepId: String): CaseHandle

    /**
     * 2. Capture the event baseline of the case BEFORE a turn starts, then post
     *    the turn. Returns a TurnToken whose baseline high-water mark fences out
     *    every pre-existing (old-turn / reused-case) event. Multi-turn safe:
     *    each call captures a fresh baseline for the (caseId, attemptId) turn.
     */
    fun startTurn(binding: TrustedCaseBinding, persona: String, brief: String): TurnToken

    /** 3. Observe the lifecycle until a verdict is derivable or a budget elapses — never a verdict by silence. */
    fun observeTurn(turn: TurnToken, timeoutMs: Long,
                    onIntermediateVerdict: (AgentOsExecutionVerdict.WaitingHuman) -> Unit = {}): AgentOsExecutionVerdict

    /** 4. Durable history (REST catch-up), baseline-filtered for the current turn. */
    fun reconcile(turn: TurnToken): AgentOsExecutionVerdict
    fun persistedEvents(caseId: String): List<CaseEventView>

    /** 5. Controlled interruption with an explicit reason (intent recorded → terminal KILLED derives to Interrupted). */
    fun interrupt(caseId: String, reason: String)

    /** 6. Forced best-effort kill (never throws). */
    fun kill(caseId: String)

    /** 7. Close / seal the case when the runtime supports it (default no-op). */
    fun close(caseId: String) {}
}
```
> `TurnToken` : `data class TurnToken(val caseId: String, val attemptId: String, val baseline: HighWaterMark)` — à placer dans ce fichier ou dans `HighWaterMark.kt`.

> **Compatibilité** : conserver sur `AgentOsExecutionAdapter` les signatures legacy actuellement appelées (`createOrRecoverExecution(namespaceId, workflowId, stepId, externalUserId, attemptId, capabilityToken, caseId)`, `startTurn(caseId, persona, brief, externalUserId, attemptId, capabilityToken)`, `observeTurn(caseId, attemptId, timeoutMs[, cb])`, `reconcile(caseId)`) en **méthodes par défaut** qui construisent un `TrustedCaseBinding`/`TurnToken` et délèguent aux nouvelles. Ainsi `CapabilityExecutionService`, `BridgeRecoveryWorker`, `BridgeCancellationService`, `ObservationEscalationPolicy` **compilent sans modification**. Faire `interface AgentOsExecutionAdapter : AgentRuntimeAdapter`.

### 3.3 `adapter/agentos/ActiveCaseRegistry.kt` (NOUVEAU — exigence #5)
`@Component` thread-safe (`ConcurrentHashMap`) énumérant/suivant/pilotant tous les cases actifs.
```kotlin
package io.whozoss.factory.adapter.agentos

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

enum class ActiveCaseState { CREATED, RUNNING, WAITING_HUMAN, QUIESCENT, TERMINATED }

data class ActiveCaseEntry(
    val binding: TrustedCaseBinding,
    val state: ActiveCaseState,
    val baseline: HighWaterMark?,
    val startedAtEpochMs: Long,
)

@Component
class ActiveCaseRegistry(private val clock: () -> Long = System::currentTimeMillis) {
    private val logger = KotlinLogging.logger {}
    private val cases = ConcurrentHashMap<String, ActiveCaseEntry>()  // keyed by caseId

    fun register(binding: TrustedCaseBinding, baseline: HighWaterMark? = null)
    fun markState(caseId: String, state: ActiveCaseState)
    fun markBaseline(caseId: String, baseline: HighWaterMark)
    fun deregister(caseId: String)
    fun snapshot(): List<ActiveCaseEntry>          // énumération
    fun activeCount(): Int

    /**
     * Graceful shutdown: interrupt then kill every registered non-terminal case
     * via the supplied adapter, logging each case's final state. Best-effort,
     * never throws; a runtime-unreachable case is logged, not silently "done".
     */
    fun shutdownActiveCases(adapter: AgentRuntimeAdapter, reason: String = "factory-service shutdown")
}
```
Détails `shutdownActiveCases` : itérer `snapshot()`, pour chaque case non terminal `runCatching { adapter.interrupt(caseId, reason) }` puis `runCatching { adapter.kill(caseId) }`, logguer `caseId`/`attemptId`/état avant & résultat, puis `deregister`. Journalisation via `KotlinLogging` (pattern déjà utilisé dans `BridgeRecoveryWorker`).

### 3.4 `adapter/agentos/AgentOsCaseShutdownHook.kt` (NOUVEAU — exigence #6)
Déclencheur Spring du shutdown gracieux. Deux options équivalentes — **choisir `DisposableBean`** (le plus explicite) :
```kotlin
package io.whozoss.factory.adapter.agentos

import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component

@Component
class AgentOsCaseShutdownHook(
    private val registry: ActiveCaseRegistry,
    private val adapter: AgentOsExecutionAdapter,
) : DisposableBean {
    override fun destroy() {
        registry.shutdownActiveCases(adapter)
    }
}
```
> Alternative acceptable : `@PreDestroy` directement sur un bean du registre (mimant `EmbeddedNeo4jConfiguration`). Garder le hook **séparé** du registre pour que `ActiveCaseRegistryTest` puisse tester `shutdownActiveCases()` sans contexte Spring.

### 3.5 Tests (NOUVEAUX)
- `src/test/kotlin/io/whozoss/factory/adapter/agentos/ActiveCaseRegistryTest.kt` — exigences (b) et le shutdown.
- Extensions dans `DefaultAgentOsExecutionAdapterTest.kt` (ou nouveau `AgentRuntimeAdapterBaselineTest.kt`) — exigences (a), (c), (d).

---

## 4. Fichiers à modifier

### 4.1 `VerdictDeriver.kt`
Ajouter la constante de classification explicite :
```kotlin
const val RUNTIME_UNREACHABLE = "RUNTIME_UNREACHABLE"
```
(Aucune autre logique de dérivation à changer : un runtime injoignable n'est jamais quiescent, donc jamais `Succeeded`.)

### 4.2 `HighWaterMark.kt`
- Ajouter helper pour **capturer une baseline** depuis une liste d'events durables :
  ```kotlin
  fun EventCheckpoint.Companion.baselineOf(events: List<CaseEventView>): HighWaterMark =
      events.filter { !it.isTransient() }
            .fold(HighWaterMark(null, null)) { mark, e -> advance(mark, e) }
  ```
- Permettre d'amorcer un `EventCheckpoint` à partir d'une baseline (mark pré-positionné) sans peupler `seen`, afin que `covers(mark, oldEvent)` renvoie `true` et que les anciens events soient **dédupliqués/ignorés** au replay :
  ```kotlin
  class EventCheckpoint(windowSize: Int = DEFAULT_SEEN_WINDOW, baseline: HighWaterMark = HighWaterMark(null, null)) {
      @Volatile var mark: HighWaterMark = baseline ; private set
      ...
  }
  ```
- Dans `HighWaterMarkStore`, ajouter : `fun checkpointWithBaseline(caseId, attemptId, baseline): EventCheckpoint` et un store de baselines `(caseId,attemptId)→HighWaterMark` récupérable (`fun baseline(caseId, attemptId): HighWaterMark?`).

### 4.3 `DefaultAgentOsExecutionAdapter.kt`
Injecter `ActiveCaseRegistry` au constructeur (nullable avec défaut no-op registry pour ne pas casser les tests qui construisent l'adapter directement) :
```kotlin
class DefaultAgentOsExecutionAdapter(
    builder: RestClient.Builder,
    private val baseUrl: String,
    private val sseClientFactory: (String) -> AgentOsSseClient = { AgentOsSseClient(it) },
    private val checkpointStore: HighWaterMarkStore = HighWaterMarkStore(),
    private val registry: ActiveCaseRegistry = ActiveCaseRegistry(),
) : AgentOsExecutionAdapter { ... }
```

Changements de comportement :
1. **`createOrRecoverExecution`** : après résolution du `caseId`, appeler `registry.register(binding, baseline=null)` + `markState(CREATED)`. Les champs `environmentRef/revision/runtimeId` du `TrustedCaseBinding` sont stockés dans `ExecutionRecord` (étendre le data class) et dans le registre.
2. **`startTurn` (exigences #2, #3)** : AVANT de poster le message :
   - `val existing = listEvents(caseId, externalUserId).filter { it.caseId==caseId && !it.isTransient() }`
   - `val baseline = EventCheckpoint.baselineOf(existing)`
   - stocker la baseline dans `checkpointStore` (clé `(caseId, attemptId)`) et `registry.markBaseline(caseId, baseline)`.
   - garder le garde `AgentOsCaseBusyException` existant.
   - poster le message, `registry.markState(caseId, RUNNING)`.
   - retourner un `TurnToken(caseId, attemptId, baseline)`.
3. **`observeTurn`** : récupérer l'`EventCheckpoint` **amorcé avec la baseline** du turn (`checkpointStore.checkpointWithBaseline(caseId, attemptId, baseline)`). Les events antérieurs à la baseline sont `covered` → ignorés → `observed` ne contient que les events du turn courant → pas de verdict prématuré. Sur verdict terminal, `registry.markState(TERMINATED)` puis `registry.deregister(caseId)` (ou laisser le shutdown gérer). Sur `WaitingHuman` → `markState(WAITING_HUMAN)`.
4. **`reconcile` (exigences #4, #7)** :
   - Envelopper `listEvents(...)` dans un `try/catch` : toute `RestClientException` / `ResourceAccessException` / `IOException` (runtime injoignable) → retourner `AgentOsExecutionVerdict.Indeterminate(VerdictDeriver.RUNTIME_UNREACHABLE, mapOf("caseId" to caseId, "cause" to e.message))`. **Jamais** `Succeeded`.
   - Filtrer les events par la baseline du turn (si une baseline existe pour `(caseId, attemptId)`) avant `VerdictDeriver.derive`, pour que la reconciliation d'un case multi-tour / après restart ne conclue pas sur l'ancien turn.
   - Conserver le `Indeterminate(NOT_QUIESCENT)` existant quand le turn n'est pas encore quiescent (ne jamais inférer la fin par le silence).
5. **`close(caseId)`** : implémenter `POST /api/cases/{caseId}/seal` si l'API le supporte **sinon** no-op documenté (vérifier via grep sur `HttpAgentOsProxyClient` l'existence d'une route seal ; si absente, `close` = `deregister` + log, pas d'appel réseau). Best-effort, ne jamais throw.
6. **`kill`/`interrupt`** : inchangés fonctionnellement ; ajouter `registry.markState(caseId, TERMINATED)` après kill.

> **Ne pas** modifier la logique terminale de `DurableAgentAttemptService` ni les transitions d'attempt (tentative terminale immuable).

### 4.4 `AgentOsExecutionAdapter.kt`
- `interface AgentOsExecutionAdapter : AgentRuntimeAdapter`.
- Déplacer/garder les signatures legacy en **méthodes par défaut** qui adaptent vers les nouvelles (binding/token). Garder `answerQuestion(...)` et `persistedEvents(...)` ici.
- Documenter la baseline et `close/seal` dans le KDoc.

### 4.5 `AgentOsAdapterConfiguration.kt`
- Injecter `ActiveCaseRegistry` dans le bean `agentOsExecutionAdapter(...)` (ajouter le paramètre ; Spring fournit le `@Component`).
- Rien d'autre : `AgentOsCaseShutdownHook` est auto-détecté comme `@Component`.

### 4.6 `AgentOsAdapterProperties.kt` (optionnel)
Ajouter, si utile, `shutdownGraceMs: Long = 5_000L` (borne de temps du shutdown) — non requis par les critères ; n'ajouter que si `shutdownActiveCases` en a besoin. **Ne pas** ajouter de propriété non utilisée.

---

## 5. Tests d'acceptation (détaillés)

### 5.1 `ActiveCaseRegistryTest.kt` — critère (b)
Stack : JUnit5 + mockk + assertj. **Sans** contexte Spring (unitaire).
- `register`/`markState`/`snapshot` : enregistrer 3 cases, vérifier `activeCount()==3` et que `snapshot()` les énumère avec bindings & états.
- `deregister` retire un case.
- **`shutdownActiveCases` déclenche interrupt+kill** : `val adapter = mockk<AgentRuntimeAdapter>(relaxed = true)` ; enregistrer 2 cases non terminaux ; `registry.shutdownActiveCases(adapter)` ; `verify { adapter.interrupt("c1", any()); adapter.kill("c1"); adapter.interrupt("c2", any()); adapter.kill("c2") }` ; `assertThat(registry.activeCount()).isZero()`.
- **unreachable au shutdown** : un adapter dont `kill` throw ⇒ `shutdownActiveCases` ne propage pas (best-effort) et logge ; le case est tout de même déregistré.

### 5.2 `DefaultAgentOsExecutionAdapterTest.kt` (extensions) ou `AgentRuntimeAdapterBaselineTest.kt`

**Critère (a) — baseline : un ancien turn ne déclenche pas le verdict du nouveau turn.**
Avec `MockRestServiceServer` :
1. `createOrRecoverExecution` (POST /api/cases).
2. 1er turn : `startTurn` ⇒ GET events renvoie `[RUNNING@t0]` (baseline vide/au début), POST message. Simuler fin : events `[RUNNING@t0, Msg@t1, IDLE@t2]`. `reconcile(turn1)` ⇒ `Indeterminate(AGENT_NO_STRUCTURED_RESULT)` (fin du turn1).
3. 2e turn (même case, nouvel `attemptId`) : `startTurn` ⇒ GET events renvoie l'historique **incluant l'ancien `IDLE@t2`** → la **baseline est capturée sur `IDLE@t2`**. POST message.
4. `reconcile(turn2)` **avant** tout nouvel event terminal ⇒ events filtrés par baseline ⇒ aucun `CaseStatusEvent` postérieur ⇒ **`Indeterminate(NOT_QUIESCENT)`**, **pas** le verdict de l'ancien `IDLE`. ✅
5. Ajouter `[... , IDLE@t5]` postérieur à la baseline ⇒ `reconcile(turn2)` dérive maintenant le verdict du **nouveau** turn.

> Variante SSE équivalente possible avec `FakeAgentOsSseServer` : replay complet (ancien IDLE + nouveaux events) ; vérifier que `observeTurn(turn2, ...)` ne retourne pas prématurément sur l'ancien IDLE.

**Critère (c) — runtime injoignable ⇒ Indeterminate, jamais Succeeded.**
- Construire l'adapter avec un `baseUrl` injoignable **ou** un `MockRestServiceServer` qui `andRespond { throw IOException("connection refused") }` (ou `withException`). `reconcile(caseId)` ⇒ `assertThat(verdict).isInstanceOf(Indeterminate)` avec `reason == RUNTIME_UNREACHABLE` ; `assertThat(verdict).isNotInstanceOf(Succeeded::class.java)`.
- `observeTurn` avec SSE injoignable + reconcile injoignable ⇒ `Indeterminate` (budget reconnexion épuisé ou RUNTIME_UNREACHABLE), jamais `Succeeded`.

**Critère (d) — reconciliation après interruption/restart reprend sans conclure au silence.**
- Simuler un **restart** : nouvel `DefaultAgentOsExecutionAdapter` (checkpoint in-memory perdu) ré-observant un case dont le turn n'est pas terminé. GET events renvoie l'historique non terminal ⇒ `reconcile(turn)` ⇒ `Indeterminate(NOT_QUIESCENT)` (reprend l'observation), **jamais** un verdict de fin par silence. Puis un event terminal postérieur ⇒ verdict correct.
- Après `interrupt` : `reconcile` d'un `KILLED` avec intent ⇒ `Interrupted` (déjà couvert ; garder la non-régression).

### 5.3 Non-régression
Tous les tests existants doivent passer **sans modification de leurs assertions**. Grâce aux méthodes par défaut legacy, aucun test appelant les anciennes signatures ne casse. Si un test construit `DefaultAgentOsExecutionAdapter(builder, baseUrl, sseFactory)`, le `registry` par défaut (no-op/standalone) préserve le comportement.

---

## 6. Ordre d'implémentation suggéré

1. `TrustedCaseBinding.kt`, `TurnToken` (dans `HighWaterMark.kt` ou `AgentRuntimeAdapter.kt`).
2. `HighWaterMark.kt` : baseline (`baselineOf`, ctor `EventCheckpoint(baseline=...)`, `HighWaterMarkStore.checkpointWithBaseline`/`baseline`).
3. `VerdictDeriver.kt` : constante `RUNTIME_UNREACHABLE`.
4. `AgentRuntimeAdapter.kt` + refonte de `AgentOsExecutionAdapter.kt` (super-interface + défauts legacy).
5. `ActiveCaseRegistry.kt`.
6. `DefaultAgentOsExecutionAdapter.kt` : baseline dans `startTurn`, filtrage baseline dans `reconcile`/`observeTurn`, `RUNTIME_UNREACHABLE`, intégration registry, `close`.
7. `AgentOsCaseShutdownHook.kt`.
8. `AgentOsAdapterConfiguration.kt` : wiring registry.
9. Tests : `ActiveCaseRegistryTest.kt` + extensions adapter.
10. Build + lint + tests.

---

## 7. Vérification

Depuis `/work/app` :
```bash
# Tests ciblés du module
pnpm nx test factory-service

# Lint / build affectés (la factory les lance aussi)
pnpm nx affected -t lint  --base="$(cat /work/data/baseline)"
pnpm nx affected -t build --base="$(cat /work/data/baseline)"
pnpm nx affected -t test  --base="$(cat /work/data/baseline)" --parallel=2
```
Critères verts attendus :
- `ActiveCaseRegistryTest` : énumération + shutdown interrupt/kill (b).
- Baseline test : ancien turn n'impacte pas le nouveau (a).
- Unreachable ⇒ `Indeterminate(RUNTIME_UNREACHABLE)`, jamais `Succeeded` (c).
- Reconciliation post-restart/interrupt reprend sans silence (d).
- Tous les tests Kotlin existants passent.

---

## 8. Garde-fous / limites de scope

- **Kotlin/Spring uniquement**, sous `factory-service/`. Ne pas toucher au TS (`libs/`, `apps/`) ni à `agentos/`.
- **Ne pas** modifier les migrations (Flyway/Neo4j schema init) ni le pipeline de release.
- **Ne pas** changer la logique de transition terminale des attempts (immuabilité).
- Ne jamais introduire de chemin produisant `Succeeded` depuis du silence, de la prose LLM, ou un runtime injoignable.
- Identités toujours issues du `TrustedCaseBinding` (frontière de confiance), jamais d'arguments LLM.
- Rester sur la branche `sbx/coday-agentos-adapter-recovery-be9b`, commits conventionnels (commitlint), après `32000ee8`.
- Sorties scratch dans `/tmp`, jamais dans l'arbre de travail.

---

## 9. Message de commit suggéré pour le code (Phase 3)
`feat(agentos): explicit AgentRuntimeAdapter, per-turn baseline and ActiveCaseRegistry with graceful shutdown`

---

## 10. Note recon : ne pas confondre avec la projection `activeAgentCase` existante

`CapabilityExecutionService.publishActiveAgentCase(...)` (≈ lignes 957-984) écrit déjà une
projection `controllerExecution` / `activeAgentCase` **dans le document d'instance du
workflow** (champs `kind="agentos"`, `runtimeId="agentos-primary"`, `agentId`, `caseId`,
`stepId`, `attemptId`). C'est un **marqueur persisté par workflow**, pas un registre
process-wide énumérable, et il n'est lié à aucun hook de shutdown.

Implications pour le builder :
- Le nouveau `ActiveCaseRegistry` (exigence #5) est un **composant distinct**, en mémoire,
  énumérant TOUS les cases actifs du process pour le pilotage/shutdown. Ne pas le fusionner
  avec cette projection ni la supprimer.
- Réutiliser les identités de confiance déjà présentes dans cette projection pour peupler
  `TrustedCaseBinding` : `runtimeId` (`"agentos-primary"`), `caseId`, `attemptId`,
  `namespaceId`, `agentId`. Elles viennent déjà de la frontière Factory (jamais du LLM),
  ce qui confirme l'invariant d'identité.
- `WorkflowService.agentOsExecutionAdapter` est injecté **nullable** (`= null`) : le défaut
  des nouvelles méthodes de `AgentRuntimeAdapter` doit rester source-compatible avec ce
  consommateur optionnel.
