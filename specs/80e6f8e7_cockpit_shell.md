# Plan: Task Wave 1 "cockpit-shell" of Milestone D (autonomous Factory Cockpit)

## Overview
Implement Task Wave 1 "cockpit-shell" of Milestone D on branch `sbx/coday-cockpit-shell-edda`.
This wave introduces the governed HTML shell `cockpit.html`, stylesheet `css/dockyard.css`, client services `api-client.mjs` and `sse-client.mjs`, client router `app.mjs`, server static route integration in `composition-root.mjs`, and a node-based test suite in `factory/tests/test-cockpit-shell.mjs`.

The implementation uses pure Vanilla ESM JS with zero dependencies, zero build step, and zero external JS framework. It coexists with the existing `index.html` monolith.

---

## Architecture & Design Guidelines

### 1. Constraints & Rules
- **ZERO External Dependencies**: Pure Vanilla ESM (`.mjs`). No npm dependencies for frontend.
- **ZERO Build Step & ZERO Framework**: Browsers load native ESM modules directly via `<script type="module">`.
- **Coexistence**: `factory/dashboard/index.html` must remain untouched and fully operational.
- **Safety**: Do NOT delete or modify any `.jsonl` files in `runs/`.
- **Content-Type Enforcement**: `composition-root.mjs` must strictly return `text/css` for `.css` and `application/javascript` for `.js`/`.mjs`. Never fall back to HTML for asset paths.

---

## Detailed File Specifications

### 1. Stylesheet: `factory/dashboard/css/dockyard.css` (NEW)
Create the Coday Dockyard visual theme stylesheet.
- **Design Tokens (`:root`)**:
  ```css
  :root {
    color-scheme: dark;
    --bg: #06080f;
    --panel: #0d1119;
    --panel-2: #131a26;
    --panel-3: #0a0e16;
    --border: #232c3d;
    --border-soft: #222b3d;
    --text: #f2f5fa;
    --dim: #aabdd5;
    --faint: #8b9cb6;
    --green: #4ade80;
    --red: #ff6f67;
    --blue: #6cb6ff;
    --amber: #e8b64a;
    --purple: #c89bff;
    --cyan: #5ad2dd;
    --violet: #94a3ff;
    --sans: 'Play', 'Helvetica Neue', system-ui, sans-serif;
    --mono: ui-monospace, 'SF Mono', Menlo, Monaco, 'Roboto Mono', monospace;
    --surface: linear-gradient(180deg, #10141f 0%, #0b0f18 100%);
  }
  ```
- **Body & Visual Identity**:
  - Background: `var(--bg)` with fixed subtle radial ambient gradients (`radial-gradient(circle at 20% 10%, rgba(200, 155, 255, 0.05) 0%, transparent 40%), radial-gradient(circle at 80% 80%, rgba(90, 210, 221, 0.05) 0%, transparent 40%)`).
  - Fonts: `var(--sans)` for headings/body, `var(--mono)` for data fields (IDs, timestamps, status numbers, code, pre, etc.).
- **Topbar (`.cockpit-topbar`)**:
  - Sticky header (`position: sticky; top: 0; z-index: 100`).
  - Dark glassmorphism background (`background: rgba(13, 17, 25, 0.85); backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px)`).
  - Bottom border `1px solid var(--border)`.
  - Brand Logo: 3 vertical CSS accent bars (amber, purple, cyan) next to brand text styled with gradient text effect (`linear-gradient(90deg, var(--purple), var(--cyan))`, `-webkit-background-clip: text`, `-webkit-text-fill-color: transparent`).
  - Live Dot Indicator: glowing green dot with pulsing `@keyframes pulse`.
  - Active Link Styling: `.cockpit-nav a.active` highlighting active view.
- **Status Chips & Cards**:
  - Base `.chip` class with tabular-nums and `font-family: var(--mono)`.
  - Status variants: success (green text/border, soft glow), fail (red text/border), running (blue text/border with spinning `@keyframes spin`), wave (amber text/border).
  - Cards / Panels: background `var(--surface)`, border `1px solid var(--border)`, `border-radius: 16px`, padding, tracking on section titles (`letter-spacing: 0.08em; text-transform: uppercase; color: var(--dim)`).
