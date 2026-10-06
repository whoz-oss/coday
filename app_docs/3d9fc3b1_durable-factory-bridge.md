# Bridge AgentOS–Factory : binding et reprise après redémarrage

## Ce qui a changé

Le bridge PF4J peut maintenant recevoir les métadonnées de binding depuis le host AgentOS et les rendre disponibles à `FactoryExternalExecutionContextProvider`. Le transport host est porté par `agentos-service` :

- `ExternalContextBindingFilter` intercepte les créations `POST /api/cases` portant les headers `X-Factory-*`, lit l’`id` et le `namespaceId` de la réponse créée, puis transmet les attributs au plugin.
- `ExternalContextBindingController` expose aussi `PUT /internal/external-context/cases/{caseId}/bindings` et son alias `/internal/factory/cases/{caseId}/step-result-binding`.
- Le nouveau SPI `ExternalContextBindingRegistrar` garde les attributs opaques dans le SDK ; `FactoryBindingRegistrar` valide le shared secret en comparaison constante et enregistre le binding dans le registre durable. Un secret absent/incorrect, un attempt id ou un capability token invalide est rejeté. L’absence de nom d’agent utilise le wildcard `*`.

Les bindings step-result, leurs leases et les pending human checkpoints ne sont plus seulement volatils. `FactoryBridgeStateStore` les écrit dans un snapshot JSON (`bridge-state.json`) sous le data directory configuré, avec écriture temporaire puis renommage atomique. Le registre recharge les bindings et l’état de lease au démarrage et persiste chaque mutation (`bind`, acquire/release, acknowledge, invalidation, expiration et suppression). Une corruption ou absence de fichier repart à vide, donc fail-closed ; la lease CAS/single-flight reste appliquée par le registre.

`FactorySseHighWaterMarkStore` ajoute le checkpoint bridge-side restart-safe du protocole d’observation : un curseur `(timestamp, lastEventId)` par `(caseId, attemptId)`, monotone, avec `covers`/`isDuplicate` pour le replay complet et la déduplication par `eventId`. Sa perte est traitée comme une reprise vide, sans réouverture de capacité.

Enfin, le packaging et le démarrage du plugin sont documentés. `bootRunWithPlugins` déploie les JAR PF4J dans `agentos/plugins/` avant de démarrer `agentos-service`; le README du plugin et `agentos/docs/plugin-system.md` décrivent `deployPlugins`, le chargement PF4J, la configuration et les fichiers persistants.

## Fichiers principaux

- **Host / SPI** : `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/binding/ExternalContextBindingFilter.kt`, `ExternalContextBindingController.kt`, leurs tests `ExternalContextBindingSpec.kt`, et `agentos/agentos-sdk/src/main/kotlin/io/whozoss/agentos/sdk/spi/ExternalContextBindingRegistrar.kt`.
- **Plugin** : `FactoryBindingRegistrar.kt`, `FactoryStepResultBindingRegistry.kt`, `FactoryBridgeServices.kt` et `persistence/FactoryBridgeStateStore.kt`.
- **SSE** : `persistence/FactorySseHighWaterMarkStore.kt`.
- **Packaging / utilisation** : `agentos/agentos-factory-bridge-plugin/README.md`, `agentos/build.gradle.kts` et `agentos/docs/plugin-system.md`.
- **Tests** : `FactoryBindingRegistrarSpec.kt`, `FactoryBridgeDurableStateSpec.kt`, `FactoryStepResultBindingRegistryDurabilitySpec.kt`, `FactorySseHighWaterMarkStoreSpec.kt` et `ExternalContextBindingSpec.kt`. La spécification de changement complète est dans `specs/3d9fc3b1_durable_agentos_factory_bridge.md`.

## Configuration et vérification

Le plugin lit les propriétés JVM ou variables d’environnement suivantes :

- `agentos.factory-bridge.data-dir` / `AGENTOS_FACTORY_BRIDGE_DATA_DIR` (défaut `data/factory-bridge`) ;
- `agentos.factory-bridge.secret` / `AGENTOS_FACTORY_BRIDGE_SECRET` (vide par défaut, ce qui désactive le binding) ;
- `agentos.factory-bridge.binding-ttl` / `AGENTOS_FACTORY_BRIDGE_BINDING_TTL` (défaut `3600` secondes).

Depuis `agentos/` :

```bash
./gradlew deployPlugins
./gradlew bootRunWithPlugins
./gradlew :agentos-factory-bridge-plugin:test
```

Pour contrôler le chargement effectif, consulter `GET /api/plugins`. Pour tester le chemin explicite, appeler l’un des deux endpoints de binding avec le secret et les headers `X-Factory-Attempt-Id`, `X-Factory-Capability-Token` et, si nécessaire, `X-Factory-Runtime-Id`, `X-Factory-Agent-Name` et `X-Factory-Expires-At`. Les tests couvrent la visibilité du contexte, les rejets fail-closed, la reprise d’un binding et de sa lease, la single-flight après restart, la suppression terminale, la durabilité des checkpoints et le high-water mark SSE.
