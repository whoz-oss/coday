# Factory-service cockpit statique et timeline multi-lanes

## Ce qui a changé

`factory-service` peut maintenant servir le cockpit vanilla directement sur la même origine. La
configuration `factory.cockpit.assets-dir` (défaut : `factory/dashboard`, surchargeable par
`FACTORY_COCKPIT_ASSETS_DIR`) résout le répertoire depuis le répertoire courant ou son parent.
Les contrôleurs et handlers dédiés servent :

- `GET /` avec une redirection vers `/cockpit` ;
- `GET /cockpit`, `/cockpit/` et `/cockpit.html` avec `cockpit.html` en `text/html` ;
- `/js/**` et `/css/**` depuis les assets du cockpit.

Le handler spécialisé force les fichiers `.mjs` à être renvoyés avec
`Content-Type: application/javascript`, y compris lorsque la table MIME du conteneur embarqué
les classerait autrement. Les chemins d’assets sont contrôlés pour éviter la traversée de
répertoire.

Le cockpit dispose aussi maintenant de `GET /api/config`, enveloppé dans `{ "data": ... }`, avec
`agentosUrl` provenant de `factory.proxy.agentos-url` et `codayExpressUrl` à `null`. Cela fournit
le bootstrap attendu par le client sans réintroduire une origine Node.

## Timeline et lanes

Le routeur du cockpit monte désormais `mountProjectionView` lorsque la route est
`#/projection`. Il lui passe le client API relatif, le namespace éventuel de l’URL et le cycle de
vie de la vue. La vue de projection peut donc consommer les endpoints relatifs de
`factory-service`, dont `/api/factory/workflows/{id}/projection`, et s’abonner à la SSE
`/api/factory/workflows/stream` via le mécanisme existant de la vue.

Les cartes de workflow rendent les trois lanes `human`, `agent` et `code` via
`renderTemporalLanes`. La classification privilégie `step.lane`, puis
`responsibility.kind`; chaque étape affiche son nom, son statut visuel et, lorsque présent,
`responsibility.name`. Les attributs HTML exposent également la lane, le statut et l’acteur.
Les styles ajoutés à `dockyard.css` structurent les lanes et distinguent les états completed,
active, failed et pending.

Les appels du cockpit restent same-origin : le client est créé avec `baseUrl: ''` et la
souscription SSE reste relative. Aucun changement au serveur Node, à `factory/run.mjs` ou au
plugin forge n’apparaît dans cette livraison.

## Fichiers porteurs

### Service Kotlin/Spring

- `factory-service/src/main/kotlin/io/whozoss/factory/config/CockpitProperties.kt` : binding de
  `factory.cockpit.assets-dir`.
- `factory-service/src/main/kotlin/io/whozoss/factory/config/CockpitWebConfig.kt` : mappings
  `/js/**` et `/css/**`, avec ordre et type MIME `.mjs` explicites.
- `factory-service/src/main/kotlin/io/whozoss/factory/web/CockpitAssets.kt` : résolution des
  assets et garde contre la traversée.
- `factory-service/src/main/kotlin/io/whozoss/factory/web/CockpitController.kt` : entrée
  `/cockpit` et redirection de `/`.
- `factory-service/src/main/kotlin/io/whozoss/factory/web/CockpitResourceHttpRequestHandler.kt` :
  forçage du type `application/javascript` pour `.mjs`.
- `factory-service/src/main/kotlin/io/whozoss/factory/web/ConfigController.kt` : endpoint
  minimal `/api/config`.
- `factory-service/src/main/resources/application.yml` : propriété et défaut de configuration.

### Cockpit vanilla

- `factory/dashboard/js/app.mjs` : montage de la vue projection sur `#/projection`.
- `factory/dashboard/js/components/temporal-lanes.mjs` : rendu du statut et de
  `responsibility.name` sur chaque étape.
- `factory/dashboard/js/components/workflow-card.mjs` : insertion du rendu des lanes dans les
  cartes.
- `factory/dashboard/css/dockyard.css` : présentation des lanes et états.
- `factory/dashboard/cockpit.html` : libellé de la section projection mis à jour.

## Vérification

Le test d’intégration `factory-service/src/test/kotlin/io/whozoss/factory/web/CockpitStaticServingIntegrationTest.kt`
étend `DomainIntegrationTest` et couvre :

- `/cockpit` en `200 text/html` et la redirection de `/` ;
- `/js/app.mjs` en `200 application/javascript` ;
- la feuille CSS ;
- `/api/config` ;
- une projection publiée contenant des étapes dans les lanes `agent`, `code` et `human`, avec
  vérification de l’exposition de la projection HTTP et du nom d’acteur.

Pour une vérification manuelle, lancer le service depuis `factory-service` avec
`./gradlew bootRun` (port 8141), puis ouvrir `http://localhost:8141/cockpit`. La liste des
sessions est chargée par les API relatives. Ouvrir la route Projection (`#/projection`, ou le
lien correspondant dans le cockpit), sélectionner une session seedée telle que
`forge-story-fullstack-ux` ou une instance créée via `/start` puis `/run`, et vérifier les
lanes Humain, Agent et Code, le statut de chaque étape et son acteur. Les mises à jour de statut
arrivant sur l’événement SSE `workflow-projection-updated` doivent rafraîchir la timeline.

La validation complète prévue pour le service est `./gradlew clean test` depuis
`factory-service`; le diff ajoute les tests d’intégration mais ne fournit pas de résultat
 d’exécution dans cette documentation.

Cette livraison couvre uniquement le cockpit same-origin et sa projection multi-lanes. La
suppression ultérieure du control plane Node, de l’instrument Node et le repointage des autres
consommateurs du port 3141 restent hors périmètre.
