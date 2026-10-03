# Extraction Forge/BMAD en plugin PF4J

## Résumé

Le code Forge/BMAD et Jira a été sorti de `factory-service` dans un module autonome `factory-forge-plugin/`. Le service hôte conserve uniquement les surfaces génériques et monte dynamiquement les routes apportées par les extensions PF4J. Ainsi, sans JAR Forge installé, le cœur démarre avec ses routes workstream, runs, proxy AgentOS, etc., tandis que `/api/forge/*` et `/api/jira/*` restent absentes (`404`). Avec le plugin déployé, les routes Forge/Jira et leurs alias historiques sont fournies par le plugin.

## Ce qui porte le changement

### Nouveau module `factory-forge-plugin/`

- `factory-forge-plugin/build.gradle.kts` configure un build Kotlin JVM/KAPT publiable, compile contre `factory-service` et `factory-sdk` en `compileOnly`, et évite donc d’embarquer les dépendances fournies par l’hôte. Le JAR reçoit les attributs PF4J et la tâche `deployPlugin` le copie vers `factory-service/plugins/` par défaut, ou vers `-Pfactory.plugins.dir=...` / `-Pplugins.dir=...`.
- `settings.gradle.kts` est autonome, réutilise le catalogue Gradle du service et substitue le build `factory-service`. `project.json` fournit les cibles Nx `build`, `test` et `deploy-plugin`, ainsi que les tags `type:lib`, `platform:jvm`, `scope:plugin`.
- `src/main/resources/plugin.properties` et le manifeste du JAR déclarent l’ID `factory-forge-plugin`, la version `0.0.1-SNAPSHOT`, le fournisseur `whoz-oss` et `io.whozoss.factory.forge.plugin.ForgePlugin` comme classe PF4J.
- Les packages `forge/domain`, `forge/service`, `forge/infrastructure`, `forge/port` et `forge/web` contiennent désormais le domaine, ledger, gates, opérations Story, client Jira et handlers Forge déplacés depuis le service.
- `ForgePlugin.kt` crée un contexte Spring enfant du contexte hôte et scanne uniquement le namespace du plugin. `ForgePluginConfiguration.kt` fournit `ForgeProperties` et le client Jira.
- `ForgeRouteContributor.kt` implémente l’extension SDK `FactoryRouteContributor`; `ForgeRoutes.kt` transforme les handlers sans annotations MVC en routes fonctionnelles pour les endpoints `/api/forge/...`, `/api/factory/forge/...`, `/api/jira/...` et `/api/factory/jira/...`.
- `ForgeWorkflowProjectionPublisher.kt` branche `ForgeWorkflowAdapter` sur `FactoryWorkflowProjectionPublisher` et renvoie `PUBLISHED` ou `SKIPPED` selon la projection adaptée.
- La configuration du plugin est indépendante de `application.yml`: `ForgeProperties.fromEnvironment()` privilégie les propriétés système (`factory.forge.*`), puis les variables `AGENTOS_URL`, `FACTORY_RUNS_DIR`, `FACTORY_RUN_ENTRY` et `JIRA_*`.
- `openapi/factory-forge-plugin-openapi.yaml` documente séparément les routes Forge/Jira, y compris les alias et le comportement du plugin optionnel.

### Nettoyage et généralisation de `factory-service`

- `factory-service/src/main/kotlin/io/whozoss/factory/workstream/` héberge maintenant `WorkstreamController`, `WorkstreamService` et `JdbcWorkstreamRepository`; le test JDBC suit ce nouveau package.
- `factory-service/src/main/kotlin/io/whozoss/factory/proxy/` contient le proxy AgentOS générique (`AgentOsProxyClient`, implémentation HTTP, contrôleur et configuration). Les endpoints `/api/agents` et `/api/cases/{caseId}/events` sont conservés.
- `factory-service/src/main/kotlin/io/whozoss/factory/web/FactoryHttp.kt` fournit les enveloppes, erreurs, résolution du caller et validation `namespaceId` génériques. Les contrôleurs `runs/web/LegacyRunController.kt` et `LegacyRunSseController.kt` n’importent plus d’utilitaires Forge.
- `runs/config/LegacyRunConfiguration.kt` possède maintenant le bean `legacyRunService`, avec `LegacyRunProperties`; il n’est plus défini par une configuration Forge. Les anciennes propriétés et configuration Forge ont été retirées du service.
- `application.yml` garde les propriétés core (`factory.runs`, `factory.proxy`, etc.) mais ne contient plus la configuration `factory.forge.*` dédiée.
- `factory-service/src/main/kotlin/io/whozoss/factory/config/FactoryPluginRouteConfig.kt` collecte les `FactoryRouteContributor` PF4J et expose toujours une `RouterFunction`; sans contribution, il monte un routeur qui ne matche aucune requête.
- `factory-sdk/src/main/kotlin/io/whozoss/factory/sdk/spi/FactoryRoute.kt` expose les attributs de requête hôte, notamment le contexte de confiance, aux handlers de plugin.
- `factory-service/openapi/factory-openapi.yaml` est désormais core-only: les tags et chemins Forge/Jira ont été retirés; les spécifications plugin et core sont donc séparées.

## Utilisation et vérification

Depuis le dépôt:

```bash
pnpm nx build factory-forge-plugin
pnpm nx test factory-forge-plugin
pnpm nx run factory-forge-plugin:deploy-plugin
```

Le dernier objectif construit le JAR puis le dépose dans `factory-service/plugins/`. Pour un autre répertoire, utiliser par exemple `pnpm nx run factory-forge-plugin:deploy-plugin --args='-Pfactory.plugins.dir=/chemin/plugins'` ou invoquer `factory-forge-plugin/gradlew deployPlugin -Pfactory.plugins.dir=...`. Un démarrage du service avec un répertoire de plugins vide doit exposer les routes core et retourner `404` pour `/api/forge/runs` et `/api/jira/PROJ-1`; après dépôt du JAR, les routes du fichier OpenAPI plugin deviennent disponibles.

Les tests du service dans `factory-service/src/test/kotlin/io/whozoss/factory/plugin/FactoryPluginSystemIntegrationTest.kt` isolent volontairement un répertoire temporaire vide et vérifient l’initialisation PF4J, le routeur vide, les routes core et les `404` Forge/Jira. Les tests Forge déplacés dans `factory-forge-plugin/src/test/kotlin/io/whozoss/factory/forge/` sont devenus des tests directs adaptés au contexte plugin; `FactoryForgePluginIntegrationTest.kt` couvre le déploiement du JAR et le démarrage hôte avec plugin. `AgentOsProxyMockTest.kt`, `WorkstreamJdbcRepositoryTest.kt` et `PostgresContainerSpec.kt` ont été ajustés aux nouveaux packages et au répertoire de plugins isolé.

La vérification statique attendue après extraction est que `factory-service/src` ne contient plus d’import `io.whozoss.factory.forge` (et en particulier aucun import Forge dans `factory-service/src/main/kotlin/io/whozoss/factory/runs`). Le diff ne fournit pas de sortie d’exécution de `clean test`; lancer séparément les builds/tests Gradle demandés sur `factory-sdk`, `factory-service` et `factory-forge-plugin` pour confirmer l’état de l’environnement courant.
