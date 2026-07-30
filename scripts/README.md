# Thread Repair Scripts

Utility scripts to scan and repair corrupted Coday thread YAML files.

## Prerequisites

Run from the project root (the scripts use the `yaml` package from `node_modules`).

## Scripts

### fix-all-threads.mjs

Scan and repair all threads in a project directory.

```bash
node scripts/fix-all-threads.mjs <threads-directory>
```

Example:
```bash
node scripts/fix-all-threads.mjs ~/.coday/projects/CODAY/threads
```

### fix-thread.mjs

Repair a single thread file.

```bash
node scripts/fix-thread.mjs <path-to-thread.yml>
```

Example:
```bash
node scripts/fix-thread.mjs ~/.coday/projects/CODAY/threads/67f8123c-6c81-4055-ae77-f88f92715b2b.yml
```

## What gets fixed

- **`starring`**: boolean or invalid type → `[]`
- **`users`**: empty array → populated from `username` field
- **`projectId`**: empty string → inferred from directory name
- **`content`**: string or single object → wrapped in proper `[{ type: 'text', content }]` array
- **Trailing orphaned invites**: invite/choice events at end of thread without a matching answer → removed
- **Empty files** (0 bytes): deleted automatically (batch script only)

## Backups

A `.bak` backup is created for every modified file before writing. Empty files are deleted without backup.

## After running

If any repaired thread was already open in the server, **restart Coday** to clear the in-memory instance. Threads not yet opened will be read correctly on next access without a restart.
