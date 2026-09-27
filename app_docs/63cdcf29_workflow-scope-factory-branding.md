# Workflows multi-namespace et marque Factory

## Ce qui a changé

`GET /api/factory/workflows` accepte désormais `namespaceId` comme filtre optionnel. Sans paramètre, ou avec une valeur vide/blanche, le contrôleur résout toujours le `TenantScope` depuis le `TrustContext` vérifié et demande les projections de tous les namespaces de ce scope (`organizationId` + `workstreamId`). Le scope n’est jamais dérivé d’un paramètre client. Avec un namespace non vide, le filtrage existant est conservé. La réponse garde `namespaceId` dans l’enveloppe, avec une chaîne vide pour une liste scope-wide.

Le changement est porté par :

- `WorkflowController.kt` : la route de liste appelle la résolution du caller avec `requireNamespace = false`.
- `WorkflowService.kt` et `WorkflowRepository.kt` : `listProjections` accepte `String?`, normalise les valeurs blanches et conserve la validation de `state`.
- `JdbcWorkflowRepository.kt` : la requête applique toujours `organization_id` et `workstream_id`; la clause `namespace_id` n’est ajoutée que lorsqu’un namespace est fourni.
- `factory-openapi.yaml` : le résumé de l’opération décrit le périmètre tenant et le filtre optionnel.

## Cockpit

Dans `factory/dashboard/js/views/projection.mjs`, `listPath` n’envoie plus `namespaceId=` lorsque le cockpit n’a pas de namespace actif : la vue Runs appelle alors `/api/factory/workflows?state=active` (ou `removed`). Si un namespace est présent, il reste envoyé comme filtre optionnel.

Dans `factory/dashboard/cockpit.html`, le titre de page, le libellé de marque et l’ARIA label passent à `Factory`. Les classes CSS et les routes ne changent pas.

## Vérification

`WorkflowControllerHttpTest.kt`, qui étend le `DomainIntegrationTest` existant, couvre :

1. une liste sans `namespaceId` contenant des workflows publiés dans deux namespaces du même scope;
2. une liste avec `namespaceId` ne contenant que le namespace demandé;
3. l’isolation entre deux `TenantScope`.

Pour vérifier le contrat, lancer le test d’intégration Factory concerné (ou la suite Gradle complète) et contrôler que la requête sans namespace retourne HTTP 200 avec les workflows du scope appelant, sans exposer ceux d’un autre scope. Pour le cockpit, inspecter `ProjectionController.listPath()` ou la vue Runs et confirmer l’absence de `namespaceId=` dans l’URL scope-wide.

Le fichier `specs/63cdcf29_optional_namespace_workflow_list_factory_branding.md` conserve également le plan d’architecture, les décisions de scope, les étapes de changement et le plan de vérification associés à cette évolution.
