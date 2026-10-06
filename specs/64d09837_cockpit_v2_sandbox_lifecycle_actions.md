# Plan - Exposer les actions de cycle de vie sur la carte sandbox et la page Sandboxes (`apps/cockpit-v2`)

## Vue d'ensemble

L'objectif de cette tâche est d'exposer les actions de cycle de vie (`stop`, `remove`, `restore`) sur la carte sandbox (`SandboxCardComponent`) et la page Sandboxes (`SandboxesPageComponent`).

Le store `FactoryStore` (`apps/cockpit-v2/src/app/core/factory.store.ts`) fournit déjà les méthodes `stop(workflowId)`, `remove(workflowId)` et `restore(workflowId)`.

## Directives et Interdictions Strictes
- **NE PAS modifier** les fichiers dans `apps/cockpit-v2/src/app/core/` (`factory.store.ts`, `models.ts`, `factory-api.service.ts`, etc.).
- **NE PAS modifier** `apps/cockpit-v2/src/app/features/history/` ni `apps/cockpit-v2/src/app/layout/`.
- Respecter l'architecture et les composants existants (notamment `ConfirmDialogComponent` pour la confirmation de suppression).

---

## Fichiers à modifier

1. `apps/cockpit-v2/src/app/features/sandboxes/sandbox-card/sandbox-card.component.ts`
2. `apps/cockpit-v2/src/app/features/sandboxes/sandbox-card/sandbox-card.component.html`
3. `apps/cockpit-v2/src/app/features/sandboxes/sandboxes-page.component.ts`
4. `apps/cockpit-v2/src/app/features/sandboxes/sandboxes-page.component.html`
5. `apps/cockpit-v2/src/app/features/sandboxes/sandbox-card/sandbox-card.component.spec.ts`
6. `apps/cockpit-v2/src/app/features/sandboxes/sandboxes-page.component.spec.ts`

---

## Détail des modifications

### 1. `SandboxCardComponent` (`sandbox-card.component.ts` & `.html`)

#### TypeScript (`sandbox-card.component.ts`)
- **Élargir le type `SandboxAction`** :
  ```typescript
  export type SandboxAction =
    | 'ask'
    | 'workflow'
    | 'conversation'
    | 'log'
    | 'commits'
    | 'stop'
    | 'remove'
    | 'restore'
  ```
- **Injecter `FactoryStore`** :
  ```typescript
  private readonly store = inject(FactoryStore)
  ```
