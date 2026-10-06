# Plan : Correction des défauts de convergence du Cockpit JS (`factory/dashboard/js`)

## Vision Globale & Objectives

Le Cockpit JS consomme un flux SSE d'invalidation et synchronise l'état autoritatif via REST. L'objectif est de corriger 4 faiblesses d'architecture causant des défauts de convergence et des incohérences de navigation :

1. **Reconnexion SSE et rattrapage des terminaisons manquées** : Émettre un événement dédié `reconnect` dans `SseClient` à la rétablissement de la connexion SSE (transitions `error`/`disconnect` vers `open`/`connected`, hors connexion initiale). Écouter `reconnect` et `open` dans les vues pour forcer un refetch autoritatif.
2. **Rafraîchissement sur `visibilitychange`** : Ré-exécuter une relecture autoritative (load REST) au retour au premier plan (`document.visibilityState === 'visible'`).
3. **Protection contre les réponses REST obsolètes (out-of-order execution)** : Implémenter une garde de révision / numéro de séquence de requête dans `ProjectionController` (`projection.mjs`) et `mount` (`run-detail.mjs`). Si une réponse résolue appartient à une requête antérieure, elle est ignorée.
4. **Alignement du scope SSE & Identité de route dynamique** :
   - Mettre à jour l'abonnement/scope du `SseClient` selon le `namespaceId` courant au lieu de le figer au bootstrap.
   - Prendre en compte les arguments/query parameters dans la clé d'identité de route (`currentRouteIdentity` / `currentRouteKey` dans `app.mjs`) afin que la navigation entre deux détails distincts (ex: `#/detail?workflowId=A` et `#/detail?workflowId=B`) déclenche correctement le démontage de l'ancienne vue et le montage de la nouvelle.

---

## Targeted Files & Changes

### 1. `factory/dashboard/js/services/sse-client.mjs`
- **Changements** :
  - Ajouter un état `hasEverConnected` (initialisé à `false`).
  - Dans `handleEvent(event, evt)` quand `event === 'open'` :
    - Si `hasEverConnected` est `true`, émettre l'événement `'reconnect'` via `this.emit('reconnect', evt)`.
    - Passer `hasEverConnected = true`.
    - Conserver l'émission de `'open'` et `onOpen?.(evt)`.
  - S'assurer que les événements `'reconnect'` et `'open'` sont correctement propagés aux listeners enregistrés sur `SseClient`.

### 2. `factory/dashboard/js/views/projection.mjs`
- **Changements** :
  - **Protection contre les réponses obsolètes dans `ProjectionController`** :
    - Ajouter `this.requestSequence = 0` dans le constructeur de `ProjectionController`.
    - Dans `load(state = this.mode)` :
      - Incrémenter `const seq = ++this.requestSequence`.
      - Lors de la réception de la réponse REST, vérifier `if (seq < this.requestSequence || this.disposed) return;`.
  - **Reconnexion SSE et visibilitychange** :
    - Écouter l'événement `reconnect` (ou `open`) sur le client SSE pour déclencher immédiatement un `this.load(this.mode)`.
    - Dans `mountProjectionView` (ou dans `ProjectionController`) :
      - Ajouter un event listener `visibilitychange` sur `document` (si `document` existe).
      - Si `document.visibilityState === 'visible'`, appeler `controller.load(controller.mode)`.
      - Nettoyer le listener `visibilitychange` dans la fonction `teardown()`.

### 3. `factory/dashboard/js/views/run-detail.mjs`
- **Changements** :
  - **Protection contre les réponses obsolètes dans `loadAll()`** :
    - Ajouter une variable de séquence localisée au niveau de la vue (ex: `let currentFetchSequence = 0`).
    - Dans `loadAll()` :
      - Incrémenter `const seq = ++currentFetchSequence`.
      - Après la première promesse REST (`apiClient.get(withScope(base))`) et après `Promise.all(...)` pour timing/evidence/metrics, vérifier `if (!state.mounted || seq < currentFetchSequence) return;`.
  - **Reconnexion SSE & visibilitychange** :
    - Écouter l'événement `'reconnect'` (ainsi que `'open'`) du `sseClient` s'il est fourni, pour redéclencher `loadAll()`.
    - Écouter `visibilitychange` sur `document` (si présent). Lorsque `document.visibilityState === 'visible'`, appeler `scheduleRefresh()` ou `loadAll()`.
    - Veiller au nettoyage dans `unmount()` (suppression de `visibilitylistener` et désabonnement SSE).

### 4. `factory/dashboard/js/app.mjs`
- **Changements** :
  - **Identité de route dynamique** :
    - Modifier `createRouter` pour calculer l'identité de route courante (`currentRouteKey` / `currentRouteIdentity`).
    - Au lieu de comparer uniquement le chemin statique (ex: `/detail`), construire la clé d'identité avec la query complète ou le hash entier (ex: `#/detail?workflowId=A` vs `#/detail?workflowId=B` ou `${route}?${queryString}`).
    - Si l'identité courante change, exécuter `runTeardowns()`, mettre à jour `currentRouteKey`, et remonter la vue avec ses nouveaux arguments.
  - **Scope du stream SSE / Namespace dynamique** :
    - Aligner la création ou le passage du `sseClient` sur le `namespaceId` actif résolu lors de la navigation / montage de la vue.
    - S'assurer que le client SSE est reconstruit ou scopé au namespace courant si celui-ci change lors du changement de route.

---

## Verification Plan

### Automated Tests
Exécuter les tests unitaires JS avec Node test runner :
- `node --test factory/dashboard/js/**/*.test.mjs`

Créer / enrichir de nouveaux fichiers de tests unitaires :
1. **`factory/dashboard/js/services/sse-client.test.mjs`** :
   - Tester qu'à la première ouverture SSE, l'événement `'open'` est émis mais **pas** `'reconnect'`.
   - Tester qu'après une erreur/déconnexion, la réouverture émet à la fois `'open'` et `'reconnect'`.
2. **`factory/dashboard/js/views/projection.test.mjs`** :
   - Tester que `load()` sur `ProjectionController` ignore les réponses si une requête ultérieure s'est terminée plus vite (out-of-order execution).
   - Tester qu'une reconnexion SSE ou un événement `visibilitychange` avec `visibilityState === 'visible'` déclenche `load()`.
3. **`factory/dashboard/js/views/run-detail.test.mjs`** :
   - Tester que `loadAll()` dans `run-detail` ignore les réponses REST hors séquence (`currentFetchSequence`).
   - Tester le comportement au retour de visibilité et reconnexion SSE.
4. **`factory/dashboard/js/app.test.mjs`** :
   - Tester la navigation dans `createRouter` entre deux routes de même chemin mais avec des query parameters différents (ex: `#/detail?workflowId=A` -> `#/detail?workflowId=B`) : vérifier que le teardown de A est exécuté et que B est monté avec les bons paramètres.
   - Tester que le scope SSE correspond au namespace actif.

### Commandes de Validation Globale
- `node --test factory/dashboard/js/**/*.test.mjs`
- `pnpm nx affected -t lint --base="$(cat /work/data/baseline)"`
- `pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2`
