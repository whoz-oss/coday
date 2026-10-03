# Portage de la gouvernance admin dans cockpit-v2

## Ce qui a changé

L’écran ADMIN de gouvernance des artefacts et des définitions de workflow est désormais disponible dans le cockpit Angular v2, via la route `/reglages` déjà utilisée par la sidebar. La page autonome `AdminPageComponent` regroupe quatre opérations :

- **Garbage collection** : choix du dry-run, lancement de la réconciliation et affichage des compteurs de staging recyclé, blobs scannés, lignes de métadonnées, anomalies et timestamp.
- **Purge d’artefact** : saisie de l’identifiant et d’un motif facultatif, confirmation explicite avant suppression irréversible, puis affichage du statut, de l’artefact, de la taille libérée et du motif.
- **Legal hold** : saisie de l’artefact, activation/désactivation du hold et motif facultatif, avec restitution de l’état appliqué.
- **Workflow definitions** : chargement initial, import d’un fichier JSON, rafraîchissement, tableau type/version/hash et suppression confirmée de chaque définition.

Les contrôles et la présentation utilisent les composants Material et les tokens `sf-*` de cockpit-v2. Le fil d’Ariane est défini à `Gouvernance des artefacts` lors de la création de la page.

## API et autorisation

`FactoryApiService` expose les six opérations administratives : GC, purge, legal hold, lecture, upload multipart et suppression de définition. Les routes d’artefact et de définition encodent leurs paramètres de chemin. Les appels conservent les conventions existantes : `X-Correlation-Id`, propagation facultative de `namespaceId` en query/header, déballage de l’enveloppe `{ data: ... }` et normalisation des erreurs. L’upload met le fichier sous le champ `file` et ne force pas `Content-Type`, afin de laisser HttpClient générer la frontière multipart.

L’autorisation reste détenue par le serveur. Une réponse HTTP 403 ou le code `FORBIDDEN_ADMIN_REQUIRED` verrouille toutes les actions et tous les champs admin, puis affiche un bandeau contenant le message serveur et le code. Angular rend ce texte par interpolation, donc il est échappé. Les autres erreurs restent dans le bandeau de la section concernée sans faire tomber la page.

`confirm-dialog.component.ts` fournit la modale de confirmation réutilisée avant une purge ou une suppression de définition ; l’annulation ne déclenche aucun appel HTTP.

## Fichiers concernés

- `apps/cockpit-v2/src/app/app.routes.ts` — ajout du chargement lazy de `/reglages`.
- `apps/cockpit-v2/src/app/core/factory-api.service.ts` — types de résultats, six méthodes admin et helpers DELETE/FormData.
- `apps/cockpit-v2/src/app/core/factory-api.service.spec.ts` — tests des méthodes, routes, corps, namespace, corrélation, FormData et erreurs normalisées.
- `apps/cockpit-v2/src/app/features/admin/admin-page.component.ts` — état par signaux, appels API, confirmations, formatage des tailles et gestion 403.
- `apps/cockpit-v2/src/app/features/admin/admin-page.component.html` — les quatre sections, résultats, erreurs et tableau.
- `apps/cockpit-v2/src/app/features/admin/admin-page.component.scss` — mise en forme des panneaux, résultats, alertes et tableau.
- `apps/cockpit-v2/src/app/features/admin/confirm-dialog.component.ts` — dialogue de confirmation des actions destructives.
- `apps/cockpit-v2/src/app/features/admin/admin-page.component.spec.ts` — tests de rendu, breadcrumb, opérations, confirmations, refresh et verrouillage 403.
- `specs/45b3c21e_artifact_admin_governance.md` — plan et critères de validation du portage.

## Utilisation et vérification

Ouvrir `/reglages` dans cockpit-v2. Les définitions sont demandées au chargement ; sélectionner un fichier `.json` puis utiliser **Importer la définition** pour l’envoyer, ou **Rafraîchir** pour relire la liste. Les actions de purge et de suppression nécessitent une confirmation. Une erreur d’autorisation doit rendre les commandes indisponibles et conserver le message serveur visible.

Les tests unitaires couvrent le service et le composant, notamment l’annulation des confirmations, le rendu des métriques GC, l’upload et les refresh. Pour vérifier le projet avec les commandes prévues :

```bash
pnpm nx test cockpit-v2
pnpm nx lint cockpit-v2
pnpm nx build cockpit-v2
```

Le diff ajoute les tests et ces cibles de validation ; leur exécution n’est pas enregistrée dans ce changement.