- **Native `<dialog>` Styling (`#cockpit-dialog`)**:
  - Panel styling: background `var(--panel)`, border `1px solid var(--border)`, `border-radius: 16px`, padding.
  - Backdrop styling (`#cockpit-dialog::backdrop`): `backdrop-filter: blur(8px); -webkit-backdrop-filter: blur(8px); background: rgba(6, 8, 15, 0.7)`.
- **Accessibility & Motion**:
  - Reduced Motion (`@media (prefers-reduced-motion: reduce)`): disables transitions, pulse keyframes, spin animations.
  - Responsive Breakpoints: Desktop (default grid/flex), Tablet (`@media (max-width: 900px)`), Mobile (`@media (max-width: 480px)`).

---

### 2. Standalone HTML Shell: `factory/dashboard/cockpit.html` (NEW)
Create the governed HTML shell.
- **Structure**:
  - Clean HTML5 setup with `<html lang="en">` and UTF-8 charset.
  - Links Google Fonts for 'Play' (with system font fallback in CSS).
  - Includes `<link rel="stylesheet" href="/css/dockyard.css">`.
- **Layout**:
  - `<header class="cockpit-topbar">`:
    - Brand logo (vertical bars + title).
    - Navigation bar (`<nav class="cockpit-nav">`):
      - `<a href="#/runs">Runs</a>`
      - `<a href="#/detail">Détail</a>`
      - `<a href="#/projection">Projection</a>`
      - `<a href="#/forge">Forge</a>`
      - `<a href="#/admin">Admin</a>`
    - Live Status container with `#cockpit-live-indicator` (dot + label).
  - `<main id="cockpit-view-container">`:
    - Sections with classes `cockpit-view` and IDs `view-runs`, `view-detail`, `view-projection`, `view-forge`, `view-admin`.
  - Modal container: `<dialog id="cockpit-dialog"><div id="cockpit-dialog-content"></div></dialog>`.
  - ES Module Inclusion: `<script type="module" src="/js/app.mjs"></script>`.

---

### 3. REST API Client: `factory/dashboard/js/services/api-client.mjs` (NEW)
Create lightweight API client for REST endpoints.
- **`ApiClient` class**:
  - Constructor takes `{ baseUrl = '', defaultHeaders = {} }`.
  - Method `request(path, options = {})`:
    - Intercepts and builds headers.
    - **Attribution Headers**: Automatically includes attribution headers if present in options (`X-Factory-Namespace-Id`, `X-Factory-Case-Id`, `X-Factory-Actor-Id`). Note in docstrings that these are for *attribution only, NEVER authorization*.
    - **Correlation ID**: Generates `crypto.randomUUID()` if `options.headers['X-Correlation-Id']` or `options.correlationId` is absent, ensuring every HTTP call carries `X-Correlation-Id`.
    - Handles JSON body serialization for `POST`/`PUT`/`PATCH`.
    - Executes `fetch(url, options)`.
    - **Data Unwrapping & Raw Payload Tolerance**:
      - If response is `res.ok` (2xx):
        - Parses JSON (or text fallback).
        - If response object has top-level `data` property (e.g. `{ data: ... }`) and no `error` field, unwraps and returns `parsed.data`.
        - Otherwise returns raw response as-is (handles arrays, raw objects, booleans, primitives).
    - **Error Handling & Normalization**:
      - If response is NOT ok (or contains an explicit `error` field):
      - Normalizes into a uniform error object / throws `ApiClientError`:
        - Format A: `{ error: { code, message, ... } }` -> uses `code` and `message`.
        - Format B: `{ error: "message string" }` -> code = `HTTP_<STATUS>` or status string, message = `error`.
        - Format C: Non-JSON error body or standard HTTP error -> code = `HTTP_<STATUS>`, message = statusText or text body.
      - Attaches `status`, `code`, `message`, and `details` to the thrown error instance.
  - Convenience methods: `get(path, options)`, `post(path, body, options)`, `put(path, body, options)`, `delete(path, options)`.

---

