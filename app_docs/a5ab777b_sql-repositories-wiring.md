# Câblage des repositories SQL et conformité globale

## Ce qui a changé

Le barrel SQL de la persistence exporte maintenant la surface complète des neuf repositories SQL, avec pour chacun sa classe, sa factory `createSql*Repository` et son type d’options. Le barrel persistence principal ré-exporte cette surface depuis `./sql/index.js`, en complément des exports filesystem existants. Les repositories concernés sont les définitions et instances de workflow, les evidences, les interactions humaines, les tentatives et résultats d’étape agent, l’exécution oracle, l’environnement de travail et la delivery.

Le bundle opérationnel `factory/runtime/factory-operational.mjs` a été régénéré afin de rendre ces classes et factories importables depuis l’entrée runtime. Il contient également le code compilé des adapters SQL nouvellement inclus ; il ne doit pas être édité manuellement.

## Vérification ajoutée

`factory/tests/test-sql-repository-ports-adapters.mjs` décrit les neuf couples filesystem/SQL dans `REPOSITORY_PORTS` et les charge depuis le bundle opérationnel. Pour chaque port, la suite globale vérifie :

- que les deux classes et la factory SQL sont exportées ;
- que le repository SQL s’instancie par constructeur et par factory avec le client SQL en mémoire ;
- que la surface publique SQL couvre toutes les méthodes du prototype filesystem.

Les contrats existants pour les repositories de définition et d’instance restent exécutés sur les implémentations filesystem et SQL, avec le contrôle SQL spécifique du rejet d’une transition sur révision obsolète. La suite de wiring est exécutée via le même mécanisme de scénarios, sans dupliquer les suites de conformité par groupe.

## Fichiers concernés

- `factory/src/adapters/persistence/sql/index.ts` — exports complets des neuf adapters SQL, factories et options.
- `factory/src/adapters/persistence/index.ts` — surface SQL ré-exportée au niveau persistence.
- `factory/tests/test-sql-repository-ports-adapters.mjs` — matrice de wiring/parité et contrats communs.
- `factory/runtime/factory-operational.mjs` — bundle généré exposant la nouvelle surface runtime.
- `specs/a5ab777b_sql_repositories_wiring_conformance.md` — périmètre et procédure de conformité du jalon.

## Utilisation et contrôle

Depuis la racine du dépôt, lancer la vérification globale avec :

```bash
node factory/tests/test-sql-repository-ports-adapters.mjs
```

Les suites de conformité W2 indiquées dans `specs/a5ab777b_sql_repositories_wiring_conformance.md` complètent ce contrôle. En cas de modification des barrels, régénérer le bundle avec `node factory/toolchain/build.mjs` plutôt que de modifier `factory/runtime/factory-operational.mjs` à la main.
