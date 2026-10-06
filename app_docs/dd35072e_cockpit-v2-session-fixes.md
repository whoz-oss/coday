# Cockpit v2 — corrections frontend de la vue session

## Résumé

La vue session Angular de `apps/cockpit-v2` couvre désormais les trois corrections demandées, sans modification du backend :

- la commande initiale persistée dans `controllerRequest` est affichée dans une lane humaine `engineer` ;
- le Gantt dispose d’une horloge locale pendant l’exécution et étend les blocs `running` en temps réel ;
- le panneau de phase affiche les interactions, sorties/evidence et informations d’agent réellement disponibles, tout en signalant explicitement les données backend absentes.

## Ce qui a changé

### Commande initiale et lane ingénieur

`apps/cockpit-v2/src/app/core/mappers.ts` lit défensivement `snapshot.controllerRequest`, avec compatibilité pour les formes objet, chaîne legacy et emplacements legacy. Lorsqu’une requête exploitable existe, le mapper garantit une lane `engineer` de type `human`, ton `amber`, avec le demandeur comme sous-titre et un bloc `request` commençant à `0s`. La description vient de `text` ou `prompt`. Un step `request` déjà transformé par la boucle des steps reste prioritaire afin d’éviter une lane ou un bloc dupliqué.

### Gantt temps réel

`apps/cockpit-v2/src/app/features/session/agent-timeline.component.ts` ajoute les inputs `status` et `startedAt`, une horloge signalée rafraîchie chaque seconde lorsque le run est `running`, ainsi que le nettoyage de l’intervalle lors du changement d’état ou de la destruction du composant. `effectiveNowSec` utilise au minimum le `nowSec` reçu et l’écoulement depuis `startedAt`; les runs `queued` et les états terminaux ne progressent pas. L’axe, les ticks et les barres sont calculés à partir de cette valeur, et les blocs dont le statut est `running` sont étendus sans muter les lanes d’entrée.

`apps/cockpit-v2/src/app/features/session/session-page.component.html` transmet le statut et la date de démarrage au composant timeline. Le fichier TypeScript de la page reste dans le périmètre frontend de la vue.

### Sections de phase réelles

`apps/cockpit-v2/src/app/core/models.ts` introduit `PhaseSection` et `PhaseSectionItem`, permettant aux sections de porter des éléments structurés, des statuts, actions, messages et le marqueur `notAvailable`.

Dans `apps/cockpit-v2/src/app/core/mappers.ts`, `buildPhaseDetail` :

- filtre les Gates sur le step actif et expose type, statut, prompt et actions ;
- filtre les Sorties sur le step actif ou le `resultEvidenceId` de la tentative courante, en exposant kind/outcome/facts ;
- expose la configuration connue de l’agent, son rôle, son case et le ratio de tentatives ;
- affiche les messages français dédiés lorsque Gates ou Sorties sont vides ;
- marque Prompts compilés et Modèle LLM résolu comme `notAvailable`, avec le message `Non disponible (nécessite exposition backend)`, au lieu de fabriquer un count nul.

`apps/cockpit-v2/src/app/features/session/session-page.component.html` rend maintenant les items, statuts, sous-titres et actions des sections, ainsi que les états vide et indisponible. Les styles associés sont dans `session-page.component.scss`.

## Tests et vérification

Les tests de `apps/cockpit-v2/src/app/core/mappers.spec.ts` couvrent la lane `controllerRequest`, la déduplication avec un step `request`, les Gates et Sorties réelles, les valeurs vides, la configuration d’agent et les sections indisponibles.

Le nouveau `apps/cockpit-v2/src/app/features/session/agent-timeline.component.spec.ts` couvre l’avancement de l’horloge en `running`, son gel en état terminal, l’absence d’avancement pour un run terminé, l’extension des blocs actifs et l’absence de mutation des inputs.

Pour vérifier le changement dans le monorepo :

```bash
pnpm nx test cockpit-v2
pnpm nx lint cockpit-v2
pnpm nx build cockpit-v2
```

Le diff fourni ajoute les tests et les chemins de validation, mais ne fournit pas de résultat d’exécution de ces commandes.

## Fichiers concernés

- `apps/cockpit-v2/src/app/core/mappers.ts`
- `apps/cockpit-v2/src/app/core/mappers.spec.ts`
- `apps/cockpit-v2/src/app/core/models.ts`
- `apps/cockpit-v2/src/app/features/session/agent-timeline.component.ts`
- `apps/cockpit-v2/src/app/features/session/agent-timeline.component.html`
- `apps/cockpit-v2/src/app/features/session/agent-timeline.component.spec.ts`
- `apps/cockpit-v2/src/app/features/session/session-page.component.ts`
- `apps/cockpit-v2/src/app/features/session/session-page.component.html`
- `apps/cockpit-v2/src/app/features/session/session-page.component.scss`
- `specs/dd35072e_cockpit_v2_front_fixes.md`
