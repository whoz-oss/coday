# Cockpit V2 sidebar and workflows route

## What changed

The `cockpit-v2` navigation rail now has exactly three entries:

- **Sandboxes** → `/sandboxes` (`grid_view`)
- **Historique** → `/historique` (`history`)
- **Workflows** → `/workflows` (`account_tree`)

The former Sessions and Agents entries were removed from the navigation model, and the hard-coded Réglages rail link (including its spacer) was removed from the template. This keeps the visible rail aligned with the three-item `nav` array and eliminates the legacy `/reglages` menu item.

`/workflows` now lazy-loads `AdminPageComponent`. The existing `/reglages` URL is retained as a full redirect to `/workflows`; the `/sessions/:runId` route and wildcard redirect to `sandboxes` remain in the route table.

The admin page now sets its shell breadcrumb to **Workflows** instead of “Gouvernance des artefacts.” Its existing administrative behavior was not changed. The admin component spec was updated to assert the new breadcrumb label.

## Files carrying the change

- `apps/cockpit-v2/src/app/layout/shell.component.ts` — defines the three-item rail navigation.
- `apps/cockpit-v2/src/app/layout/shell.component.html` — renders only the navigation array in the rail; removes the standalone Réglages link.
- `apps/cockpit-v2/src/app/app.routes.ts` — maps `/workflows` to `AdminPageComponent`, redirects `/reglages`, and preserves session and fallback routes.
- `apps/cockpit-v2/src/app/features/admin/admin-page.component.ts` — sets the Workflows breadcrumb.
- `apps/cockpit-v2/src/app/features/admin/admin-page.component.spec.ts` — expects the Workflows breadcrumb.

## Verification

Run the cockpit-v2 test, lint, and build targets:

```sh
pnpm nx test cockpit-v2
pnpm nx lint cockpit-v2
pnpm nx build cockpit-v2
```

For a manual check, inspect the rail for only the three entries, navigate to `/workflows` and confirm the admin page and `Workflows` breadcrumb, verify `/reglages` redirects to `/workflows`, and directly open `/sessions/<runId>` to confirm the session route remains reachable.
