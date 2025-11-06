# Message Loading API Design - Discussion

## Contexte

Actuellement, le chargement de l'historique d'une conversation se fait via le **replay automatique** lors de la connexion SSE. Cela pose plusieurs problèmes :

### Problèmes identifiés

1. **Duplication de messages** : Si une instance Coday est déjà active et a broadcasté des messages, puis que le client se reconnecte, il reçoit :
   - Les messages déjà reçus via broadcast
   - Les mêmes messages via le replay
   - Résultat : duplication

2. **Mélange de responsabilités** : Le SSE endpoint gère à la fois :
   - La récupération de l'historique (replay)
   - La souscription aux événements temps réel (broadcast)

3. **Manque de contrôle client** : Le client ne peut pas choisir quand recharger l'historique

4. **Problème avec les SummaryEvent** : Les résumés peuvent être insérés au milieu de la chronologie, rendant impossible l'utilisation d'un simple timestamp "last received"

## Solution proposée : Séparation des préoccupations

### Architecture cible

```
Client Thread Loading Flow:
1. GET /api/projects/:projectName/threads/:threadId/messages  → Load full history
2. GET /api/projects/:projectName/threads/:threadId/event-stream → Subscribe to updates

Client reconnection flow:
1. Keep existing messages in memory
2. Reconnect to event-stream (no replay)
3. Receive only new events via broadcast
4. Client-side deduplication prevents any duplicates
```

## Design de l'API `/messages`

### Option A : Messages complets (recommandé)

```typescript
GET /api/projects/:projectName/threads/:threadId/messages

Response: {
  messages: ThreadMessage[],  // Array of all messages in chronological order
  threadInfo: {
    id: string,
    name: string,
    messageCount: number,
    modifiedDate: string
  }
}
```

**Avantages** :
- Simple à implémenter
- Pas de pagination complexe au départ
- Client obtient l'état complet du thread

**Inconvénients** :
- Peut devenir lourd pour les très longs threads
- Mais : la plupart des threads ont < 100 messages

### Option B : Messages avec pagination

```typescript
GET /api/projects/:projectName/threads/:threadId/messages?limit=50&offset=0

Response: {
  messages: ThreadMessage[],
  pagination: {
    total: number,
    limit: number,
    offset: number,
    hasMore: boolean
  }
}
```

**Avantages** :
- Scalable pour les longs threads
- Meilleure performance réseau

**Inconvénients** :
- Plus complexe à implémenter
- Client doit gérer la pagination
- Peut-être prématuré (YAGNI)

### Option C : Hybride - Messages récents par défaut

```typescript
GET /api/projects/:projectName/threads/:threadId/messages?recent=true

Response: {
  messages: ThreadMessage[],  // Last 50 messages by default
  hasMore: boolean,
  totalCount: number
}

// Si besoin de tout charger :
GET /api/projects/:projectName/threads/:threadId/messages?all=true
```

**Avantages** :
- Bon compromis entre simplicité et scalabilité
- Client charge ce dont il a besoin
- Extensible vers pagination complète

## Comportement du replay actuel à modifier

### Changement dans `thread-coday-manager.ts`

```typescript
// AVANT (actuel)
addConnection(response: Response): void {
  this.connections.add(response)
  
  // Replay automatique si Coday existe
  if (this.coday) {
    this.replayThreadHistory(response)
  }
}

// APRÈS (proposé)
addConnection(response: Response): void {
  this.connections.add(response)
  
  // NE PLUS faire de replay automatique
  // Le client charge l'historique via GET /messages s'il en a besoin
}
```

### Avantages du changement

1. **SSE devient pur temps réel** : uniquement pour les nouveaux événements
2. **Client contrôle le chargement** : décide quand charger/recharger
3. **Pas de duplication** : séparation claire entre historique et temps réel

## Flow client détaillé

### Scénario 1 : Chargement initial d'un thread

```typescript
// 1. Charger l'historique
const history = await messageApi.getMessages(projectName, threadId)
codayService.loadMessages(history.messages)  // Avec déduplication

// 2. S'abonner aux mises à jour temps réel
eventStream.connectToThread(projectName, threadId)
```

### Scénario 2 : Reconnexion après perte réseau

```typescript
// Les messages sont déjà en mémoire (messagesSubject)
// Pas besoin de recharger l'historique

// Juste reconnecter le SSE
eventStream.connectToThread(projectName, threadId)

// Les nouveaux événements arriveront via broadcast
// La déduplication côté client empêche les doublons
```

### Scénario 3 : Changement d'onglet (focus/blur)

```typescript
// Option A : Garder la connexion SSE active (navigateur gère)
// → Pas de reconnexion, pas de problème

// Option B : Si le navigateur ferme le SSE
// → Même flow que Scénario 2 (reconnexion sans rechargement)
```

