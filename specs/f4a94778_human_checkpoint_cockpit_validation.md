# Plan: Human Checkpoint Validation in Cockpit Dashboard

## Overview
Enable direct human checkpoint approval and rejection from the Factory cockpit GUI (`factory/dashboard/`). When a user selects a step in the timeline (`run-detail` / `temporal-lanes`) whose lane/kind is `human` and whose status is `waiting_human` (or has an active `waiting` interaction), the detail panel (`phase-panel`) will render a checkpoint decision section containing:
- Checkpoint title/prompt.
- Comment text box (optional `text` mapped up to 2000 chars).
- Primary "Approuver" (green) and Danger "Rejeter" (red) action buttons.

Upon clicking Approuver / Rejeter:
1. The cockpit calls `GET /api/factory/workflows/{workflowId}/interactions?namespaceId={ns}` to find the active `waiting` interaction for the selected `stepId`.
2. It calls `POST /api/factory/workflows/{workflowId}/interactions/{interactionId}/reply` with body `{ expectedRevision, actionId, text }` and required headers (`Content-Type`, `X-Factory-Actor-Id`, `X-Factory-Namespace-Id`, `X-Correlation-Id`).
3. If successful, the panel displays feedback and triggers a full view refresh (`refresh()`), which re-fetches the projection and timing (updating the step status from `waiting_human` to `completed` or `failed`).
4. In case of `REVISION_CONFLICT` (409), it re-fetches the interaction and prompts the user to retry with the updated revision.

### DAG Continuation Verification
Analysis of `factory-service` (`WorkflowController.kt` and `SessionRunService.kt`):
- `POST .../interactions/.../reply` atomically updates the human interaction state, records `human-decision` evidence, applies the transition, and updates the step status.
- `SessionRunService.runSession(...)` evaluates suspended human steps via `resolveWaitingHuman(...)`.
- **Finding:** Resolving an interaction via `/reply` updates the durable database projection, but does **not** automatically re-trigger the in-process `SessionRunService.runSession` loop.
- **Continuation:** To resume execution of the DAG after a successful reply, the frontend triggers `POST /api/factory/workflows/{workflowId}/continue` with `{ "namespaceId": ns }`. (Note: `runInternal` in `WorkflowController.kt` accepts `/continue` with `namespaceId`; `repoRoot` falls back to `factory.session.default-repo-root` when omitted, or can be passed if known).

---

## Proposed Changes

### 1. API Client Support (`factory/dashboard/js/services/api-client.mjs`)
- Ensure `listInteractions(workflowId, { namespaceId })` and `replyInteraction(workflowId, interactionId, body, { namespaceId, actorId })` can be invoked using `apiClient.get` and `apiClient.post`.
- Verify default actor attribution (`X-Factory-Actor-Id`) is passed through or populated from current cockpit state (e.g. `factory-cockpit-user` or `actorId` passed down).

### 2. Checkpoint Component & Phase Panel Integration (`factory/dashboard/js/components/phase-panel.mjs`)
- Enhance `renderPhasePanel`:
  - When the step has `phaseKind === 'human'` or `lane === 'human'` (or `responsibility.kind === 'human'`) AND status is `waiting_human` (or an open waiting interaction is present in state):
  - Render an interactive **Decision Checkpoint** card inside the panel:
    - Display interaction prompt (if loaded in `enrichment` or passed via props) or step name.
    - Render `<textarea id="checkpoint-comment" maxlength="2000" placeholder="Commentaire optionnel..."></textarea>`.
    - Render `<button class="button primary" data-action="approve">Approuver</button>` and `<button class="button danger" data-action="reject">Rejeter</button>`.
    - Render alert/feedback container for success or revision conflict warnings.
- Update `loadPhaseEnrichment`:
  - If step is a human step (or status is `waiting_human`), fetch open interactions via `GET /api/factory/workflows/{workflowId}/interactions?namespaceId={ns}` and filter for `stepId === step.id && status === 'waiting'`.
  - Store the active interaction object (`{ interactionId, revision, prompt, actions }`) in `enrichment.interaction`.

### 3. Interaction Logic in Run Detail View (`factory/dashboard/js/views/run-detail.mjs`)
- Handle click events on approval/rejection buttons inside `run-detail`:
  - Intercept `click` events on `[data-checkpoint-action]`.
  - Disable buttons and show loading spinner/indicator during submission.
  - Read comment text from `#checkpoint-comment`.
  - Call `POST /api/factory/workflows/{workflowId}/interactions/{interactionId}/reply` with `{ expectedRevision: interaction.revision, actionId: 'approve'|'reject', text }`.
  - Upon successful reply:
    - Trigger `POST /api/factory/workflows/{workflowId}/continue` with `{ namespaceId }` to ensure DAG sequence continuation on server.
    - Show brief success feedback ("Checkpoint résolu avec succès").
    - Call `refresh()` to re-fetch projection & timeline, reflecting step transition to `completed`/`failed`.
  - Upon error / `REVISION_CONFLICT`:
    - Show error message ("Conflit de révision, actualisation...").
    - Re-fetch enrichment/interactions to acquire the new revision and re-enable action buttons.

### 4. Cockpit Dockyard Styling (`factory/dashboard/css/dockyard.css`)
- Check existing classes for buttons (`.button.primary`, `.button.danger`, `.panel`) and ensure clean UI integration for the checkpoint card in the phase detail panel without introducing new frameworks.

### 5. Automated ESM Tests
- Create or update `factory/dashboard/js/components/phase-panel.test.mjs` (or unit test files executable via `node --test`):
  - Test rendering phase panel with a waiting human step shows input and Approve/Reject buttons.
  - Test rendering phase panel with non-waiting or resolved human step hides action buttons.
  - Test interaction handling logic / payload formatting for `replyInteraction`.

---

## Verification Plan

### Automated Tests
Run vanilla Node ESM tests:
```bash
node --test factory/dashboard/js/components/phase-panel.test.mjs
node --test factory/dashboard/js/components/temporal-lanes.test.mjs
```

### Manual Verification
1. Start factory-service or mock server on port 8141.
2. Launch a workflow with a human checkpoint step.
3. Open Cockpit UI at `http://localhost:8141/cockpit.html`.
4. Navigate to the detail view of the run in `waiting_human` state.
5. Click on the human checkpoint block in the timeline.
6. Verify the detail panel displays prompt, comment box, and "Approuver" / "Rejeter" buttons.
7. Click "Approuver": verify API request is sent, DAG resumes, and status updates to `completed`.
