# B5-T3 — câblage global du stockage d’artefacts

## Ce qui a changé

Le composition root du dashboard prend désormais en charge la configuration S3/MinIO et la sélection de l’adaptateur d’artefacts :

- `loadConfig` lit endpoint, région, bucket, identifiants/session token S3, TTL de presigned URL, durée de rétention et l’option explicite de blob client mémoire.
- `FACTORY_PERSISTENCE=fs` sans composition SQL sélectionne `MemoryArtifactStore`.
- Le mode SQL, ou un `sqlClient` fourni avec une configuration blob, compose `PostgresArtifactStore` avec un client S3/MinIO, un client mémoire explicite ou un client injecté, et le repository de métadonnées SQL.
- Les stores exposent aussi le blob client et le lister de métadonnées nécessaires à la GC auditée.
- `createApplication` attache `artifactAdmin`, qui réutilise exactement le store composé pour `purgeArtifact`, `setLegalHold` et `collectAndAuditGarbage`. Le serveur HTTP route les requêtes d’administration d’artefacts vers ces instances.

Les adaptateurs et les use cases eux-mêmes ne sont pas modifiés ; le changement reste au niveau du câblage et des exports.

## Exports et documentation

- `factory/src/adapters/artifact/index.ts` réexporte maintenant `PostgresArtifactStore`, sa factory, sa configuration et les types associés.
- `factory/src/application/artifact/index.ts` fournit le barrel des use cases d’administration.
- `factory/src/entrypoints/factory-operational.ts` expose ces barrels, ainsi que le repository SQL des métadonnées.
- `factory/infra/README.md` décrit le stockage objet + métadonnées PostgreSQL, le démarrage MinIO, les variables `S3_*`, `ARTIFACT_RETENTION_DAYS`, `ARTIFACT_SIGNED_URL_TTL` et le fallback mémoire.
- La documentation détaille les routes de purge, legal hold et GC/audit, ainsi que leurs équivalents use-case. Elle rappelle explicitement qu’il n’existe ni timer, ni scheduler, ni auto-purge : les opérations sont déclenchées par un opérateur.
- `factory/README.md` met à jour la présentation des adaptateurs, du câblage et des tests hors-ligne.

## Vérification

Le nouveau test hors-ligne `factory/tests/test-artifact-global-wiring.mjs` couvre :

1. les valeurs par défaut et le parsing de la configuration S3/rétention/presigning ;
2. la sélection mémoire en mode filesystem ;
3. la composition PostgreSQL avec un fake blob client et un client SQL mémoire ;
4. la sélection effective de `S3ObjectClient` avec une configuration S3 ;
5. le fallback blob mémoire explicite ;
6. `putArtifact`, `openArtifact`, `getSignedUrl` et la rétention configurée ;
7. les trois capacités `artifactAdmin` ;
8. le câblage HTTP de la route GC et une requête loopback retournant 200.

Pour vérifier manuellement, lancer individuellement :

```bash
node factory/tests/test-artifact-store.mjs
node factory/tests/test-artifact-signed-urls.mjs
node factory/tests/test-artifact-metadata-postgres.mjs
node factory/tests/test-artifact-admin-commands.mjs
node factory/tests/test-artifact-global-wiring.mjs
```

Pour utiliser MinIO localement :

```bash
docker compose -f factory/docker-compose.minio.yml up -d
```

## Fichiers concernés

- `factory/dashboard/composition-root.mjs`
- `factory/src/adapters/artifact/index.ts`
- `factory/src/application/artifact/index.ts`
- `factory/src/entrypoints/factory-operational.ts`
- `factory/infra/README.md`
- `factory/README.md`
- `factory/tests/test-artifact-global-wiring.mjs`
- `specs/1d4ef8e1_artifact_composition_root_global_wiring.md`

## Point d’attention

Le diff capturé ne contient pas `factory/runtime/factory-operational.mjs`, ni ses fichiers map/metafile, bien que la spécification mentionne une régénération du bundle. La documentation ci-dessus ne considère donc pas ces artefacts générés comme faisant partie du changement effectivement capturé.
