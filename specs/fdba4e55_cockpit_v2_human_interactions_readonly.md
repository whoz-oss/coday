# Plan implementation: Read-Only Human Interactions Integration in apps/cockpit-v2

## Overview
Garantir la lecture seule des interactions humaines d'un workflow dans `apps/cockpit-v2` sans modifier le backend Kotlin.
L'endpoint backend existant est `GET /api/factory/workflows/{workflowId}/interactions?namespaceId=...&state=all|open`, qui renvoie `{ data: HumanInteractionRecord[] }` ou un tableau empaqueté.

## Proposed Changes

### 1. HTTP Service (`apps/cockpit-v2/src/app/core/factory-api.service.ts`)
- Ajouter la méthode `getInteractions(workflowId: string, namespaceId?: string, state: 'all' | 'open' = 'all'): Observable<unknown[]>`
- Appeler `GET /api/factory/workflows/${encodeURIComponent(workflowId)}/interactions`
- Transmettre le paramètre de requête `state` (valeur par défaut `'all'`) ainsi que le paramètre/header `namespaceId` via le helper privé `request<unknown>` existant.
- Déballer défensivement les éléments renvoyés:
  - Si le payload déballé de l'enveloppe `{ data }` est un tableau, le retourner.
  - S'il s'agit d'un objet contenant `items: unknown[]` (`{ data: { items: [...] } }`), retourner cet array `items`.
  - Sinon, retourner `[]`.

### 2. UI Models (`apps/cockpit-v2/src/app/core/models.ts`)
- Définir l'interface `HumanInteraction`:
  ```ts
  export interface HumanInteraction {
    interactionId: string
    stepId: string
    interactionType: string
    status: string
    prompt?: string
    actions?: Array<{ id: string; label: string }>
    recipient?: string
    createdAt?: string
  }
  ```
- Enrichir `SessionDetail`:
  - Ajouter le champ optionnel `interactions?: HumanInteraction[]`.

### 3. Pure Mappers (`apps/cockpit-v2/src/app/core/mappers.ts`)
- Créer la fonction pure `extractInteractions(payload: unknown): HumanInteraction[]`:
  - Accepter un payload `unknown` (qui peut être un tableau ou un objet enveloppe).
  - Inspecter défensivement chaque élément pour extraire:
    - `interactionId`: string (défaut `''` ou ID généré défensivement/depuis l'item)
    - `stepId`: string (défaut `''`)
    - `interactionType`: string (défaut `'unknown'`)
    - `status`: string (défaut `'unknown'`)
    - `prompt`: string optionnel (extrait depuis `payload.prompt` ou `payload.question` ou top-level `prompt`)
    - `actions`: tableau d'objets `{ id: string, label: string }` si présent dans `payload.actions` ou top-level `actions`
    - `recipient`: string optionnel
    - `createdAt`: string optionnel (ex: ISO string)
- Mettre à jour `mapProjectionToSessionDetail`:
  - Ajouter le paramètre optionnel `interactions?: unknown` dans la signature:
    `mapProjectionToSessionDetail(workflow: unknown, timing?: unknown, evidence?: unknown, metrics?: unknown, interactions?: unknown): SessionDetail`
  - Calculer `const mappedInteractions = extractInteractions(interactions)`
  - Renseigner `interactions: mappedInteractions` dans le `SessionDetail` retourné.
  - Mettre à jour la section `'Gates'` dans `buildPhaseDetail`:
    - Dans `buildPhaseDetail(steps: unknown[], fallbackStatus: RunStatus, interactionsCount = 0)` (ou directement dans `mapProjectionToSessionDetail`), initialiser le count de la section `'Gates'` à `interactionsCount` (ou `mappedInteractions.length` s'il y a des interactions).
  - Injecter chaque interaction comme un `RunEvent` dans `session.events`:
    - Pour chaque interaction dans `mappedInteractions`, créer un `RunEvent`:
      - `time`: heure formatée avec `formatClock(interaction.createdAt)` (ou fallback instant courant / HH:mm:ss)
      - `type`: `'agent_message'` (ou type adapté)
      - `text`: texte combinant le type/prompt/status (ex: `[Human Gate - ${interaction.interactionType}] ${interaction.prompt ?? interaction.status}`)
    - Fusionner/trier ou ajouter ces événements aux événements de la session sans écraser les événements existants (issus d'evidence/steps).

### 4. Angular Store (`apps/cockpit-v2/src/app/core/factory.store.ts`)
- Mettre à jour `enrichment` Map et la méthode `enrichSession(snapshot: unknown)`:
  - Étendre la structure du cache d'enrichissement:
    `private readonly enrichment = new Map<string, { timing?: unknown; evidence?: unknown; metrics?: unknown; interactions?: unknown }>()`
  - Dans `enrichSession`, ajouter l'appel `this.api.getInteractions(id, namespaceId)` en parallèle des appels `getTiming`, `getEvidence`, et `getMetrics`.
  - En cas de succès, exécuter `merge({ interactions })`, ce qui recalculera `mapProjectionToSessionDetail(snapshot, next.timing, next.evidence, next.metrics, next.interactions)` et mettra à jour le signal `sessions`.
  - En cas d'échec de `getInteractions`, intercepter avec `.subscribe({ next: ..., error: () => undefined })` (dégradation silencieuse sans crash).

### 5. Mock Fallback (`apps/cockpit-v2/src/app/core/mock-data.ts`)
- Vérifier la cohérence de `SESSION_872641A8` et s'assurer qu'aucun mock d'interaction arbitraire trompeur n'est injecté comme interaction réelle si non chargée.
- S'assurer que le fallback de démo reste conforme aux types mis à jour de `SessionDetail`.

### 6. Unit Tests (`apps/cockpit-v2/src/app/core/`)
- `factory-api.service.spec.ts`:
  - Valider `getInteractions`: unwrapping envelope `{ data: [...] }` ou `{ data: { items: [...] } }`, query parameter `state=all`, propagation de `namespaceId`, et gestion d'erreur.
- `mappers.spec.ts`:
  - Valider `extractInteractions` avec payloads valides, tableau d'items, structures vides, et objets malformés.
  - Valider `mapProjectionToSessionDetail`: vérifier que la section "Gates" reflète `interactions.length`, que `session.interactions` est renseigné, et que `session.events` contient les événements d'interactions créés sans perdre les événements evidence/steps.
- `factory.store.spec.ts`:
  - Valider l'enrichissement async avec `getInteractions`.
  - Valider la dégradation gracieuse en cas d'erreur HTTP sur `getInteractions`.

## Verification Plan

### Automated Tests
Run Nx targets:
- `pnpm nx test cockpit-v2`
- `pnpm nx lint cockpit-v2`
- `pnpm nx build cockpit-v2`
