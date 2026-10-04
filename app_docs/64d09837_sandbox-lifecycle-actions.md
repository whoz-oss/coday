# Actions de cycle de vie des sandboxes

## Ce qui a changé

La carte et la page Sandboxes de `apps/cockpit-v2` exposent désormais les actions `stop`, `remove` et `restore`, en plus des actions existantes (`ask`, `workflow`, `conversation`, `log`, `commits`). Les actions de cycle de vie sont routées vers les méthodes déjà disponibles de `FactoryStore`.

- `sandbox-card.component.ts` injecte `FactoryStore` et ajoute le getter `canStop`. Le bouton **Arrêter** n'est disponible que pour une sandbox `working` ayant un `run.id` et une session correspondante dont `activeAttemptId` est résolvable. Aucun identifiant de tentative n'est fabriqué côté interface.
- `sandbox-card.component.html` affiche les actions normales et **Supprimer** pour les cartes non détruites. Une carte détruite affiche uniquement **Restaurer** dans son footer ; elle ne propose ni suppression ni arrêt.
- `sandboxes-page.component.html` transmet à la page l'identifiant du workflow (`s.run?.id ?? s.name`) plutôt que de toujours utiliser le nom de la sandbox.
- `sandboxes-page.component.ts` route **Arrêter** vers `store.stop`, **Restaurer** vers `store.restore`, et protège **Supprimer** par `ConfirmDialogComponent`. La boîte de dialogue utilise le titre « Supprimer la sandbox », le message indiquant que l'action est récupérable via le toggle des sandboxes détruites, le libellé « Supprimer » et `destructive: true`. La suppression n'est appelée qu'après une confirmation explicite (`true`). Les autres actions restent neutres.

Les boutons conservent les composants et classes Material/du design system existants, notamment `mat-stroked-button`, `sf-secondary` et `sf-danger` pour la suppression.

## Fichiers concernés

- `apps/cockpit-v2/src/app/features/sandboxes/sandbox-card/sandbox-card.component.ts`
- `apps/cockpit-v2/src/app/features/sandboxes/sandbox-card/sandbox-card.component.html`
- `apps/cockpit-v2/src/app/features/sandboxes/sandbox-card/sandbox-card.component.spec.ts`
- `apps/cockpit-v2/src/app/features/sandboxes/sandboxes-page.component.ts`
- `apps/cockpit-v2/src/app/features/sandboxes/sandboxes-page.component.html`
- `apps/cockpit-v2/src/app/features/sandboxes/sandboxes-page.component.spec.ts`
- `specs/64d09837_cockpit_v2_sandbox_lifecycle_actions.md` contient également le plan détaillé associé à cette évolution.

## Vérification

Les tests unitaires de la carte couvrent l'affichage et l'émission de `stop`, l'absence de l'arrêt sans tentative active résolvable, l'absence de l'arrêt pour un run non actif, ainsi que les actions `remove` et `restore` selon l'état détruit ou non détruit.

Les tests unitaires de la page couvrent le routage vers `store.stop` et `store.restore`, l'ouverture de `ConfirmDialogComponent` avec sa configuration de suppression, l'appel à `store.remove` après confirmation et l'absence d'appel en cas d'annulation.

Pour vérifier localement :

```bash
pnpm nx test cockpit-v2
pnpm nx affected -t lint --base="$(cat /work/data/baseline)"
pnpm nx affected -t build --base="$(cat /work/data/baseline)"
```