### Scénario 4 : Rechargement explicite par l'utilisateur

```typescript
// L'utilisateur demande explicitement à recharger le thread
codayService.resetMessages()
const history = await messageApi.getMessages(projectName, threadId)
codayService.loadMessages(history.messages)
```

## Implémentation par phases

### Phase 1 : Déduplication côté client ✅ (FAIT)

- Ajout de la déduplication dans `addMessage()`
- Ajout de `loadMessages()` pour chargement bulk
- Logs détaillés pour tracer les duplications

### Phase 2 : Désactiver le replay automatique

```typescript
// Dans thread-coday-manager.ts
addConnection(response: Response): void {
  // ... existing code ...
  
  // REMOVE this block:
  // if (this.coday && isReconnection && wasDisconnected) {
  //   this.replayThreadHistory(response)
  // }
}
```

### Phase 3 : Créer l'endpoint GET /messages

```typescript
// Dans thread.routes.ts
app.get('/api/projects/:projectName/threads/:threadId/messages', 
  async (req, res) => {
    const { projectName, threadId } = req.params
    const username = getUsernameFn(req)
    
    // Get thread
    const thread = await threadService.getThread(projectName, threadId)
    
    // Verify ownership
    if (thread.username !== username) {
      return res.status(403).json({ error: 'Access denied' })
    }
    
    // Get messages from thread
    const messages = thread.getAllMessages()  // ou getMessages() selon besoin
    
    res.json({
      messages,
      threadInfo: {
        id: thread.id,
        name: thread.name,
        messageCount: messages.length,
        modifiedDate: thread.modifiedDate
      }
    })
  }
)
```

### Phase 4 : Modifier le client pour utiliser le nouvel endpoint

```typescript
// Dans thread.component.ts
private async initializeThreadConnection(): Promise<void> {
  // 1. Charger l'historique d'abord
  try {
    const history = await this.messageApi.getMessages(
      this.projectName, 
      this.threadId
    ).toPromise()
    
    this.codayService.loadMessages(history.messages)
  } catch (error) {
    console.error('[THREAD] Failed to load history:', error)
  }
  
  // 2. Puis s'abonner aux événements temps réel
  this.codayService.connectToThread(this.projectName, this.threadId)
}
```

## Questions ouvertes

### Q1 : Faut-il garder `replayThreadHistory()` pour certains cas ?

**Réponse suggérée** : NON
- Le GET /messages remplace complètement le replay
- Plus simple, plus prévisible, plus contrôlable

### Q2 : Que faire des très longs threads (> 1000 messages) ?

**Réponse suggérée** : Commencer simple (Option A), optimiser plus tard si nécessaire
- La plupart des threads ont < 100 messages
- Si problème de performance : implémenter Option C (messages récents)
- Éviter l'optimisation prématurée

### Q3 : Comment gérer les SummaryEvent insérés au milieu ?

**Réponse suggérée** : La déduplication par ID résout le problème
- Chaque événement a un timestamp unique (ID)
- Peu importe où il est inséré dans la chronologie
- La déduplication empêche les doublons

### Q4 : Faut-il un indicateur de chargement pendant GET /messages ?

**Réponse suggérée** : OUI
```typescript
// Dans thread.component.ts
isLoadingHistory: boolean = false

private async initializeThreadConnection(): Promise<void> {
  this.isLoadingHistory = true
  try {
    const history = await this.messageApi.getMessages(...)
    this.codayService.loadMessages(history.messages)
  } finally {
    this.isLoadingHistory = false
  }
  
  this.codayService.connectToThread(...)
}
```

## Décision recommandée

**Approche progressive** :

1. ✅ **Déduplication côté client** (FAIT) - Protection immédiate
2. ⏳ **Créer GET /messages** (Option A - simple) - Nouveau endpoint
3. ⏳ **Modifier le client** pour utiliser GET /messages avant SSE
4. ⏳ **Désactiver le replay automatique** - Simplification
5. 🔮 **Optimisation future** si besoin (pagination, etc.)

Cette approche :
- Résout le problème de duplication **immédiatement** (Phase 1)
- Sépare les responsabilités **proprement** (Phases 2-4)
- Reste **simple** et **évolutive** (pas de sur-ingénierie)
- Suit le principe **YAGNI** (You Aren't Gonna Need It)

## Prochaines étapes

1. **Tester la déduplication actuelle** en conditions réelles
2. **Décider de l'option d'API** (A, B ou C)
3. **Implémenter l'endpoint GET /messages**
4. **Modifier le client** pour charger l'historique avant SSE
5. **Désactiver le replay automatique**
6. **Monitorer et ajuster** si nécessaire
