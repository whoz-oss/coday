# Worker runtime local — câblage C2-T2

## Ce qui a changé

Le runtime worker C2 est maintenant accessible depuis une façade locale et peut être lancé contre PostgreSQL sans implémentation ADW réelle. `createLocalWorkerRuntime()` résout la configuration SQL, crée un client PostgreSQL (sauf client injecté), instancie les trois repositories SQL réels — lease, work-unit et worker — puis retourne le runtime avec ses helpers `start`/`stop`. `runLocalWorker()` effectue le même câblage et démarre immédiatement la boucle.

Le launcher utilise par défaut `createDemoWorkExecutor()`. Cet executor journalise le work-unit, attend un court délai configurable (50 ms par défaut), respecte l’`AbortSignal`, puis renvoie `completed` avec le patch `{ executor: 'demo-echo', executedAt: ... }`. Il ne fait aucun dispatch ADW ni appel externe.

La boucle et le vocabulaire C2-T1 sont réexportés sans modification : le câblage reste dans l’entrypoint et les règles de lease/fencing restent portées par le domaine et les adapters SQL existants.

## Fichiers concernés

- `factory/src/entrypoints/worker-runtime.ts` : façade TypeScript, options de configuration, logger console, executor de démonstration et fonctions `createLocalWorkerRuntime` / `runLocalWorker`.
- `factory/src/entrypoints/factory-operational.ts` : réexporte les types, `WorkerRuntime` et l’entrypoint local dans la surface opérationnelle.
- `factory/lib/worker-runtime.mjs` : façade JS stateless qui réexporte le bundle généré.
- `factory/runtime/factory-operational.mjs` : bundle régénéré ; il expose notamment `WorkerRuntime`, `createDemoWorkExecutor`, `createLocalWorkerRuntime`, `runLocalWorker` et `createConsoleWorkerRuntimeLogger`.
- `factory/toolchain/build.mjs` : ajustement du build pour produire des métadonnées d’inputs stables, puis régénération du bundle.
- `factory/infra/README.md` : procédure PostgreSQL conteneurisé, variables `PG*`, `WORKER_ID`, `LEASE_TTL_MS`, lancement Node, insertion SQL/JS et observation du cycle `created → running → completed`.
- `factory/tests/test-worker-runtime-entrypoint.mjs` : couverture de l’executor (succès, logs, annulation), du câblage sur `SqlClient` mémoire, du cycle complet, de `runLocalWorker`, de la surface du bundle et de la façade.
- `specs/dbc99c6c_worker_runtime_entrypoint_wiring.md` : plan et critères de la livraison C2-T2.

## Utilisation locale

Depuis la racine, démarrer l’infrastructure et attendre l’application des migrations :

```bash
docker compose -f factory/infra/docker-compose.yml up -d
docker compose -f factory/infra/docker-compose.yml logs -f flyway
```

Le README infra documente les valeurs par défaut de `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`, `PGPOOL_MAX` et `PGSSL`. `WORKER_ID` vaut `local-worker-1` par défaut et `LEASE_TTL_MS` vaut `30000`. Le workstream et l’organisation sont `default` par défaut et peuvent être remplacés dans les options de `createLocalWorkerRuntime()`.

Le lancement documenté est un script Node ESM qui importe `createLocalWorkerRuntime` depuis `factory/lib/worker-runtime.mjs`, démarre le worker, installe les handlers `SIGINT`/`SIGTERM` et appelle `worker.stop({ drainTimeoutMs: 5000 })` à l’arrêt. Le driver `pg` doit être résolvable par le processus (`npm install pg` ou `pnpm add -w pg`) car il est chargé à la création du pool.

Pour une démonstration, insérer d’abord l’organisation et le workstream `default`, puis un work-unit `wu-demo-1` de type `demo-echo` avec le statut `created` dans `work_units` (le README fournit aussi une variante JS utilisant `createPgPoolClient`). Les requêtes de suivi sur `work_units` et `work_unit_leases` permettent d’observer :

1. `created` après l’insertion ;
2. `running` lorsque le worker acquiert le lease et passe le worker à `busy` ;
3. `completed` après l’executor de démonstration et la release atomique du lease, avec retour du worker à `idle`.

Un délai de démo plus long peut être passé à `createDemoWorkExecutor({ delayMs })` pour rendre `running` observable.

## Vérification

Les tests autonomes ajoutés peuvent être lancés sans PostgreSQL, Docker ni driver `pg` :

```bash
node factory/tests/test-worker-runtime-entrypoint.mjs
node factory/tests/test-worker-runtime-core.mjs
```

Le premier exerce les adapters SQL via le `SqlClient` mémoire tout en vérifiant la façade générée ; le second couvre la boucle C2-T1. Le bundle `factory/runtime/factory-operational.mjs` est un artefact généré : toute nouvelle modification de la surface TypeScript doit être suivie de l’unique commande de génération prévue, `node factory/toolchain/build.mjs`, plutôt que d’une édition manuelle.
