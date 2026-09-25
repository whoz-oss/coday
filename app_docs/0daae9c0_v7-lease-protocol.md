# Migration Flyway V7 : protocole de bail et fencing token

## Ce qui a changé

La migration `factory/infra/migrations/V7__lease_protocol.sql` étend les squelettes créés en V6 sans recréer ni supprimer de table. Elle :

- ajoute à `work_unit_leases` les informations de cycle de vie (`fencing_token`, timestamps d’acquisition/expiration/heartbeat/libération et `expiry_reason`) ;
- ajoute les index d’acquisition et d’expiration des baux ;
- ajoute à `workers` `last_heartbeat_at`, `protocol_version` et `capabilities JSONB`, avec une valeur par défaut de tableau vide et une contrainte vérifiant la forme JSON ;
- ajoute à `work_units` `priority`, `not_before` et `attempt_count`, avec defaults/contrainte de compteur, ainsi que l’index d’éligibilité ordonné pour les lectures `FOR UPDATE SKIP LOCKED`.

Le fencing token est garanti par la base via la séquence PostgreSQL dédiée `work_unit_lease_fencing_seq` (`START WITH 1`, `INCREMENT BY 1`, `CACHE 1`). Le default de `work_unit_leases.fencing_token` appelle `nextval` sur cette séquence. Les commentaires SQL expliquent que `nextval` est non transactionnel et concurrent-safe, ce qui fournit des valeurs strictement croissantes sans recourir à `MAX(...) + 1`.

## Validation hors ligne

`factory/tests/test-v7-migration-schema.mjs` est un runner Node.js autonome, sans PostgreSQL, Docker ni dépendance externe. Il charge les migrations V1 à V7 dans l’ordre, retire et parse les commentaires/instructions SQL, puis vérifie notamment :

- la propreté du SQL, les parenthèses et le caractère ALTER/CREATE-only de V7 ;
- les colonnes, types, nullabilité et defaults ajoutés aux trois tables ;
- la séquence, son incrément positif, le default `nextval` et une simulation de 100 appels strictement croissants ;
- les trois index V7 et leurs colonnes, dont l’ordre `priority DESC, not_before` ;
- les contraintes JSON/compteur, l’isolation par `organization_id` et la conservation des tables du schéma cumulé.

Exécution :

```bash
node factory/tests/test-v7-migration-schema.mjs
```

Le runner affiche le détail des assertions et retourne le code 0 uniquement si toutes passent.

## Documentation et spécification

`factory/infra/README.md` ajoute V7 à l’inventaire Flyway et documente les colonnes, index, contraintes, stratégie de fencing token et commande de validation. La section précise également que V7 est une extension non destructive des tables V6.

`specs/0daae9c0_v7_flyway_migration_lease_protocol.md` formalise le périmètre C1-T0, les interdictions, la structure attendue de la migration, les blocs du test et les étapes de validation.

## Fichiers concernés

- `factory/infra/migrations/V7__lease_protocol.sql`
- `factory/tests/test-v7-migration-schema.mjs`
- `factory/infra/README.md`
- `specs/0daae9c0_v7_flyway_migration_lease_protocol.md`