### 4. SSE Client: `factory/dashboard/js/services/sse-client.mjs` (NEW)
Create resilient SSE subscription client with event bus and idempotent closing.
- **`SseClient` class**:
  - Constructor `(url, options = {})`.
  - Internal state: `url`, `eventSource`, `listeners` (Map of event names to Set of handlers), `closed` (boolean), `reconnectTimer`.
  - **Event Bus Methods**:
    - `on(eventName, handler)`: registers event callback.
    - `off(eventName, handler)`: unregisters callback.
    - `emit(eventName, data)`: dispatches data to registered callbacks.
  - **Connection Lifecycle**:
    - `connect()`: creates `new EventSource(this.url)`.
    - Listens to default `message` event: parses JSON payload and calls `this.emit('message', data)`.
    - Listens to named projection events:
      - `workflow-projection-updated`
      - `workflow-projection-removed`
      - `workflow-projection-restored`
      - `workflow-projection-purged`
      - Dynamically attaches event listeners for active subscriptions so custom named events trigger `this.emit(eventName, parsedData)`.
    - Error handling & auto-reconnect:
      - If connection errors out and `this.closed` is false, closes existing EventSource and schedules reconnect via timer without leaking sockets or timers.
  - **Safe Teardown (`close()`)**:
    - Idempotent: safe to call multiple times.
    - Sets `this.closed = true`.
    - Clears reconnect timer if active (`clearTimeout(this.reconnectTimer)`).
    - Invokes `this.eventSource.close()` if present.
    - Clears `this.listeners` Map and detach event handlers.
    - Guarantees zero memory/connection leaks.

---

### 5. Client Router & Controller: `factory/dashboard/js/app.mjs` (NEW)
Create hash-based SPA router and view manager.
- **Hash-based Navigation**:
  - Listens to `hashchange` and `DOMContentLoaded` on `window`.
  - Route mapping:
    - `#/runs` -> Run List View
    - `#/detail` -> Run Detail View
    - `#/projection` -> Projection View
    - `#/forge` -> Forge View
    - `#/admin` -> Admin View
    - Default/Fallback: redirects to `#/runs` if route is empty or unrecognized.
- **View Lifecycle & Teardown**:
  - Tracks `currentRoute` and active view teardown functions (`teardownHooks = []`).
  - On view transition:
    1. Executes all active view teardown hooks (clears interval timers, calls `.close()` on any open `SseClient` instances).
    2. Updates visibility of section elements (`.cockpit-view`).
    3. Updates topbar navigation styling (`.active` class on `<nav>` links).
    4. Renders view skeleton / placeholder content into the active section.
- **Live Dot Controller**:
  - Updates `#cockpit-live-indicator` status dot (connected/disconnected/connecting).
- **Modal Helpers**:
  - Global helper functions `showModal(contentElement)` and `closeModal()` interacting with `<dialog id="cockpit-dialog">`.

---

### 6. Server Static Route Wiring: `factory/dashboard/composition-root.mjs` (MODIFY)
Add static file routing for `/cockpit`, `/cockpit.html`, `/css/*`, and `/js/*` in `createHttpServer`.
- **HTML Shell Routes**:
  - `GET /cockpit` and `GET /cockpit.html`:
    - Serves `factory/dashboard/cockpit.html`.
    - Headers: `Content-Type: text/html; charset=utf-8`, CORS headers, `X-Correlation-Id: res.correlationId`.
- **Static Asset Routes (`/css/*` and `/js/*`)**:
  - When request method is `GET` and `path.startsWith('/css/')` or `path.startsWith('/js/')`:
    - Sanitizes path using `join` and checks that resolved path starts within `factory/dashboard/css/` or `factory/dashboard/js/` (prevent path traversal vulnerabilities).
    - Check if file exists.
    - If file exists:
      - For `.css`: set `Content-Type: text/css; charset=utf-8`.
      - For `.js` or `.mjs`: set `Content-Type: application/javascript; charset=utf-8`.
      - Read file and send 200 response.
    - If file does NOT exist:
      - Return 404 JSON error: `sendError(sendFn, 404, 'NOT_FOUND', 'Asset not found')`.
      - **CRITICAL**: Never fall back to HTML (`index.html` or `cockpit.html`) when a `.css`, `.js`, or `.mjs` request fails!
- **Coexistence**:
  - Retain `GET /` and `GET /index.html` serving legacy `index.html`.
  - Leave all existing API routes, B6 trust context extraction, and security boundaries intact.

---

