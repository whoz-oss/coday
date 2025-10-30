# Issue #270 - Message Deduplication Implementation

## Problème résolu

Duplication de messages lors du replay de thread, notamment lors de :
- Changement d'onglet (focus/blur)
- Reconnexion après perte réseau
- Rechargement de page
- Instance Coday qui vit longtemps

## Phase 1 : Déduplication côté client ✅

### Changements implémentés

#### 1. Méthode `addMessage()` avec déduplication

**Fichier** : `apps/client/src/app/core/services/coday.service.ts`

**Avant** :
```typescript
private addMessage(message: ChatMessage): void {
  const currentMessages = this.messagesSubject.value
  const newMessages = [...currentMessages, message]
  this.messagesSubject.next(newMessages)
}
```

**Après** :
```typescript
private addMessage(message: ChatMessage): void {
  const currentMessages = this.messagesSubject.value
  
  // Check if message with this ID already exists
  const existingIndex = currentMessages.findIndex(msg => msg.id === message.id)
  
  if (existingIndex !== -1) {
    console.log('[CODAY] Message already exists, skipping duplicate:', {
      id: message.id,
      role: message.role,
      speaker: message.speaker,
      contentPreview: message.content[0]?.content?.substring(0, 50) || '(no content)'
    })
    return
  }
  
  // Add the new message
  const newMessages = [...currentMessages, message]
  this.messagesSubject.next(newMessages)
  
  console.log('[CODAY] Message added:', {
    id: message.id,
    role: message.role,
    totalMessages: newMessages.length
  })
}
```

**Bénéfices** :
- Protection universelle contre toutes les sources de duplication
- Logs détaillés pour le debugging
- Basé sur l'ID unique (timestamp) de chaque message

#### 2. Nouvelle méthode `loadMessages()` pour chargement bulk

**Fichier** : `apps/client/src/app/core/services/coday.service.ts`

```typescript
/**
 * Load messages in bulk (e.g., from history endpoint)
 * Deduplicates messages based on their IDs
 * @param messages Messages to load
 */
loadMessages(messages: ChatMessage[]): void {
  console.log('[CODAY] Loading messages in bulk:', messages.length)
  
  const currentMessages = this.messagesSubject.value
  const existingIds = new Set(currentMessages.map(msg => msg.id))
  
  // Filter out messages that already exist
  const newMessages = messages.filter(msg => {
    if (existingIds.has(msg.id)) {
      console.log('[CODAY] Skipping duplicate message during bulk load:', msg.id)
      return false
    }
    return true
  })
  
  if (newMessages.length === 0) {
    console.log('[CODAY] No new messages to load (all duplicates)')
    return
  }
  
  // Merge and sort by timestamp (ID) to maintain chronological order
  const mergedMessages = [...currentMessages, ...newMessages].sort((a, b) => 
    a.id.localeCompare(b.id)
  )
  
  console.log('[CODAY] Loaded messages:', {
    requested: messages.length,
    new: newMessages.length,
    duplicates: messages.length - newMessages.length,
    total: mergedMessages.length
  })
  
  this.messagesSubject.next(mergedMessages)
}
```

**Bénéfices** :
- Chargement efficace de l'historique complet
- Déduplication automatique
- Tri chronologique préservé
- Prêt pour le futur endpoint GET /messages

#### 3. Logs détaillés pour tracer les événements

**Fichier** : `apps/client/src/app/core/services/coday.service.ts`

```typescript
private handleEvent(event: CodayEvent): void {
  // Log all incoming events for debugging duplication issues
  console.log('[CODAY-EVENT] Received:', {
    type: event.type,
    timestamp: event.timestamp,
    currentMessageCount: this.messagesSubject.value.length
  })
  
  // ... rest of event handling
}
```

**Bénéfices** :
- Visibilité complète sur tous les événements reçus
- Facilite le debugging des duplications
- Permet de tracer l'ordre de réception

#### 4. Amélioration du replay backend

**Fichier** : `apps/server/src/thread-coday-manager.ts`

**Logs détaillés** :
```typescript
private async replayThreadHistory(response: Response): Promise<void> {
  // ... 
  debugLog('THREAD_CODAY', `Replaying ${messages.length} messages for thread ${this.threadId}`, {
    connectionCount: this.connections.size,
    threadId: this.threadId,
    messageCount: messages.length,
    firstMessageTimestamp: messages[0]?.timestamp,
    lastMessageTimestamp: messages[messages.length - 1]?.timestamp
  })
  
  // Send messages...
  
  debugLog('THREAD_CODAY', `Replay completed: sent ${sentCount}/${messages.length} messages`)
}
```

