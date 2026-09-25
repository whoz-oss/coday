# Commitlint and Husky factory validation

## What changed

Commit validation now accommodates the repository’s 120-character line standard and agent-generated commit text without removing conventional-commit structure checks:

- `commitlint.config.ts` keeps `@commitlint/config-conventional`, sets `header-max-length` to 120, disables body and footer line-length checks, and disables `subject-case`. Type/header structure and the remaining inherited rules continue to apply.
- `.husky/commit-msg` now exits successfully for automated runs when any of `SSSF_AUTOMATED_COMMIT`, `SSSF_RUN_ID`, `SSSF_PHASE`, `COMMITLINT_SKIP`, or `SKIP_COMMITLINT` is non-empty. It also bypasses validation when the lower-cased author/committer identity contains `sssf`, `factory`, or `bot`.
- Ordinary human commits still run `npx --no -- commitlint --edit "$1"`, preserving the commit-message quality gate for non-bypassed identities.

## Why it matters

The documented diagnosis identifies three failures under the former default rules: headers over 100 characters, long body lines, and sentence/title-case subjects produced by agents. The configuration relaxes those cosmetic constraints while retaining conventional-commit validation. The hook’s explicit environment and identity paths prevent automated ADW/SSSF factory commits from stalling, without bypassing validation for ordinary developer commits.

## Verification recorded

`docs/commitlint-sssf-fix.md` records before/after CLI evidence: the old configuration rejects a 103-character header, sentence-case subject, and 120-character body line; the repository configuration accepts those same inputs; and an invalid non-conventional message still fails with `subject-empty` and `type-empty`. It also records successful hook fast-passes for each supported environment variable and bot-like identity, plus human-identity tests showing a valid message passes and a malformed message is rejected.

The two changed planning/specification notes, `specs/b962d43a_commitlint_husky_sssf_fix.md` and `specs/c7c88eea_commitlint_husky_sssf_fix.md`, describe the requested scope, intended rule values, bypass conditions, and verification plan corresponding to the finalized files.

## How to use

- For a normal commit, let Husky invoke the hook; it validates the message using the root `commitlint.config.ts`.
- For an explicitly automated SSSF/ADW commit, export one of the documented non-empty bypass variables before committing, or use the configured bot/factory identity. The hook exits 0 before invoking commitlint.
- To reproduce the evidence, follow the commands and `/tmp` test-message examples in `docs/commitlint-sssf-fix.md`, including the negative human-commit case.

Changed files documented: `.husky/commit-msg`, `commitlint.config.ts`, `docs/commitlint-sssf-fix.md`, `specs/b962d43a_commitlint_husky_sssf_fix.md`, and `specs/c7c88eea_commitlint_husky_sssf_fix.md`.
