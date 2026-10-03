# Plan: Port section / écran ADMIN dans cockpit-v2 (Angular)

## Context & Goal
Port the legacy artifact & workflow governance admin view (`factory/dashboard/js/views/artifact-admin.mjs`) into `apps/cockpit-v2` behind the existing `/reglages` route/link in the Angular sidebar shell.
All administrative operations interact with `/api/factory/admin/artifacts/*` and `/api/factory/workflow-definitions/*`.
Authorization is server-owned. When any response returns HTTP 403 or code `FORBIDDEN_ADMIN_REQUIRED`, the UI disables administrative actions/inputs and renders the escaped verbatim server error message banner without crashing or masking.

---

## Architecture & File Structure

### Modified Files:
1. `apps/cockpit-v2/src/app/core/factory-api.service.ts`
   - Implement 6 new API methods with proper typing, headers (`X-Correlation-Id`, optional `X-Namespace-Id`/`namespaceId`), envelope unwrapping (`{ data: ... }`), and normalized error handling (`normalizeError`).
2. `apps/cockpit-v2/src/app/core/factory-api.service.spec.ts`
   - Add comprehensive unit tests for all 6 new API methods.
3. `apps/cockpit-v2/src/app/app.routes.ts`
   - Map route `/reglages` to lazily load `AdminPageComponent`.

### New Files:
4. `apps/cockpit-v2/src/app/features/admin/admin-page.component.ts`
   - Standalone component for the Admin page.
5. `apps/cockpit-v2/src/app/features/admin/admin-page.component.html`
   - Template with 4 distinct sections (GC, Purge, Legal Hold, Workflow Definitions) + 403/global error banner.
6. `apps/cockpit-v2/src/app/features/admin/admin-page.component.scss`
   - Styling adhering to cockpit-v2 design tokens (`var(--sf-*)`, `sf-panel`, etc.).
7. `apps/cockpit-v2/src/app/features/admin/admin-page.component.spec.ts`
   - Unit tests covering initial load, 403 handling, forms, dialogs, GC, purge, legal hold, definition list/upload/delete.
8. `apps/cockpit-v2/src/app/features/admin/confirm-dialog.component.ts` (or embedded MatDialog template / component)
   - Confirmation dialog component for destructive actions (purge artifact, delete definition).

---

## Detailed Implementation Steps

### Step 1: `FactoryApiService` Extensions
In `apps/cockpit-v2/src/app/core/factory-api.service.ts`:

Add types if helpful (or typed inline/interfaces):
```typescript
export interface GcReport {
  reclaimedStagingKeys?: string[]
  scannedBlobKeys?: string[]
  scannedMetadataRows?: number
  anomalies?: string[]
  timestamp?: string
  [key: string]: unknown
}

export interface PurgeResult {
  status?: string
  artifactId?: string
  reason?: string
  metadata?: { size?: number; [key: string]: unknown }
  [key: string]: unknown
}

export interface LegalHoldResult {
  status?: string
  artifactId?: string
  legalHold?: boolean
  reason?: string
  [key: string]: unknown
}

export interface WorkflowDefinition {
  workflowType: string
  version: string
  definitionHash?: string
  [key: string]: unknown
}
```

Add methods:
1. `runGarbageCollection(body?: { dryRun?: boolean }, namespaceId?: string): Observable<unknown>`
   - `POST /api/factory/admin/artifacts/gc`
   - Uses `this.post<unknown>('/api/factory/admin/artifacts/gc', body ?? {}, namespaceId)`
2. `purgeArtifact(artifactId: string, body?: { reason?: string }, namespaceId?: string): Observable<unknown>`
   - `POST /api/factory/admin/artifacts/:artifactId/purge`
   - `path = /api/factory/admin/artifacts/${encodeURIComponent(artifactId)}/purge`
3. `setLegalHold(artifactId: string, body: { legalHold: boolean; reason?: string }, namespaceId?: string): Observable<unknown>`
   - `POST /api/factory/admin/artifacts/:artifactId/legal-hold`
   - `path = /api/factory/admin/artifacts/${encodeURIComponent(artifactId)}/legal-hold`
4. `getWorkflowDefinitions(namespaceId?: string): Observable<unknown>`
   - `GET /api/factory/workflow-definitions`
   - Uses `this.request<unknown>('/api/factory/workflow-definitions', {}, namespaceId)`
