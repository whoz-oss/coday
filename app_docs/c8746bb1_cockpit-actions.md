# Cockpit v2 : actions autorisées par le backend

## Résumé

Le cockpit v2 est désormais actionnable tout en conservant l’invariant de gouvernance : les contrôles sont rendus uniquement à partir de `allowedActions` fourni par le backend. Les identifiants de workflow, interaction, étape et tentative ainsi que les révisions attendues sont relayés depuis cette réponse ; l’interface n’en fabrique pas. Les `blockers` sont également remontés et affichés dans la vue de session.

## Ce qui a changé

- **Accès HTTP** — `apps/cockpit-v2/src/app/core/factory-api.service.ts`
  - Ajout de `getActions(workflowId, namespaceId?)` pour lire `/actions`, débloquer `{ data }`, normaliser les listes et transmettre `namespaceId` en query/header ainsi que `X-Correlation-Id`.
  - Ajout des POST `replyInteraction`, `openRetry`, `cancelAttempt`, `continueCost` et `stopCost` sur les routes dédiées. Les corps sont transmis tels quels, les IDs de chemin sont encodés, les réponses sont déballées et les erreurs passent par `normalizeError`.

- **Contrats et mapping** — `apps/cockpit-v2/src/app/core/models.ts` et `apps/cockpit-v2/src/app/core/mappers.ts`
  - Ajout des types d’action `reply`, `retry`, `cancel_attempt`, `continue_cost` et `stop_cost`, des identifiants/révisions associés, de `WorkflowBlocker` et de `GetActionsResponse`.
  - `SessionDetail` expose désormais `allowedActions?` et `blockers?` sans modifier les champs existants.
  - `extractAllowedActions` et `extractBlockers` acceptent les formes attendues (tableau ou enveloppes `allowedActions`/`blockers`, `items`, `data`), écartent les entrées inutilisables et retombent sur des tableaux vides. Les messages de blocker sont utilisés comme libellés, avec repli sur le code.
  - `mapProjectionToSessionDetail` intègre ces deux listes dans la session.

- **Store** — `apps/cockpit-v2/src/app/core/factory.store.ts`
  - L’enrichissement de session appelle `getActions` en parallèle des autres enrichissements. Une erreur de cette lecture reste non bloquante et laisse les listes d’actions/blockers vides.
  - Les méthodes publiques `replyInteraction`, `retry`, `cancelAttempt`, `continueCost` et `stopCost` appellent le service avec le namespace de la session (sauf surcharge explicite), puis déclenchent `load()` après succès afin de relire l’état autoritatif.

- **Interface de session** — `apps/cockpit-v2/src/app/features/session/action-bar.component.ts`, `.html`, `.scss`, et `session-page.component.ts`, `.html`
  - Le nouveau `ActionBarComponent` filtre les actions par type et ne rend aucun bouton en l’absence de l’action correspondante dans `allowedActions`.
  - Il propose les réponses d’interaction (choix et commentaire facultatif), la relance d’étape, l’annulation de tentative et les contrôles continuer/arrêter de coût.
  - Les blockers sont présentés avec des styles distincts pour attente humaine, blocage, pause de coût et cas indéterminé.
  - Les clics émettent des intents contenant les identifiants et révisions autorisés ; `SessionPageComponent` les transmet au store. L’ancien bouton d’arrêt basé sur le seul statut `running` a été retiré.

- **Tests et spécification** — `apps/cockpit-v2/src/app/core/factory-api.service.spec.ts`, `factory.store.spec.ts`, `mappers.spec.ts`, `apps/cockpit-v2/src/app/features/session/action-bar.component.spec.ts` et `specs/c8746bb1_cockpit_v2_allowed_actions_blockers.md`
  - Les tests couvrent le GET et les cinq POST (routes, corps, namespace, corrélation, déballage et erreurs), le mapping défensif, l’enrichissement et sa dégradation, le refetch après chaque action, ainsi que le rendu conditionnel et le classement visuel des blockers.
  - La spécification ajoutée décrit les contrats, l’architecture et les commandes de vérification de cette évolution.

## Utilisation et vérification

La page de session consomme les champs `session.allowedActions` et `session.blockers` remplis par le store. Pour exécuter une action, le composant doit recevoir l’action backend correspondante, puis l’intent est envoyé au store ; après le POST, le store recharge les workflows et leurs enrichissements. En pratique, l’absence d’une action dans la réponse `/actions` signifie donc l’absence du contrôle associé.

Les tests ciblés sont :

```bash
pnpm nx test cockpit-v2
pnpm nx lint cockpit-v2
pnpm nx build cockpit-v2
```

Les changements restent limités au cockpit v2, à ses tests et à la spécification associée ; aucun fichier Kotlin backend n’est modifié dans ce diff.
