# Jalon C1-T1b — protocole de lease

## Ce qui a changé

Le protocole de lease des `work_units` est maintenant décrit par un domaine pur, un port de persistance et un adapter SQL PostgreSQL. Les leases suivent les états `active`, `released` et `expired`, avec un `fencingToken` monotone issu de `work_unit_lease_fencing_seq`. Les erreurs exposent des codes stables (`LEASE_FENCED`, `LEASE_NOT_FOUND`, `LEASE_EXPIRED`, `NO_ELIGIBLE_WORK_UNIT`, `WORK_UNIT_NOT_FOUND`, `INVALID_LEASE_STATE`) via `LeaseError`.

L’adapter réalise les mutations dans `withTransaction` :

- `acquire` sélectionne le work unit éligible de plus haute priorité, puis le plus ancien, en respectant `not_before`, avec `FOR UPDATE SKIP LOCKED`. Il crée une lease active, attribue le prochain token de fencing et passe le work unit à `running` en incrémentant `attempt_count` et `revision`.
- `renew` vérifie l’existence, l’état actif, l’expiration et l’égalité exacte du token avant de mettre à jour le heartbeat et la date d’expiration.
- `release` vérifie le token fourni, marque la lease `released` et passe le work unit à `completed` par défaut, ou au `resultStatus` demandé (`failed`/`created`).
- `expire` balaie les leases actives dont l’échéance est dépassée, les marque `expired` avec une raison (`heartbeat_timeout` par défaut), puis remet les work units à `created`. L’implémentation ne double pas `attempt_count` lors de ce requeue : le compteur a déjà été incrémenté à l’acquisition.

Les lectures permettent de retrouver une lease par son identité tenant/workstream/work-unit/lease, ou de trouver la lease active d’un work unit.

## Fichiers concernés

- `factory/src/domain/lease/lease.ts` : types `WorkUnitLease`, états, constantes d’erreur et de raisons d’expiration, validation pure du fencing, calcul d’échéance et règles d’activité/renouvellement.
- `factory/src/ports/persistence/lease-repository.ts` : contrats fortement typés pour `acquire`, `renew`, `release`, `expire` et les lectures, avec injection de l’horloge via `now` pour les tests.
- `factory/src/adapters/persistence/sql/sql-lease-repository.ts` : `SqlLeaseRepository`, son périmètre organisation/workstream configurable et son constructeur `createSqlLeaseRepository`.
- `factory/tests/support/in-memory-sql-client.mjs` : support des séquences `nextval`, clauses `IN`/`IS NULL`, comparaisons, `ORDER BY`, `LIMIT`, `FOR UPDATE [SKIP LOCKED]`, expressions d’update et transactions nécessaires aux tests du protocole. `FOR UPDATE` reste un no-op en mémoire ; la concurrence réelle est réservée à PostgreSQL.
- `factory/tests/test-lease-protocol.mjs` : tests du domaine, acquisition/priorité, exclusivité simulée, renew, fencing, release, expiration, re-acquisition, lectures et scénario de concurrence PostgreSQL optionnel.
- `specs/61d66f84_lease_protocol.md` : spécification détaillée du périmètre, des interfaces, des règles SQL et des cas de test.

Aucun barrel, adapter work-unit/worker, fichier de migration, `db.ts`, `unit-of-work.ts` ou fichier `agentos` n’est inclus dans le changement.

## Vérification et utilisation

Le test hors ligne se lance depuis la racine avec :

```sh
node factory/tests/test-lease-protocol.mjs
```

Il utilise le client SQL en mémoire et fonctionne sans Docker. Le test de concurrence stricte est activé uniquement si `DATABASE_URL` ou des variables `PGHOST`/`PGPORT` sont présentes et si le module `pg` est disponible ; sinon il est ignoré avec un message et les tests hors ligne continuent. Pour l’exécuter contre PostgreSQL :

```sh
docker compose -f factory/infra/docker-compose.yml up -d
docker compose -f factory/infra/docker-compose.yml logs -f flyway
export DATABASE_URL="postgres://factory:factory_dev_pass@localhost:5432/coday_factory"
node factory/tests/test-lease-protocol.mjs
```

`DATABASE_URL` est prioritaire ; à défaut, le test accepte `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER` et `PGPASSWORD`. Le scénario ouvre une connexion par worker et vérifie que des acquisitions parallèles ne partagent ni work unit ni fencing token.
