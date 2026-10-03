# Convergence du Cockpit JS

Cette évolution durcit la convergence du cockpit (`factory/dashboard/js`) entre les invalidations SSE et l’état autoritatif REST. Une reconnexion ou un retour au premier plan provoque désormais une relecture, tandis que les réponses réseau arrivant dans le désordre ne peuvent plus remplacer un état plus récent.

## Ce qui a changé

- **Reconnexion SSE** — `factory/dashboard/js/services/sse-client.mjs` mémorise si une première connexion a déjà réussi. La première ouverture émet seulement `open`; toute réouverture ultérieure émet `reconnect`, puis `open`. Les vues peuvent ainsi rattraper une terminaison survenue pendant la coupure.
- **Projection** — `factory/dashboard/js/views/projection.mjs` associe un numéro monotone à chaque `load()`. Une réponse ou une erreur dont le numéro n’est plus courant est ignorée, y compris pour la gestion de `loading`. Le contrôleur recharge l’état sur `reconnect`; le montage écoute aussi `document.visibilitychange` et recharge lorsque la page redevient visible. Le listener de visibilité est supprimé au teardown.
- **Détail d’un run** — `factory/dashboard/js/views/run-detail.mjs` protège les étapes de `loadAll()` avec `currentFetchSequence`, après la lecture principale et après les lectures parallèles timing/evidence/metrics. Les chargements obsolètes, y compris leurs erreurs, ne modifient plus la vue. Le détail recharge sur `reconnect` et au retour à la visibilité, puis désabonne les handlers SSE et le listener de document à l’unmount. Le chargement REST initial du détail reste effectué au montage.
- **Route et namespace** — `factory/dashboard/js/app.mjs` expose `buildRouteIdentity()` et inclut la query complète dans l’identité de route. Ainsi, `/detail?workflowId=A` et `/detail?workflowId=B` déclenchent bien teardown puis remount. Le routeur résout le namespace à chaque transition et crée une URL de stream `/api/factory/workflows/stream?namespaceId=...`; le client partagé est réutilisé tant que le namespace ne change pas et fermé/recréé lorsqu’il change. Le stream est fourni aux vues `/runs`, `/projection` et `/detail`, et les mounters reçoivent le namespace actif.

## Vérification

Les tests ciblés sont des tests Node sans dépendance ni étape de build :

```sh
node --test factory/dashboard/js/services/sse-client.test.mjs
node --test factory/dashboard/js/views/projection.test.mjs
node --test factory/dashboard/js/views/run-detail.test.mjs
node --test factory/dashboard/js/app.test.mjs
```

Ils couvrent notamment : première ouverture contre réouverture SSE, convergence après reconnexion, réponses REST hors séquence, rafraîchissement sur `visibilitychange` et nettoyage des listeners, changement de namespace du stream, et navigation entre deux détails ayant le même chemin mais des paramètres différents. Le plan et la liste de vérifications sont également consignés dans `specs/e3c06c63_fix_cockpit_js_convergence.md`.

Les fichiers de test concernés sont `factory/dashboard/js/services/sse-client.test.mjs`, `factory/dashboard/js/views/projection.test.mjs`, `factory/dashboard/js/views/run-detail.test.mjs` et `factory/dashboard/js/app.test.mjs`. Aucun code Kotlin de `factory-service` n’est inclus dans cette évolution.
