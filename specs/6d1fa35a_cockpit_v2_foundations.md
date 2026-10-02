# Plan T1 : Fondations apps/cockpit-v2, socle partagé et service /cockpit-v2

## Context & Objectives
Mettre en place la structure initiale de l'application Angular `cockpit-v2` dans le monorepo Nx, le socle de composants / modèles partagés applicatif (Angular standalone), et enregistrer la route/service backend `/cockpit-v2` dans le serveur Express (`apps/server`).

ADW Session ID: `6d1fa35a`

## Scope of Changes

### 1. Frontend: Application `apps/cockpit-v2/`
- Structure d'application Angular v18+ standalone sous `apps/cockpit-v2/`:
  - `apps/cockpit-v2/project.json` : Configuration du projet Nx (`name: "cockpit-v2"`, `projectType: "application"`, targets: `build`, `build-angular`, `serve`, `lint`, `test`).
  - `apps/cockpit-v2/package.json` : Déclaration du package local.
  - `apps/cockpit-v2/tsconfig.json`, `apps/cockpit-v2/tsconfig.app.json`, `apps/cockpit-v2/tsconfig.spec.json` : Configurations TypeScript alignées sur `apps/client`.
  - `apps/cockpit-v2/eslint.config.mjs` : Configuration ESLint flat avec règles Angular.
  - `apps/cockpit-v2/jest.config.ts` : Configuration Jest unit tests.
  - `apps/cockpit-v2/public/` ou `assets/` : Assets statiques.
  - `apps/cockpit-v2/src/index.html` : Entry HTML.
  - `apps/cockpit-v2/src/styles.scss` : Styles globaux.
  - `apps/cockpit-v2/src/test-setup.ts` : Setup Jest Angular.
  - `apps/cockpit-v2/src/main.ts` : Point d'entrée Angular `bootstrapApplication(CockpitV2Component, appConfig)`.
  - `apps/cockpit-v2/src/app/app.config.ts` : Providers Angular (Router, HttpClient, Animations, provideZoneChangeDetection, global error listeners).
  - `apps/cockpit-v2/src/app/app.routes.ts` : Routes Angular de cockpit-v2.
  - `apps/cockpit-v2/src/app/cockpit-v2.component.ts` (ou `app.component.ts` / `CockpitV2Component`) : Composant racine standalone (`selector: 'cockpit-v2-root'`, template affichant le layout cockpit-v2, la navigation et le router-outlet).

### 2. Frontend Shared Foundations: `apps/cockpit-v2/src/app/shared/`
- Socle de composants et modèles partagés standalone sous `apps/cockpit-v2/src/app/shared/`:
  - `models/` : Interfaces/types de base du cockpit-v2 (ex. `CockpitStatus`, `CockpitInfo`, etc.).
  - `components/` : Composants partagés UI/layout standalone (ex. `HeaderComponent` ou `StatusCardComponent`).
  - `services/` : Services partagés de l'application (ex. `CockpitV2ApiService` appelant l'API `/api/cockpit-v2`).
  - `index.ts` : Barrel export pour simplifier les imports dans cockpit-v2.

### 3. Backend Express API Route & Service: `apps/server/src/app/cockpit-v2/`
- Backend sous `apps/server/src/app/cockpit-v2/` (ou `apps/server/src/lib/cockpit-v2.routes.ts` selon les règles d'arborescence) :
  - Créer `apps/server/src/app/cockpit-v2/cockpit-v2.routes.ts` (ou `apps/server/src/lib/cockpit-v2.routes.ts`) : Routeur Express exposant `/api/cockpit-v2` (GET status/info, etc.).
  - Brancher l'enregistrement de `registerCockpitV2Routes(app, ...)` dans `apps/server/src/server.ts`.

### 4. Workspace Registration
- Vérifier / s'assurer que Nx reconnaît le projet `cockpit-v2` via `apps/cockpit-v2/project.json`.
- Ajouter des scripts dans `package.json` si approprié (ex: `"cockpit-v2": "nx run cockpit-v2:serve"`).

## Step-by-Step Implementation Plan

### Step 1: Create Backend Route & Service (`apps/server`)
1. Create `apps/server/src/lib/cockpit-v2.routes.ts` (or `apps/server/src/app/cockpit-v2/cockpit-v2.routes.ts`):
   - Export function `registerCockpitV2Routes(app: express.Application, getUsernameFn: ...)`
   - Implement route `GET /api/cockpit-v2/status` or `GET /api/cockpit-v2` returning JSON `{ status: 'ok', version: '2.0.0', service: 'cockpit-v2' }`.
2. Import and call `registerCockpitV2Routes(app, getUsername)` in `apps/server/src/server.ts`.
3. Unit test for backend route in `apps/server/src/lib/cockpit-v2.routes.spec.ts`.

### Step 2: Create Angular App Structure (`apps/cockpit-v2`)
1. Create configuration files:
   - `apps/cockpit-v2/project.json`
   - `apps/cockpit-v2/package.json`
   - `apps/cockpit-v2/tsconfig.json`, `tsconfig.app.json`, `tsconfig.spec.json`
   - `apps/cockpit-v2/eslint.config.mjs`
   - `apps/cockpit-v2/jest.config.ts`
   - `apps/cockpit-v2/src/test-setup.ts`
2. Create src files:
   - `apps/cockpit-v2/src/index.html`
   - `apps/cockpit-v2/src/styles.scss`
   - `apps/cockpit-v2/src/main.ts`
3. Create App Component & Routing:
   - `apps/cockpit-v2/src/app/app.config.ts`
   - `apps/cockpit-v2/src/app/app.routes.ts`
   - `apps/cockpit-v2/src/app/cockpit-v2.component.ts` & template/styles.

### Step 3: Create Shared Layer (`apps/cockpit-v2/src/app/shared`)
1. `shared/models/cockpit-v2.model.ts`: Interface `CockpitV2Status`.
2. `shared/services/cockpit-v2-api.service.ts`: Angular Service consuming `/api/cockpit-v2`.
3. `shared/components/cockpit-header/cockpit-header.component.ts`: Standalone Angular component displaying header / status.
4. `shared/index.ts`: Barrel export.

### Step 4: Verification & Quality Checks
1. Run `pnpm nx test server` to verify Express routes.
2. Run `pnpm nx test cockpit-v2` to verify frontend unit tests.
3. Run `pnpm nx lint cockpit-v2` and `pnpm nx lint server`.
4. Run `pnpm nx build cockpit-v2`.

## Verification Commands
- `pnpm nx test cockpit-v2`
- `pnpm nx test server`
- `pnpm nx lint cockpit-v2`
- `pnpm nx build cockpit-v2`
- `pnpm nx affected -t lint --base="$(cat /work/data/baseline)"`