5. `uploadWorkflowDefinition(file: File, namespaceId?: string): Observable<unknown>`
   - `POST /api/factory/workflow-definitions/upload` using `FormData` (`file` field name, fallback filename `file.name || 'definition.json'`).
   - NOTE: Need a `postFormData` helper or `http.post` directly so `Content-Type` is NOT set explicitly (browser must set multipart boundary). `X-Correlation-Id` and `X-Namespace-Id` must still be passed. Unwrap envelope and normalize error.
6. `deleteWorkflowDefinition(workflowType: string, version: string, namespaceId?: string): Observable<unknown>`
   - `DELETE /api/factory/workflow-definitions/:workflowType/:version`
   - Implement `delete<T>(path: string, namespaceId?: string)` helper in `FactoryApiService` carrying `X-Correlation-Id`, optional `X-Namespace-Id` header & `namespaceId` query param, envelope unwrapping, and error normalization.

#### `uploadWorkflowDefinition` implementation details:
```typescript
uploadWorkflowDefinition(file: File, namespaceId?: string): Observable<unknown> {
  const formData = new FormData()
  formData.append('file', file, file.name || 'definition.json')

  let params = new HttpParams()
  let headers = new HttpHeaders().set('X-Correlation-Id', generateCorrelationId())
  const namespace = namespaceId?.trim()
  if (namespace) {
    headers = headers.set('X-Namespace-Id', namespace)
    params = params.set('namespaceId', namespace)
  }

  return this.http.post<unknown>('/api/factory/workflow-definitions/upload', formData, { params, headers }).pipe(
    map((payload) => (isEnvelope(payload) ? payload.data : payload)),
    catchError((error: unknown) => throwError(() => normalizeError(error)))
  )
}
```

#### `deleteWorkflowDefinition` implementation details:
```typescript
deleteWorkflowDefinition(workflowType: string, version: string, namespaceId?: string): Observable<unknown> {
  const path = `/api/factory/workflow-definitions/${encodeURIComponent(workflowType)}/${encodeURIComponent(version)}`
  return this.delete<unknown>(path, namespaceId)
}

private delete<T>(path: string, namespaceId?: string): Observable<T> {
  let params = new HttpParams()
  let headers = new HttpHeaders().set('X-Correlation-Id', generateCorrelationId())
  const namespace = namespaceId?.trim()
  if (namespace) {
    headers = headers.set('X-Namespace-Id', namespace)
    params = params.set('namespaceId', namespace)
  }

  return this.http.delete<unknown>(path, { params, headers }).pipe(
    map((payload) => (isEnvelope(payload) ? (payload.data as T) : (payload as T))),
    catchError((error: unknown) => throwError(() => normalizeError(error)))
  )
}
```

---

### Step 2: Unit Tests for API Service
In `apps/cockpit-v2/src/app/core/factory-api.service.spec.ts`:
- Test `runGarbageCollection`: verify POST to `/api/factory/admin/artifacts/gc`, query params, headers, body (`{ dryRun: true }`).
- Test `purgeArtifact`: verify POST to `/api/factory/admin/artifacts/art-123/purge`, body (`{ reason: 'expired' }`).
- Test `setLegalHold`: verify POST to `/api/factory/admin/artifacts/art-123/legal-hold`, body (`{ legalHold: true, reason: 'audit' }`).
- Test `getWorkflowDefinitions`: verify GET to `/api/factory/workflow-definitions`.
- Test `uploadWorkflowDefinition`: verify POST to `/api/factory/workflow-definitions/upload` with FormData body and headers without manual `Content-Type`.
- Test `deleteWorkflowDefinition`: verify DELETE to `/api/factory/workflow-definitions/myType/v1`.

---

### Step 3: Confirmation Dialog Component
Create `apps/cockpit-v2/src/app/features/admin/confirm-dialog.component.ts`:
- Standalone component using `MatDialogModule`, `MatButtonModule`.
- Inputs / Data: `{ title: string, message: string, confirmLabel: string, destructive?: boolean }`.
- Returns `true` on confirmation, `false`/`undefined` on cancellation.

---

### Step 4: Admin Page Component
Create `apps/cockpit-v2/src/app/features/admin/`:
- `admin-page.component.ts`
- `admin-page.component.html`
- `admin-page.component.scss`

