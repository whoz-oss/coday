# Plan — Phase 3 : Validation & finalisation (AgentRuntimeAdapter + ActiveCaseRegistry)

> **Nature de la tâche : VALIDATION / FINALISATION, pas (ré)implémentation.**
> Tout le code de la Phase 3 est **déjà commit** sur HEAD. Le but est de vérifier
> sa conformité, lancer les tests, corriger uniquement les écarts mineurs si la
> build/les tests échouent, puis permettre à la chaîne `simple_sdlc`
> (plan → build → test → review → document) de se terminer en SUCCESS.

---

## 0. État confirmé du dépôt (recon faite par le planner)

- Branche courante : `sbx/coday-agentos-adapter-recovery-be9b`. Working tree **propre**.
- `git log` :
  - `b6a1f7d9` — `feat(agentos): explicit AgentRuntimeAdapter, per-turn baseline and ActiveCaseRegistry with graceful shutdown` (le code Phase 3).
  - `ad14eb90` — `Add spec for Phase 3 AgentRuntimeAdapter and ActiveCaseRegistry` (le plan `specs/6f3f39b6_agent_runtime_adapter_registry.md`).
  - `32000ee8` — baseline (= contenu de `/work/data/baseline`).
- Le commit `b6a1f7d9` touche **11 fichiers, tous** sous
  `factory-service/src/{main,test}/kotlin/io/whozoss/factory/adapter/agentos/` :
  - **main** : `ActiveCaseRegistry.kt`, `AgentOsAdapterConfiguration.kt`,
    `AgentOsCaseShutdownHook.kt`, `AgentOsExecutionAdapter.kt`,
    `AgentRuntimeAdapter.kt`, `DefaultAgentOsExecutionAdapter.kt`,
    `HighWaterMark.kt`, `TrustedCaseBinding.kt`, `VerdictDeriver.kt`.
  - **test** : `ActiveCaseRegistryTest.kt`, `AgentRuntimeAdapterBaselineTest.kt`.
- **Vérifié : aucun fichier de migration (Flyway/Neo4j `V*__`), ni `.github/`,
  ni pipeline de release n'est touché par ce commit.** Garde-fou déjà respecté par HEAD.
- Tous les fichiers listés existent bien aux chemins attendus (confirmé par `ls`).

Le module Kotlin est **compilé/testé via Gradle, orchestré par Nx** :
`factory-service/project.json` → cibles `build`/`test` exécutent `./gradlew build` /
`./gradlew test` dans `cwd: factory-service`, avec `dependsOn` sur
`factory-sdk` et `factory-verification-core`.

---

## 1. Objectif

1. **Confirmer la conformité** du code Phase 3 présent sur HEAD (aucune régression,
   invariants respectés, aucun fichier interdit touché).
2. **Lancer la suite de tests** (build + test Gradle via Nx) et vérifier que
   l'intégralité des tests unitaires et d'intégration passent.
3. **Corriger uniquement les écarts mineurs** si la build/les tests échouent — par
   commits conventionnels ciblés (`fix(agentos): ...`). **Ne pas réimplémenter**.
4. Permettre à la chaîne `simple_sdlc` de s'achever en **SUCCESS** sur toutes ses
   étapes (plan → build → test → review → document).

---

## 2. Étape A — Vérification de conformité (lecture seule, pas de modification)

Depuis `/work/app`, sur la branche `sbx/coday-agentos-adapter-recovery-be9b` :

1. Confirmer le working tree propre et le HEAD attendu :
   ```bash
   git status
   git log --oneline -3
   ```
   Attendu : `b6a1f7d9` au sommet, tree clean.

2. Re-confirmer qu'aucun fichier interdit n'est touché par le commit Phase 3 :
   ```bash
   git show --name-only b6a1f7d9 | grep -iE "migration|flyway|release|\.github|pipeline|V[0-9]+__" \
     && echo "VIOLATION" || echo "OK: no forbidden files"
   ```
   Attendu : `OK: no forbidden files`.

