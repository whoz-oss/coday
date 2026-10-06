# Durcissement de la frontière HTTP Factory

La tâche B6-T2b renforce le bord HTTP pour un serveur Factory partagé, en conservant les signatures publiques et sans modifier l’émission JWT, le pont d’identité, les resolvers, le seam admin ou le bundle opérationnel.

## Ce qui a changé

- `factory/dashboard/http-utils.mjs`
  - `loopback-dev` n’est activé que si l’adresse du socket est loopback et que `allowLoopbackDev` vaut explicitement `true` (ou que `FACTORY_ALLOW_LOOPBACK_DEV === 'true'`). Une requête non authentifiée qui ne satisfait pas ces deux conditions devient `anonymous`.
  - Le contexte `anonymous` est forcé à zéro privilège : scopes et rôles vides, principal et identifiants d’organisation/workstream/squad nuls, même si le resolver injecté est permissif.
  - Le scope `['*']` n’est produit que pour un contexte `loopback-dev` explicitement autorisé. Les scopes vérifiés d’un JWT ou d’une signature proxy sont conservés tels quels.
  - `send()` n’émet plus de wildcard CORS codé en dur. Le nouvel helper `resolveCorsOrigin()` ne renvoie une origine que si elle figure dans la liste configurée, avec prise en charge d’un opt-in explicite `*`.

- `factory/dashboard/composition-root.mjs`
  - `createIdentityBoundaryOptions()` transmet l’opt-in strict `FACTORY_ALLOW_LOOPBACK_DEV` à la frontière d’identité, sans changer la forme publique énumérable de la bind policy.
  - `loadConfig()` lit `FACTORY_ALLOWED_ORIGINS`, avec alias `FACTORY_CORS_ORIGIN`, sous forme de liste séparée par virgules. Une configuration absente donne une liste vide et donc aucun header CORS cross-origin.
  - OPTIONS, les réponses SSE et la page HTML utilisent l’origine CORS résolue par requête au lieu de `Access-Control-Allow-Origin: *`.

## Vérification et compatibilité

- `factory/tests/test-boundary-hardening.mjs` ajoute une suite hors ligne couvrant : zéro privilège anonyme, refus du loopback sans opt-in ou depuis une adresse distante, loopback IPv4/IPv6 autorisé avec opt-in, absence de wildcard hors loopback-dev, CORS allow-listé, refus des opérations admin anonymes et comportement fail-closed d’un bind distant.
- `factory/tests/test-identity-trust-context.mjs` explicite l’opt-in loopback dans les cas qui attendent encore `loopback-dev`.
- `factory/tests/test-artifact-global-wiring.mjs` active explicitement le flag pour son scénario HTTP loopback.
- `factory/tests/test-factory-bind-policy.mjs` reste compatible avec la politique de bind existante : loopback par défaut, bind distant seulement avec `FACTORY_UNSAFE_ALLOW_REMOTE_BIND=true`, lequel conserve néanmoins un contexte de requête anonyme sans droits.
- La spécification de référence est consignée dans `specs/8493ffe2_boundary_hardening.md`.

## Utilisation / contrôle manuel

Pour le développement local, définir `FACTORY_ALLOW_LOOPBACK_DEV=true`; sans cette valeur exacte, même `127.0.0.1` reste anonyme. Pour autoriser le dashboard cross-origin, définir par exemple `FACTORY_ALLOWED_ORIGINS=https://dashboard.example.com,http://localhost:3000`; seules les origines exactes listées sont réfléchies. Ne pas configurer cette variable pour conserver le comportement same-origin sans header CORS.

Exécuter la suite dédiée et les régressions concernées :

```sh
node factory/tests/test-boundary-hardening.mjs
node factory/tests/test-identity-trust-context.mjs
node factory/tests/test-factory-bind-policy.mjs
node factory/tests/test-composition-root-source.mjs
```