- **Ajouter un getter / méthode d'assistance `canStop`** :
  - Un run peut être arrêté si :
    1. Le statut de la sandbox est `'working'` (`s.status === 'working'`).
    2. Une tentative active est résolvable (`s.run?.id` existe et `this.store.session(s.run.id)?.activeAttemptId` n'est pas undefined/vide).
  ```typescript
  protected get canStop(): boolean {
    const s = this.sandbox()
    if (s.status !== 'working' || !s.run?.id) return false
    const session = this.store.session(s.run.id)
    return Boolean(session?.activeAttemptId)
  }
  ```

#### Template HTML (`sandbox-card.component.html`)
- **Rendre conditionnellement les boutons d'actions dans le footer ou la carte** :
  - **Pour une sandbox NON détruite (`s.status !== 'destroyed'`)** :
    - Conserver les actions existantes : `'ask'`, `'workflow'`, `'conversation'`, `'log'`, `'commits'`.
    - Bouton **"Arrêter"** (`action.emit('stop')`) :
      - Visible uniquement si `canStop` est `true`.
      - Style : `mat-stroked-button` avec classe `sf-secondary`.
    - Bouton **"Supprimer"** (`action.emit('remove')`) :
      - Visible uniquement sur un run non détruit (`s.status !== 'destroyed'`).
      - Style : `mat-button` (ou `mat-stroked-button`) avec classe `sf-danger` ou `sf-ghost sf-danger`.
  - **Pour une sandbox DÉTRUITE (`s.status === 'destroyed'`)** :
    - Afficher un footer avec le bouton **"Restaurer"** (`action.emit('restore')`) :
      - Style : `mat-stroked-button` ou `mat-button` avec classe `sf-secondary`.

*Exemple de structure pour le footer dans `sandbox-card.component.html` :*
```html
@if (s.status !== 'destroyed') {
  <footer class="actions">
    <button mat-flat-button class="sf-primary" (click)="action.emit('ask')">Demander à Archay…</button>
    <button mat-stroked-button class="sf-secondary" (click)="action.emit('workflow')">Lancer un workflow…</button>
    @if (canStop) {
      <button mat-stroked-button class="sf-secondary" (click)="action.emit('stop')">Arrêter</button>
    }
    <button mat-button class="sf-ghost" (click)="action.emit('conversation')">Conversation</button>
    <button mat-button class="sf-ghost" (click)="action.emit('log')">Log</button>
    <button mat-button class="sf-ghost" (click)="action.emit('commits')">Récupérer les commits</button>
    <button mat-button class="sf-ghost sf-danger" (click)="action.emit('remove')">Supprimer</button>
  </footer>
} @else {
  <footer class="actions">
    <button mat-stroked-button class="sf-secondary" (click)="action.emit('restore')">Restaurer</button>
  </footer>
}
```

---

### 2. `SandboxesPageComponent` (`sandboxes-page.component.ts` & `.html`)

#### TypeScript (`sandboxes-page.component.ts`)
- Injecter `MatDialog` depuis `@angular/material/dialog`.
- Importer `ConfirmDialogComponent` et `ConfirmDialogData` depuis `../admin/confirm-dialog.component`.
- Implémenter la logique dans `onAction(workflowId: string, action: SandboxAction)` (remarque : l'argument de `onAction` transmis dans la carte peut être le nom ou l'ID du run `s.run?.id ?? s.name` ou le `workflowId` du run).
  *Note : Dans `sandboxes-page.component.html`, la boucle passe actuellement `(action)="onAction(s.run?.id ?? s.name, $event)"` ou `(action)="onAction(s.name, $event)"`. Il est important de passer l'identifiant du workflow (`s.run?.id ?? s.name`) ou de s'assurer que `workflowId` résout bien le run dans `store`.*
- Traitement de `onAction(workflowId: string, action: SandboxAction)` :
  - **`action === 'stop'`** : Appeler `this.store.stop(workflowId)`.
  - **`action === 'remove'`** :
    - Ouvrir la boîte de dialogue avec `MatDialog` :
      ```typescript
      const dialogRef = this.dialog.open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
        data: {
          title: 'Supprimer la sandbox',
          message: "Voulez-vous vraiment supprimer cette sandbox ? L'action est récupérable via le toggle des sandboxes détruites.",
          confirmLabel: 'Supprimer',
          destructive: true,
        },
        width: '440px',
      })
      dialogRef.afterClosed().subscribe((confirmed) => {
        if (confirmed) {
          this.store.remove(workflowId)
        }
      })
      ```
  - **`action === 'restore'`** : Appeler `this.store.restore(workflowId)`.
  - **Autres actions** (`'ask'`, `'workflow'`, etc.) : Ne rien faire ou gérer de manière neutre comme précédemment.

#### Template HTML (`sandboxes-page.component.html`)
- S'assurer que le premier paramètre de `onAction` passe bien l'identifiant utilisable par le store : `(action)="onAction(s.run?.id ?? s.name, $event)"`.

---

### 3. Tests unitaires

#### `sandbox-card.component.spec.ts`
- Mettre à jour les stubs/mocks si `FactoryStore` est injecté dans `SandboxCardComponent` (fournir un `FactoryStore` mock/stub dans `TestBed.configureTestingModule`).
- Tester l'affichage du bouton "Arrêter" :
  - Quand `s.status === 'working'` ET `store.session(id)?.activeAttemptId` est présent -> le bouton "Arrêter" est présent et émet `'stop'` au clic.
  - Quand `s.status === 'working'` MAIS `activeAttemptId` n'est pas présent -> le bouton "Arrêter" n'est PAS affiché.
  - Quand `s.status !== 'working'` -> le bouton "Arrêter" n'est PAS affiché.
- Tester l'affichage du bouton "Supprimer" :
  - Quand `s.status !== 'destroyed'` -> le bouton "Supprimer" est affiché et émet `'remove'` au clic.
- Tester l'affichage du bouton "Restaurer" :
  - Quand `s.status === 'destroyed'` -> le bouton "Restaurer" est affiché et émet `'restore'` au clic.

#### `sandboxes-page.component.spec.ts`
- Étendre le stub `FactoryStore` pour inclure des spy / méthodes `stop`, `remove`, `restore`.
- Configurer les providers de test pour inclure `MatDialog` (ou mocker `MatDialog`).
- Tester le routage de l'action `'stop'` -> `store.stop(workflowId)` est appelé.
- Tester le routage de l'action `'remove'` :
  - `MatDialog.open` est appelé avec la configuration requise (`ConfirmDialogComponent`, titre "Supprimer la sandbox", message, confirmLabel "Supprimer", destructive: true).
  - Si l'utilisateur confirme (`afterClosed()` émet `true`) -> `store.remove(workflowId)` est appelé.
  - Si l'utilisateur annule (`afterClosed()` émet `false` ou `undefined`) -> `store.remove` n'est pas appelé.
- Tester le routage de l'action `'restore'` -> `store.restore(workflowId)` est appelé.

---

## Vérification et validation
1. Lancer les tests unitaires cockpit-v2 :
   `pnpm nx test cockpit-v2`
2. Lancer le lint cockpit-v2 :
   `pnpm nx affected -t lint --base="$(cat /work/data/baseline)"`
3. Vérifier que l'application build sans erreur TS :
   `pnpm nx affected -t build --base="$(cat /work/data/baseline)"`
