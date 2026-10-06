# Documentation de la reconnaissance Coday / AgentOS / Factory

## Ce qui a changé

Le changement ajoute une reconnaissance architecturale complète dans `docs/coday-auth-usergroups-recon.md`. Le document est explicitement une proposition d’intégration sans modification de code applicatif et prépare la décision B6.

Il couvre cinq axes :

- **Authentification Coday** : le flag `--auth`, la résolution priorisée de l’identité depuis `cf-access-jwt-assertion` puis `x-forwarded-email`, le repli sur l’utilisateur OS lorsque l’authentification est désactivée, le rejet des comptes système et la persistance par `UserService` sous `~/.coday/users/<sanitized>/user.yaml`. Le document distingue ce qui est délégué au reverse proxy de ce qui reste local à Coday, et précise qu’AgentOS n’est pas dans cette chaîne d’authentification.
- **Groupes et rôles** : comparaison entre `userConfig.groups` et le rôle local `CODAY_ADMIN` de Coday, d’une part, et les UserGroups AgentOS namespace-scoped, leurs `externalId`, `agentIds` et rôles de membre `ADMIN`/`MEMBER`, d’autre part. Les modèles TypeScript générés, les modèles Kotlin et les contrôles Spring `@PreAuthorize` sont cités.
- **Jonction Factory** : description de `TrustContext`, de l’extraction fail-closed dans `factory/dashboard/http-utils.mjs`, de la vérification JWT/proxy-signature via le Fake IdP, de la séparation loopback/anonymous et de la résolution serveur des memberships. Le document souligne que les en-têtes client de rôle ou de tenant ne sont pas une source d’autorité.
- **Proposition d’intégration** : Identity Bridge produisant une preuve signée, puis `MembershipResolver` alimenté par Coday/AgentOS. Une table mappe `CODAY_ADMIN`/ADMIN namespace vers `admin`, l’utilisateur standard vers `dev` et les groupes produit dédiés vers `po`/`pm`, avec un flux de bout en bout et des contraintes de conception.
- **Gaps et décisions B6** : signatures non vérifiées côté Coday, identifiants email/externalId/principalId, différence groupes/rôles, projection namespace/tenant, source de vérité locale, Fake IdP, loopback/anonyme, risques et dix décisions à trancher avant implémentation.

Le document contient aussi un index des sources et un glossaire pour rendre les chemins et les termes directement exploitables par l’équipe suivante.

## Fichiers porteurs

- `docs/coday-auth-usergroups-recon.md` — livrable architectural principal, 930 lignes ajoutées, avec extraits de code, tableaux de comparaison, schéma de flux et décisions ouvertes.
- `specs/b2828ec5_coday_auth_usergroups_recon.md` — spécification du livrable et de sa structure, rappelant le périmètre documentation-only, les cinq sections attendues et les points de vérification.

## Utilisation et vérification

Lire `docs/coday-auth-usergroups-recon.md` avant le codage B6, en particulier les sections 3 et 4 pour le seam `checkAdminAuthorization`/`MembershipResolver`, puis la section 5 pour les décisions bloquantes. Les citations permettent de revenir aux composants concernés sans déduire l’architecture uniquement depuis la proposition.

Aucun code applicatif, test, migration ou configuration n’est modifié par ce changement. La vérification attendue est donc documentaire : confirmer la présence des cinq sections, des chemins et snippets cités, et l’absence de modifications hors des deux fichiers documentaires listés ci-dessus. Aucun test n’a besoin d’être exécuté pour ce changement documentation-only.