3. Revue de cohérence du contrat (lecture) — vérifier que les points du plan
   d'origine (`specs/6f3f39b6_agent_runtime_adapter_registry.md`) sont bien présents :
   - `AgentRuntimeAdapter.kt` : interface explicite (create/start/observe/reconcile/
     interrupt/kill/close) + `TrustedCaseBinding`/`TurnToken`.
   - `AgentOsExecutionAdapter.kt` : `interface AgentOsExecutionAdapter : AgentRuntimeAdapter`
     avec méthodes par défaut legacy pour la **compatibilité source** des consommateurs
     (`CapabilityExecutionService`, `BridgeRecoveryWorker`, `BridgeCancellationService`,
     `ObservationEscalationPolicy`, `WorkflowService.agentOsExecutionAdapter` nullable).
   - `VerdictDeriver.kt` : constante `RUNTIME_UNREACHABLE` ; aucune dérivation
     `Succeeded` depuis silence/prose/runtime injoignable.
   - `HighWaterMark.kt` : capture de baseline (`baselineOf`), amorçage de checkpoint
     par baseline, store de baselines.
   - `ActiveCaseRegistry.kt` : `@Component` thread-safe énumérable + `shutdownActiveCases`
     best-effort (interrupt puis kill, ne throw jamais).
   - `AgentOsCaseShutdownHook.kt` : `DisposableBean`/`@PreDestroy` déclenchant le shutdown.
   - `AgentOsAdapterConfiguration.kt` : injection de `ActiveCaseRegistry` dans le bean
     (déjà présent et vérifié par le planner).
   - `DefaultAgentOsExecutionAdapter.kt` : baseline par turn, filtrage baseline dans
     `reconcile`/`observeTurn`, mapping runtime-injoignable → `Indeterminate(RUNTIME_UNREACHABLE)`,
     intégration registry.

   > Cette revue est **documentaire** : si tout est présent et que les tests passent,
   > **ne rien modifier**.

---

## 3. Étape B — Build & tests

> La factory lance automatiquement la suite après chaque build :
> `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`.
> Le builder peut (et doit, pour cette tâche de validation) exécuter les tests
> ciblés pour prouver le vert.