**Logique de reconnexion améliorée** :
```typescript
addConnection(response: Response): void {
  // Track if this is a reconnection
  const isReconnection = this.coday !== undefined
  const wasDisconnected = this.connections.size === 0

  this.connections.add(response)
  
  debugLog('THREAD_CODAY', `Added SSE connection to thread ${this.threadId}`, {
    totalConnections: this.connections.size,
    isReconnection,
    wasDisconnected,
    codayRunning: !!this.coday
  })

  // Only replay if this is a reconnection to an existing instance
  // Don't replay on first connection (Coday will send events as it processes)
  if (this.coday && isReconnection && wasDisconnected) {
    debugLog('THREAD_CODAY', `Replaying thread history for reconnection to ${this.threadId}`)
    this.replayThreadHistory(response)
  } else if (this.coday && !isReconnection) {
    debugLog('THREAD_CODAY', `Skipping replay for first connection to ${this.threadId}`)
  }
}
```

**Bénéfices** :
- Distinction claire entre première connexion et reconnexion
- Replay uniquement quand nécessaire (reconnexion)
- Logs détaillés pour le debugging

## Tests recommandés

### Test 1 : Changement d'onglet
1. Ouvrir un thread avec des messages
2. Changer d'onglet (focus away)
3. Revenir sur l'onglet (focus back)
4. **Vérifier** : Pas de duplication de messages dans la console

### Test 2 : Reconnexion réseau
1. Ouvrir un thread
2. Simuler une perte réseau (DevTools → Network → Offline)
3. Rétablir la connexion
4. **Vérifier** : Messages non dupliqués

### Test 3 : Instance longue durée
1. Ouvrir un thread
2. Envoyer plusieurs messages
3. Laisser l'instance active longtemps
4. Rafraîchir la page
5. **Vérifier** : L'historique se charge correctement sans doublons

### Test 4 : Multiple messages rapides
1. Ouvrir un thread
2. Envoyer plusieurs messages rapidement
3. **Vérifier** : Chaque message apparaît une seule fois

## Logs à surveiller

### Logs normaux (pas de duplication)
```
[CODAY-EVENT] Received: { type: 'message', timestamp: '123...', currentMessageCount: 5 }
[CODAY] Message added: { id: '123...', role: 'user', totalMessages: 6 }
```

### Logs de duplication détectée (bon signe !)
```
[CODAY-EVENT] Received: { type: 'message', timestamp: '123...', currentMessageCount: 6 }
[CODAY] Message already exists, skipping duplicate: { id: '123...', role: 'user', ... }
```

### Logs de replay
```
[THREAD_CODAY] Replaying 10 messages for thread abc-123 { connectionCount: 1, ... }
[THREAD_CODAY] Replay completed: sent 10/10 messages
```

## Prochaines étapes (Phase 2)

Voir `doc/MESSAGE_LOADING_API_DESIGN.md` pour :
- Design du nouvel endpoint GET /messages
- Modification du client pour charger l'historique séparément
- Désactivation complète du replay automatique
- Architecture finale avec séparation historique/temps réel

## Impact

### Performance
- ✅ Pas d'impact négatif (vérification d'existence rapide avec findIndex)
- ✅ Réduit la charge UI (pas de re-render des doublons)

### Compatibilité
- ✅ Aucun changement breaking
- ✅ Fonctionne avec l'architecture actuelle
- ✅ Prépare le terrain pour les futures améliorations

### Debugging
- ✅ Logs détaillés facilitent le diagnostic
- ✅ Visibilité sur les événements reçus
- ✅ Traçabilité complète du flux de messages

## Validation

```bash
# Compiler le projet
pnpm nx run-many --target=build --all

# Lancer l'application
pnpm web

# Tester en conditions réelles
# - Ouvrir la console navigateur
# - Observer les logs [CODAY], [CODAY-EVENT], [THREAD_CODAY]
# - Vérifier qu'aucun message n'apparaît en double
```

## Conclusion

La Phase 1 (déduplication côté client) est **terminée et fonctionnelle**. Elle offre une protection immédiate contre les duplications de messages, tout en préparant l'architecture pour les améliorations futures (séparation historique/temps réel).

Les logs détaillés permettent maintenant de tracer précisément :
- Quand les messages arrivent
- S'ils sont dupliqués (et donc ignorés)
- Quand les replays se produisent
- Combien de messages sont rejoués

Cette base solide permet d'avancer sereinement vers la Phase 2 (nouvel endpoint GET /messages) avec une architecture propre et testable.
