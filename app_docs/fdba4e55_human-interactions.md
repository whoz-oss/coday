# Interactions humaines en lecture seule dans Cockpit V2

## Résumé

`apps/cockpit-v2` peut désormais afficher les interactions humaines réellement renvoyées par Factory, sans ajouter d’action de réponse ni modifier le backend Kotlin. Les interactions sont chargées comme enrichissement du workflow, normalisées de façon défensive, attachées à `SessionDetail`, comptées dans la section **Gates** et ajoutées à la timeline comme événements informatifs.

## Ce qui a changé

- **Service HTTP — `apps/cockpit-v2/src/app/core/factory-api.service.ts`**
  - Ajout de `getInteractions(workflowId, namespaceId?, state = 'all'): Observable<unknown[]>`.
  - Appel GET sur `/api/factory/workflows/{encodeURIComponent(workflowId)}/interactions`.
  - Le paramètre `state` est transmis, ainsi que `namespaceId` via le mécanisme existant de requête/header.
  - Les réponses tableau, `{ data: [...] }` et `{ data: { items: [...] } }` sont déballées ; les payloads non exploitables donnent `[]`.

- **Modèle — `apps/cockpit-v2/src/app/core/models.ts`**
  - Ajout de `HumanInteraction`, avec identifiants/type/statut obligatoires dans le modèle normalisé et prompt, actions, destinataire et date optionnels.
  - `SessionDetail` porte maintenant `interactions?: HumanInteraction[]`.

- **Mappers — `apps/cockpit-v2/src/app/core/mappers.ts`**
  - `extractInteractions` accepte les tableaux et enveloppes `items`/`data`, vérifie chaque champ et fournit des valeurs de repli pour les enregistrements partiels.
  - Les actions sont normalisées en `{ id, label }`, y compris lorsqu’une action ne fournit pas de label.
  - `mapProjectionToSessionDetail` accepte les interactions en cinquième paramètre et les expose dans la session.
  - Le compteur **Gates** reflète le nombre d’interactions.
  - Chaque interaction devient un `agent_message` avec heure, type et prompt (ou statut), ajouté aux événements existants sans les remplacer.

- **Store — `apps/cockpit-v2/src/app/core/factory.store.ts`**
  - Le cache d’enrichissement conserve aussi `interactions`.
  - `enrichSession` lance `getInteractions` avec les enrichissements timing/evidence/metrics et remappe la session à sa réception.
  - Une erreur de transport/HTTP sur cet appel est ignorée : la session et ses autres enrichissements restent disponibles, avec une liste d’interactions vide.

- **Tests**
  - `factory-api.service.spec.ts` couvre le déballage, `state=all`, namespace/correlation headers, payloads invalides et encodage du workflow ID.
  - `mappers.spec.ts` couvre les formats valides, vides et malformés, le compteur Gates, la liste de session et les événements additifs.
  - `factory.store.spec.ts` couvre l’enrichissement réel et la dégradation silencieuse en cas d’échec des interactions.

Le diff ne modifie que les fichiers Cockpit V2 concernés par cette intégration, les tests, ainsi que la spécification `specs/fdba4e55_cockpit_v2_human_interactions_readonly.md`; aucun fichier backend Kotlin n’est touché.

## Utilisation et vérification

L’affichage consommateur peut lire `session.interactions` depuis le `SessionDetail` fourni par `FactoryStore`. Les valeurs sont en lecture seule : aucune méthode d’action, de reply ou de post n’a été ajoutée.

Pour vérifier le changement dans le monorepo :

```bash
pnpm nx test cockpit-v2
pnpm nx lint cockpit-v2
pnpm nx build cockpit-v2
```

Les tests HTTP peuvent notamment vérifier que l’appel utilise `state=all` par défaut ou `state=open` explicitement, et que `namespaceId` est transmis à la fois selon le mécanisme de requête existant et le header attendu.
