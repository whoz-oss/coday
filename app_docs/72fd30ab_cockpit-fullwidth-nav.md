# Factory cockpit: full-width runs and reduced navigation

## What changed

The Factory cockpit now stacks run cards in one full-width column. The `.runs` grid keeps `display: grid` and an 18px gap, but no longer uses the `repeat(auto-fill, minmax(...))` column template. Cards therefore expand across the centered cockpit content area while their internal layout remains unchanged.

The top navigation now exposes only **Runs** and **Admin**. The static Launch and Forge links and their placeholder sections were removed. The router no longer registers `/launch` or `/forge`, and the corresponding Launch and Forge view wiring was removed from `app.mjs`. Unknown or removed hashes continue through the existing `parseHash` fallback to `/runs`, so direct legacy hashes are canonicalized to the runs view rather than becoming broken routes. Runs, the detail timeline, the projection alias, and Admin remain registered.

## Files carrying the change

- `factory/dashboard/css/dockyard.css` — removes the multi-column template from `.runs`.
- `factory/dashboard/cockpit.html` — keeps only Runs/Admin navigation and removes the Launch/Forge view sections.
- `factory/dashboard/js/app.mjs` — removes Launch/Forge route entries, Launch/Forge imports and mounter wiring, while retaining the remaining cockpit routes and fallback behavior.
- `specs/72fd30ab_cockpit_fullwidth_runs_nav_cleanup.md` — records the targeted change plan and verification criteria.

## Verification

Serve the dashboard through `factory-service` and open `http://127.0.0.1:8141/`. Confirm that the bar shows Runs and Admin only, and that the runs list renders vertically stacked cards spanning the centered content width. Select a run to confirm the `/detail` SSSF timeline still opens. Directly visiting `#/launch` or `#/forge` should resolve to the runs view because those paths are no longer known routes.