### 7. Automated Test Suite: `factory/tests/test-cockpit-shell.mjs` (NEW)
Create standard node test file (`node factory/tests/test-cockpit-shell.mjs`) verifying all client services and server routes.
- **Test Modules**:
  1. **ApiClient Tests**:
     - Tests unwrapping `{ data: { items: [1] } }` -> returns `{ items: [1] }`.
     - Tests raw payload tolerance: returns arrays or raw objects as-is.
     - Tests Error Format A: `{ error: { code: 'ERR_1', message: 'failed' } }` throws error with `code === 'ERR_1'`.
     - Tests Error Format B: `{ error: 'Something wrong' }` throws error with `message === 'Something wrong'`.
     - Tests HTTP status error handling (404, 500).
     - Tests header propagation (`X-Correlation-Id`, `X-Factory-*`).
  2. **SseClient Tests**:
     - Tests event subscription and emission (`on`, `off`, `emit`).
     - Tests mock EventSource named event handling (`workflow-projection-updated`, etc.).
     - Tests safe and idempotent `close()` (calling multiple times without errors, clearing listeners).
  3. **Server Integration Tests**:
     - Starts composition root server on a dynamic port (`PORT=0`).
     - Tests `GET /cockpit` -> returns 200 OK, `text/html`, contains `<dialog id="cockpit-dialog">`.
     - Tests `GET /cockpit.html` -> returns 200 OK, `text/html`.
     - Tests `GET /css/dockyard.css` -> returns 200 OK, `text/css`, contains `:root`.
     - Tests `GET /js/app.mjs` -> returns 200 OK, `application/javascript`.
     - Tests `GET /js/services/api-client.mjs` -> returns 200 OK, `application/javascript`.
     - Tests `GET /js/services/sse-client.mjs` -> returns 200 OK, `application/javascript`.
     - Tests `GET /css/nonexistent.css` -> returns 404 JSON error (NOT HTML).
     - Tests `GET /` -> returns 200 OK, legacy `index.html` monolith.
     - Teardown: closes server after tests complete.

---

## Step-by-Step Implementation Sequence

1. **Create `factory/dashboard/css/dockyard.css`**: Implement Coday Dockyard deep-space design tokens, glass topbar, modal backdrop, live dot pulse animation, responsive breakpoints, and monospace rules.
2. **Create `factory/dashboard/cockpit.html`**: Build the governed standalone HTML shell with view sections, topbar nav, and modal dialog.
3. **Create `factory/dashboard/js/services/api-client.mjs`**: Implement `ApiClient` with correlation IDs, attribution headers, data unwrapping, and normalized error throwing.
4. **Create `factory/dashboard/js/services/sse-client.mjs`**: Implement `SseClient` with event bus, auto-reconnect, and idempotent `close()` cleanup.
5. **Create `factory/dashboard/js/app.mjs`**: Implement hash router (`window.location.hash`), view switcher, teardown manager, live indicator status, and modal helper.
6. **Modify `factory/dashboard/composition-root.mjs`**: Wire routes for `/cockpit`, `/cockpit.html`, static `/css/*` and `/js/*` with strict `Content-Type` headers and 404 handling.
7. **Create `factory/tests/test-cockpit-shell.mjs`**: Implement comprehensive test suite covering API client, SSE client, and server static routes.
8. **Run Verification & Quality Suite**: Verify all test suites and lint/build checks pass.

---

## Verification Plan

### Automated Tests
Run the newly created test suite:
```bash
node factory/tests/test-cockpit-shell.mjs
```

Run existing composition root and factory tests to ensure no regressions:
```bash
node factory/tests/test-composition-root-source.mjs
node factory/tests/test-factory-api.mjs
node --test factory/tests/test-*.mjs
```

Run Nx affected checks:
```bash
pnpm nx affected -t test --base="$(cat /work/data/baseline)" --parallel=2
pnpm nx affected -t lint --base="$(cat /work/data/baseline)"
```

### Manual Verification
1. Start factory dashboard server:
   ```bash
   PORT=3141 node factory/dashboard/server.mjs
   ```
2. Verify endpoints with curl:
   - `curl -i http://localhost:3141/cockpit` -> 200 OK, `Content-Type: text/html; charset=utf-8`
   - `curl -i http://localhost:3141/css/dockyard.css` -> 200 OK, `Content-Type: text/css; charset=utf-8`
   - `curl -i http://localhost:3141/js/app.mjs` -> 200 OK, `Content-Type: application/javascript; charset=utf-8`
   - `curl -i http://localhost:3141/index.html` -> 200 OK, original monolith dashboard