1. Tests ciblés du module Kotlin concerné :
   ```bash
   pnpm nx test factory-service
   ```
   (exécute `./gradlew test` dans `factory-service`, dépendances `factory-sdk` +
   `factory-verification-core` compilées d'abord).

2. Build affecté (comme la factory) :
   ```bash
   pnpm nx affected -t build --base="$(cat /work/data/baseline)"
   ```

3. Tests affectés (comme la factory) :
   ```bash
   pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2
   ```

4. Lint affecté :
   ```bash
   pnpm nx affected -t lint --base="$(cat /work/data/baseline)"
   ```

**Juger chaque commande par son code de sortie, pas par la présence de mots comme
`error` dans la sortie.** Rediriger toute sortie scratch vers `/tmp`, jamais dans l'arbre.

Tests à surveiller spécifiquement (critères d'acceptation du plan d'origine) :
- `ActiveCaseRegistryTest` — énumération + shutdown interrupt/kill, best-effort si kill throw.
- `AgentRuntimeAdapterBaselineTest` — baseline (un ancien turn ne déclenche pas le verdict
  du nouveau turn), runtime injoignable ⇒ `Indeterminate(RUNTIME_UNREACHABLE)` jamais `Succeeded`,
  reconciliation post-restart/interrupt sans conclure au silence.
- Non-régression : `DefaultAgentOsExecutionAdapterTest`, `AgentOsSseClientTest`,
  `VerdictDeriverTest`, `HighWaterMarkTest`, `ObservationEscalationPolicyTest`,
  `SseFrameParserTest`.

---

## 4. Étape C — Corrections mineures (seulement si échec)

**Si et seulement si** la build ou un test échoue :

1. Lire le rapport Gradle (`factory-service/build/reports/tests/test/index.html` ou la
   sortie console) pour localiser l'échec précis.
2. Appliquer la **plus petite correction ciblée** possible dans les fichiers Phase 3
   déjà présents (code ou test). Exemples de corrections *mineures* admissibles :
   - import manquant / signature de méthode par défaut à ajuster pour compiler,
   - assertion de test trop stricte sur un détail non contractuel,
   - wiring de bean (paramètre de constructeur) à aligner,
   - mapping d'exception runtime manquant pour atteindre `RUNTIME_UNREACHABLE`.
3. **Interdits** :
   - Ne pas réimplémenter la Phase 3 de zéro.
   - Ne pas toucher aux migrations DB (Flyway/Neo4j) ni au pipeline de release.
   - Ne pas changer la logique de transition terminale des attempts (immuabilité).
   - Ne jamais introduire un chemin produisant `Succeeded` depuis silence / prose LLM /
     runtime injoignable.
   - Identités toujours issues du `TrustedCaseBinding` (frontière de confiance),
     jamais d'arguments LLM.
   - Pas d'écriture scratch dans l'arbre de travail (`/tmp` uniquement).
4. Commiter chaque correction avec un message **conventionnel** ciblé, p. ex. :
   ```bash
   git add factory-service/src/...
   git commit -m "fix(agentos): <correction précise et minimale>"
   ```
   (commitlint/husky actif — respecter le format `type(scope): subject`.)

**Si tout est vert dès l'étape B : ne faire aucun commit de code.** La tâche est alors
une pure validation ; la seule nouvelle écriture est ce document de plan (committé par
l'étape de plan).

---

## 5. Étape D — Revue & documentation (clôture de simple_sdlc)

Objectif : que les étapes `review` et `document` de la chaîne `simple_sdlc` finissent
en SUCCESS.

1. **Revue** : produire une synthèse de conformité (dans le rapport final / la sortie du
   builder, **pas** de nouveau fichier dans l'arbre sauf demande explicite) couvrant :
   - les 4 critères d'acceptation du plan d'origine (baseline, registry/shutdown,
     runtime-injoignable ⇒ Indeterminate, reconciliation sans silence) et le test qui
     les atteste,
   - confirmation « aucune migration/release touchée »,
   - confirmation build + tests + lint verts (coller les codes de sortie / résumé Gradle).
2. **Documentation** : vérifier que la documentation vivante reste cohérente. Le plan
   d'origine (`specs/6f3f39b6_...md`) et **ce plan de validation** (ajouté sous `specs/`)
   constituent la trace documentaire. Si un fichier de doc projet existant référence
   explicitement l'adapter AgentOS (p. ex. `agentos/AGENTOS.md` ou un `docs/` dédié) et
   qu'une mention de la Phase 3 y est attendue, ajouter une note **courte et factuelle**
   via un commit `docs(agentos): ...`. **Sinon, ne créer aucun fichier de doc** : ne pas
   inventer de documentation hors-périmètre.

> Important : ne pas confondre `ActiveCaseRegistry` (registre process-wide en mémoire,
> pour pilotage/shutdown) avec la projection persistée `activeAgentCase`/`controllerExecution`
> écrite par `CapabilityExecutionService.publishActiveAgentCase(...)` dans le document
> d'instance de workflow. Ce sont deux mécanismes distincts et complémentaires.

---

## 6. Critères de sortie (Definition of Done)

- `git status` propre ; HEAD = `b6a1f7d9` (ou + commits `fix(agentos)`/`docs(agentos)`
  ciblés si des écarts mineurs ont été corrigés).
- `pnpm nx test factory-service` → **code de sortie 0**.
- `pnpm nx affected -t build --base="$(cat /work/data/baseline)"` → **0**.
- `pnpm nx affected -t test  --base="$(cat /work/data/baseline)" --parallel=2` → **0**.
- `pnpm nx affected -t lint  --base="$(cat /work/data/baseline)"` → **0**.
- Aucune migration DB ni pipeline de release modifié.
- Tous commits éventuels au format conventionnel (commitlint OK).
- Chaîne `simple_sdlc` : toutes les étapes en SUCCESS, avec une synthèse de revue claire.

---

## 7. Garde-fous / périmètre

- **Kotlin/Spring uniquement**, sous `factory-service/`. Ne pas toucher au TS
  (`libs/`, `apps/`) ni au module `agentos/` sauf doc explicitement attendue.
- Rester sur la branche `sbx/coday-agentos-adapter-recovery-be9b`, commits après `32000ee8`.
- Préférer **zéro changement** : la cible est la validation. Modifier seulement pour
  rendre la build/les tests verts.
- Sorties scratch dans `/tmp`, jamais dans l'arbre de travail.
