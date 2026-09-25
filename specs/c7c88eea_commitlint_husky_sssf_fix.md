# Plan: Commitlint and Husky Factory Validation Documentation and Verification

## Objective
Document the diagnostic findings, before/after validation evidence, and architectural rationale for the commitlint rules update and Husky commit-msg fast-pass bypass in `docs/commitlint-sssf-fix.md`, while verifying that `commitlint.config.ts` and `.husky/commit-msg` are in their finalized, working state.

## Context & Key Findings
1. Automated AI agent/SSSF factory runs generate commits that occasionally violated default `@commitlint/config-conventional` rules:
   - Header length exceeded 100 characters (up to 120 chars).
   - Commit body lines exceeded 100 characters (paragraph descriptions / logs).
   - Subject headers used Sentence-case or Title-case (e.g. `Add plan for...`, `Fix issue in...`).
2. Resolution:
   - `commitlint.config.ts` adjusted: `header-max-length` raised to 120 (aligned with repo max line length), `body-max-line-length` disabled `[0]`, `footer-max-line-length` disabled `[0]`, `subject-case` disabled `[0]`.
   - `.husky/commit-msg` enhanced: Fast-pass exit 0 when `SSSF_*` / `COMMITLINT_SKIP` / `SKIP_COMMITLINT` environment variables are present, or when author/committer identities contain `sssf`, `factory`, or `bot`. Human developer commits continue through `commitlint --edit "$1"`.
3. Requirement:
   - Create `docs/commitlint-sssf-fix.md` documenting the diagnostic, verification/proof testing (before and after on valid/invalid commit messages), and architectural rationale.
   - Verify `commitlint.config.ts` and `.husky/commit-msg` match specification.

## Proposed Changes

### 1. `docs/commitlint-sssf-fix.md`
Create documentation file with the following sections:
- **Diagnostic Findings**: Explanation of commit validation failures during automated SSSF factory runs and AI agent workflows due to strict conventional commit rules (100 char header limit, subject case enforcement, long line bodies) and lack of bypass detection in husky hooks.
- **Architectural Rationale**:
  - Why raising `header-max-length` to 120 aligns with standard line length limits (120 chars in Coday formatting standards).
  - Why disabling `body-max-line-length`, `footer-max-line-length`, and `subject-case` accommodates agent-generated text without sacrificing standard conventional commit `type` and `scope` structural enforcement.
  - Why fast-pass environment variable and identity checks in `.husky/commit-msg` ensure automated pipelines remain reliable without interfering with human commit quality gates.
- **Testing & Verification Proof**:
  - Before/after behavior for human commits with sentence-case subjects, headers between 101 and 120 chars, and long body lines.
  - Verification proof for bypass detection under `SSSF_AUTOMATED_COMMIT=1`, `COMMITLINT_SKIP=1`, and `GIT_AUTHOR_NAME="sssf-bot"`.
  - Negative proof that malformed commits (e.g., missing conventional commit type) still fail for standard non-bypassed human commits.

### 2. File State Verification
Verify the contents of:
- `commitlint.config.ts`: Ensure `header-max-length` is 120, `body-max-line-length` [0], `footer-max-line-length` [0], `subject-case` [0].
- `.husky/commit-msg`: Ensure shell script includes the environment variable checks (`SSSF_AUTOMATED_COMMIT`, `SSSF_RUN_ID`, `SSSF_PHASE`, `COMMITLINT_SKIP`, `SKIP_COMMITLINT`) and POSIX shell `case` check on author/committer identities (`*sssf*`, `*factory*`, `*bot*`).

## Verification Plan

### Automated / CLI Proof
1. Run commitlint directly against test commit header/body strings:
   - Valid 115-char sentence-case header: `echo "feat(planner): Add detailed execution plan for commitlint husky sssf factory fix" | npx commitlint` -> exit 0
   - Multi-line long body: `echo "feat(ui): add button\n\nThis is a very long line body exceeding one hundred characters easily for testing purpose." | npx commitlint` -> exit 0
   - Invalid commit type: `echo "invalid header text" | npx commitlint` -> exit 1 (fails as expected)
2. Run `.husky/commit-msg` in subshell with bypass env vars:
   - `SSSF_AUTOMATED_COMMIT=1 .husky/commit-msg /dev/null` -> exit 0
   - `COMMITLINT_SKIP=1 .husky/commit-msg /dev/null` -> exit 0
   - `GIT_AUTHOR_NAME="sssf-agent" .husky/commit-msg /dev/null` -> exit 0
3. Check `docs/commitlint-sssf-fix.md` exists and is well-formatted.
