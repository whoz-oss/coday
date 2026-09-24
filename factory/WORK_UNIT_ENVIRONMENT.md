# Factory Work Unit Environment v1

Factory exclusively owns Git worktree creation and removal. Phase 8 links each environment durably to a governed `workflowId` and controlling AgentOS case, while keeping repository and worktree roots in the trusted Factory composition root.

## Contract

`WorkUnitEnvironment` is a durable, generic identity for one work unit. It records safe stable IDs, UUID namespace and optional bound parent case, canonical repository/worktree working-directory paths, exact integration/feature refs and base object ID, creation attribution and lifecycle. Provisioning explicitly uses no `parentCaseId`; no case ID is fabricated. A provisioning descriptor temporarily has `baseCommit: null` until Git returns the exact 40- or 64-hex commit. No display prose, BMAD paths, ledger data or concurrency ownership is included.

A worktree path means the checked-out working directory. `.git/worktrees/...` is Git administrative metadata and is never stored as the worktree path.

## Storage and ordering

Data lives at `<FACTORY_DATA_ROOT>/environments/<namespaceId>/<sha256(namespaceId:environmentId)>/`. `environment.json` is the current revisioned snapshot and `events.jsonl` contains machine facts only: reservation, provision, parent binding, state change and removal.

Each write is serialized per environment in-process and ordered as: fsynced `pending.json`, fsynced journal append, atomic/fsynced snapshot replacement, then pending removal. Pending and journal facts carry the SHA-256 hash of the complete canonical descriptor. Recovery validates the pending revision, descriptor, namespace/environment identity and hash, and promotes it only when a durable journal fact has the same revision, identity and hash. Malformed pending data or a missing/mismatched journal binding fails closed and remains on disk for diagnosis; it is neither promoted nor silently removed. Before scanning, reading, recovery or mutation, existing namespace and hashed-environment path components are checked with `lstat`: symlinks and non-directories are rejected, canonical parents must remain beneath the canonical environments root, and directories are rechecked after creation before files are opened. Success is returned only after snapshot and journal are durable.

The service additionally serializes the complete inspect/reconcile/reserve/Git/finalize/error sequence per namespace/environment. Its lock is independent from store locks and store calls remain sequential, avoiding nested store-lock acquisition. The design assumes one Factory process/host is the sole writer. It is not a distributed lock or multi-writer protocol.

Lifecycle is `provisioning` (unbound) to `active` only by exact parent-case binding; `active` to `completed`, `abandoned`, or `error`; terminal to `removed`. Provisioning can become `error` after Git failure. There is no resurrection in v1. Repeated identical reserve/bind/remove operations are idempotent where ownership can be proven.

## Git safety

Commands use an injected exec-file runner with executable and argument arrays; no shell interpolation is used. Repository inspection canonicalizes the supplied path, resolves `git rev-parse --show-toplevel`, and requires exact equality, so repository subdirectories are rejected. It requires clean status and resolves the exact integration-branch commit.

The configured Factory worktrees root is canonical. An incoming destination must already be absolute and lexically canonical, must be strictly below that root, and must retain containment after canonicalizing its existing parent. Root itself, relative/noncanonical paths, escapes and symlink escapes are rejected before Git. The exact canonical intended destination is persisted. Existing branches or paths fail closed unless reconciliation proves the exact registered worktree, expected branch and commit.

Removal refuses the repository root, its ancestors, and paths outside the configured worktrees root. It first verifies the target in `git worktree list --porcelain` for the expected repository and branch, then invokes `git worktree remove` and prune. It never recursively deletes arbitrary directories and never commits, rebases, merges, pushes, or deletes branches. Absence is idempotent only when both Git metadata and the filesystem prove absence.

The service durably reserves provisioning intent before `git worktree add`. `baseCommit` is the immutable provisioning baseline; `headCommit` is mutable reconciliation evidence and is not persisted as the baseline. Reconciliation reads `git worktree list --porcelain` and the expected branch ref, requires the registered path and branch plus equality between the branch ref and registered worktree HEAD, and returns current `headCommit` separately. Advancing a feature branch does not change `baseCommit` and does not prevent idempotency or removal. A repeated successful provision returns the existing coherent descriptor. A crash after Git add but before snapshot finalization leaves a provisioning descriptor; retry proves ownership and finalizes it without another add. If path or branch evidence exists but exact ownership cannot be proven, provisioning remains unchanged with an `OWNERSHIP_UNCERTAIN` result for operator inspection. Only conclusive absence after a failed creation transitions to `error`, recording a bounded machine code. A deterministic post-add fault seam exists for recovery testing. Removal state is written only after verified Git removal succeeds; uncertain worktrees are never auto-deleted.

## Security and next stage

Absolute paths remain Factory-internal and must not become model-visible tool arguments. Canonicalization and explicit roots limit path confusion, while the mono-host assumption remains a deployment constraint.

Remaining limitations are mono-host/mono-process ownership, no distributed writer lease, and no automatic repair of uncertain Git metadata. There are no watchers or branch cleanup. Production deployment must run one Factory writer; uncertain reconciliation is surfaced as `OWNERSHIP_UNCERTAIN`/`ENVIRONMENT_NOT_BOUND` and workers receive no work-unit file tools.

Phase 8 control-plane roots are mandatory `FACTORY_REPO_ROOT` and `FACTORY_WORKTREES_ROOT`; neither CWD, namespace configPath nor model/client input is used. The authoritative `WorkflowInstance.environmentRef` stores only the environment ID and immutable environment hash; reads resolve through that reference and fail closed if the current environment snapshot no longer matches. AgentOS exposes `FACTORY__provision_environment` without path arguments. Work-unit `FILE_ACCESS` is explicit-only through `WORK_UNIT_FILE_ACCESS`, resolves the environment using trusted namespace/case identity, requires exact canonical equality between the durable worktree path and `toRealPath()`, and otherwise grants no tools. The cockpit displays durable state, base commit, observed HEAD, worktree/root and the fail-closed block code. Git commit/checkpoint remains intentionally absent until Phase 9.
