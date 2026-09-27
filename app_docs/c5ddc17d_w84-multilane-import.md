# W8.4 — projection multi-lanes et import des sessions

## Résumé

W8.4 enrichit la projection de session de `factory-service` pour alimenter une timeline en swimlanes sans modifier le schéma de persistance gelé. Chaque étape expose désormais sa lane (`agent`, `code` ou `human`), sa responsabilité et son acteur, son statut, ses dépendances et sa fenêtre d’exécution. Les définitions JSON déclaratives peuvent être chargées, validées et enregistrées, avec une définition `forge-story-fullstack-ux` fournie comme seed. Le cockpit vanilla continue de consommer le contrat REST/SSE existant ; son rendu distingue maintenant correctement les trois types de lanes.

## Ce qui a changé

### Projection et exécution

- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowInstance.kt` initialise `lane` à partir de `responsibility.kind` lors de la création de l’instance.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionRunService.kt` conserve les timestamps de chaque étape dans le payload de `workflow_step_states`, les restaure lors d’une reprise, puis écrit dans la projection `lane`, `responsibility`, `status`, `dependsOn`, `startedAt`, `completedAt` et `durationMs`. Une étape terminée ou en échec reçoit une durée calculée en millisecondes ; une étape encore ouverte conserve seulement son début.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/domain/WorkflowProjection.kt` accepte et normalise ces attributs optionnels. `lane` est dérivée de la responsabilité lorsqu’elle n’est pas fournie, tandis que les valeurs étrangères, timestamps mal typés et durées négatives sont rejetés. Les projections v1/v2 restent valides et les champs inconnus restent interdits.
- La projection est toujours stockée dans les structures existantes (`workflow_instances.projection_json` / projections workflow). Le run publie l’événement SSE de projection via le hub existant ; les clients peuvent ensuite relire la projection enrichie sur le contrat actuel.

### Import et seed des définitions

- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionDefinitionCatalog.kt` charge les JSON de `classpath:sessions/*.json`, les valide avec `WorkflowDefinitionValidator` (identifiants, dépendances, acyclicité et kinds), puis les transforme en enregistrements canoniques avec hash.
- `factory-service/src/main/kotlin/io/whozoss/factory/workflow/service/SessionDefinitionSeedRunner.kt` et `SessionDefinitionCatalog.kt` ajoutent le seed idempotent au tenant par défaut au démarrage. Une définition déjà présente pour le couple `workflowType@version` n’est pas écrasée. Le comportement est contrôlé par `factory.workflow.seed.enabled` ; il est désactivé dans le profil OpenAPI.
- `factory-service/src/main/resources/sessions/forge-story-fullstack-ux.json` fournit l’exemple committé : analyse du ticket, checkpoints humains, conception UX/technique, implémentation agent, vérification code et checkpoint d’acceptation, avec les dépendances DAG et les responsabilités `agent`/`human`/`code`.
- `factory-service/src/main/resources/application.yml` active le seed par défaut et permet de le désactiver avec `FACTORY_WORKFLOW_SEED_ENABLED`. `factory-service/src/main/resources/application-openapi.yml` le désactive pour la génération de spécification sans tables workflow.

La surface d’import/upsert HTTP existante est donc utilisable avec une définition validée ; les tests d’intégration enregistrent notamment la définition fournie, vérifient son hash et vérifient qu’un graphe cyclique n’est pas persisté.

### Cockpit vanilla

- `factory/dashboard/js/components/gantt.mjs` donne priorité à `lane` pour l’attribution de phase.
- `factory/dashboard/js/components/temporal-lanes.mjs` donne priorité à `lane`, puis à `responsibility.kind`, conserve le fallback déterministe existant, expose la lane sur chaque nœud et accepte `completedAt` comme fin d’exécution (en plus de `endedAt`). Les noms d’acteurs, statuts et métriques temporelles restent disponibles pour le rendu des lanes `agent`, `code` et `human`.
- L’origine du cockpit et son contrat REST/SSE n’ont pas été basculés vers `factory-service` dans cette étape : cette coupe reste W6b.

## Vérification

Les tests ajoutés couvrent :

- l’intégration import/seed, l’idempotence, la validation d’un cycle, la projection avec lanes, statuts, timestamps, durée et dépendances, ainsi que l’émission de l’événement `workflow-projection-updated` dans `factory-service/src/test/kotlin/io/whozoss/factory/workflow/SessionDefinitionImportIntegrationTest.kt` ;
- la validation/normalisation et la rétrocompatibilité de la projection dans `factory-service/src/test/kotlin/io/whozoss/factory/workflow/domain/WorkflowProjectionValidatorTest.kt` ;
- le chargement et la validation de la définition bundle dans `factory-service/src/test/kotlin/io/whozoss/factory/workflow/domain/SessionDefinitionCatalogTest.kt` ;
- la priorité de la lane et la capture de l’acteur/timing dans `factory/tests/test-projection-governance.mjs`.

Pour vérifier localement :

```bash
cd factory-service
./gradlew clean test
cd ..
node factory/tests/test-projection-governance.mjs
```

La roadmap mise à jour dans `plans/2026-09-27-factory-instrument.md` marque W8.4 comme fait. Le seul travail restant explicitement indiqué sur cette trajectoire est W6b : bascule complète du cockpit et de l’exécution hors du Node legacy, puis suppression de l’outillage Node concerné.