#### Behavior & State Management:
- Uses Angular `signal`s / `reactive` state.
- Constructor / OnInit: set breadcrumbs via `ShellState`: `shellState.crumbs.set([{ label: 'Réglages' }])` or `[{ label: 'Gouvernance des artefacts' }]`.
- Load workflow definitions on init via `getWorkflowDefinitions()`.
- Handle 403 / `FORBIDDEN_ADMIN_REQUIRED`:
  - If any API call fails with status 403 or code `FORBIDDEN_ADMIN_REQUIRED`, set a state signal `adminDisabled = signal(true)` and `forbiddenError = signal(error.message)`.
  - Disable all form fields and action buttons across all sections when `adminDisabled()` is true.
  - Display the escaped verbatim error message in a banner at the top of the admin page (e.g., `"Accès refusé : ... (FORBIDDEN_ADMIN_REQUIRED)"`).
- Non-403 errors: display in appropriate section or global error banner without setting `adminDisabled`.

#### Section Specs:
1. **Garbage Collection (GC)**
   - Description: "Réconcilie le stockage objet et les lignes de métadonnées ; recycle les uploads de staging orphelins."
   - Checkbox for `dryRun` (reactive control or signal).
   - Button "Lancer GC" (displays "GC en cours…" when loading).
   - Result report panel:
     - `staging recyclés : X` (`reclaimedStagingKeys.length`)
     - `blobs scannés : X` (`scannedBlobKeys.length`)
     - `lignes métadonnées : X` (`scannedMetadataRows`)
     - `anomalies : X` (`anomalies.length`)
     - Timestamp display.

2. **Purge d'un artefact**
   - Inputs: Identifiant d'artefact (`artifactId`, required to enable button), Motif (optionnel, `reason`).
   - Button: "Purger l'artefact" with destructive styling (e.g. `mat-flat-button` color `warn` / `.btn-danger`).
   - Before calling API: open `MatDialog` confirmation with message `"Suppression définitive et irréversible de l'artefact..."`. If cancelled, abort.
   - Result panel: Displays purge status, artifactId, freed size (using helper `formatBytes`), reason on success.

3. **Legal Hold**
   - Inputs: Identifiant d'artefact (`artifactId`, required), Checkbox "Placer le legal hold" (`legalHold`, default `true`), Motif (optionnel, `reason`).
   - Button: "Appliquer le legal hold".
   - Result panel: Active state ("legal hold : actif" / "inactif"), artifactId, reason.

4. **Workflow Definitions**
   - Description: "Importez une définition JSON (format v1) et gérez les définitions enregistrées."
   - Inputs: File input (`type="file"`, `accept=".json,application/json"`).
   - Buttons: "Importer la définition" (calls `uploadWorkflowDefinition`), "Rafraîchir" (calls `getWorkflowDefinitions`).
   - Table (`MatTable` or HTML table with `MatTableModule`): Columns `type` (`workflowType`), `version` (`version`), `hash` (`definitionHash`), `actions` (Supprimer button).
   - Before deleting definition: open `MatDialog` confirmation with message `"Suppression définitive de la définition..."`. If cancelled, abort. On success, refresh definitions list.

#### Helper Utilities:
- `formatBytes(bytes: number | null | undefined): string` helper function.

---

### Step 5: Routing Integration
In `apps/cockpit-v2/src/app/app.routes.ts`:
- Add route before wildcard `**`:
```typescript
{
  path: 'reglages',
  loadComponent: () => import('./features/admin/admin-page.component').then((m) => m.AdminPageComponent),
},
```

---

### Step 6: Component & Service Tests
In `apps/cockpit-v2/src/app/features/admin/admin-page.component.spec.ts`:
- Test breadcrumb setup (`ShellState.crumbs` contains label `'Gouvernance des artefacts'` or `'Réglages'`).
- Test initial fetch of workflow definitions.
- Test GC trigger with `dryRun` flag and result rendering.
- Test Purge artifact flow:
  - Trigger purge -> confirmation dialog opens -> confirm -> `purgeArtifact` called -> result displayed.
  - Trigger purge -> dialog cancelled -> `purgeArtifact` NOT called.
- Test Legal hold flow.
- Test Workflow definitions flow:
  - File selection & upload -> `uploadWorkflowDefinition` called -> table refreshed.
  - Delete definition -> confirmation dialog -> `deleteWorkflowDefinition` called -> table refreshed.
- Test 403 / `FORBIDDEN_ADMIN_REQUIRED` error handling:
  - Mock API 403 error.
  - Assert controls/buttons are disabled.
  - Assert verbatim error message banner is rendered.

---

## Verification Plan

### Run automated test suite and quality checks:
1. `pnpm nx test cockpit-v2`
2. `pnpm nx lint cockpit-v2`
3. `pnpm nx build cockpit-v2`
