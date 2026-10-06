# Automated Release Process

## Overview

The Coday project now uses a fully automated release workflow that triggers on every push to the master branch. This replaces the previous manual `release.sh` script with GitHub Actions automation.

## Workflow Details

### Trigger
- **Event**: Push to master branch
- **File**: `.github/workflows/release.yml`

### Process Steps

The workflow is split into 4 independent jobs that can be retried individually:

1. **Check Release** (`check-release`)
   - Checks out code with full history
   - Fetches all tags
   - Determines if there are releasable commits (feat, fix, BREAKING CHANGE)
   - Outputs whether release should proceed
   - Captures previous tag for changelog generation

2. **Version and Build** (`version-and-build`)
   - Runs only if releasable commits are found
   - Sets up Node.js 22 with pnpm
   - Configures Git with GitHub Actions bot credentials
   - Runs `nx release --skip-publish` which:
     - Determines the next version based on conventional commits
     - Updates version in `apps/server/package.json`
     - Generates/updates CHANGELOG.md
     - Runs the build (via `preVersionCommand` in nx.json)
     - Creates a git commit with message `chore(release): {version}`
     - Creates a git tag `release/{version}`
   - Pushes commits and tags back to master
   - Outputs the new version number

3. **Publish to npm** (`publish-npm`)
   - Runs after version and build completes
   - Checks out the updated master branch
   - Installs dependencies
   - Runs `nx release publish` which:
     - Rebuilds the package (via `dependsOn: ['server:build']` in nx.json)
     - Publishes to npm registry with provenance

4. **Create GitHub Release** (`create-github-release`)
   - Runs after npm publish completes
   - Creates a GitHub Release with auto-generated notes
   - Links to the previous release tag for changelog generation

### Configuration

The release process is configured in `nx.json`:

```json
{
  "release": {
    "projects": ["web"],
    "releaseTagPattern": "release/{version}",
    "git": {
      "commitMessage": "chore(release): {version}",
      "tag": true
    },
    "changelog": {
      "workspaceChangelog": {
        "file": "CHANGELOG.md",
        "entryWhenNoChanges": false
      }
    },
    "version": {
      "preVersionCommand": "npx nx run server:build",
      "currentVersionResolver": "registry",
      "preserveLocalDependencyProtocols": false,
      "manifestRootsToUpdate": ["apps/{projectName}"]
    }
  }
}
```

## Required Setup

### GitHub Secrets

You need to configure the following secrets in your GitHub repository:

1. **NPM_ACCESS_TOKEN**
   - Generate at: https://www.npmjs.com/settings/[your-username]/tokens
   - Required permissions: "Automation" token type
   - Add to repository: Settings → Secrets and variables → Actions → New repository secret

2. **RELEASE_PAT** (Required for branch protection bypass)
   - This is needed because the workflow pushes directly to the protected master branch
   - Generate a Personal Access Token (classic) at: https://github.com/settings/tokens
   - Required scopes:
     - `repo` (Full control of private repositories)
     - `workflow` (Update GitHub Action workflows)
   - Add to repository: Settings → Secrets and variables → Actions → New repository secret
   - **Important**: The PAT must be from a user who has bypass permissions in the branch protection ruleset
   
   **Alternative**: If using fine-grained PATs:
   - Repository access: Select the `whoz-oss/coday` repository
   - Permissions:
     - Contents: Read and write
     - Workflows: Read and write
     - Metadata: Read-only (automatically selected)
   - Then add the user/bot to the branch protection ruleset bypass list

### Repository Permissions

The workflow requires these permissions (already configured in the workflow):
- `contents: write` - To push commits and create releases
- `id-token: write` - For npm provenance

### Branch Protection Configuration

Since the release workflow pushes directly to master, you need to configure your branch protection ruleset to allow this:

**Option 1: Add bypass for GitHub Actions (Recommended)**
1. Go to Repository Settings → Rules → Rulesets
2. Edit your master branch protection ruleset
3. Under "Bypass list", add:
   - The user whose PAT is used in `RELEASE_PAT`
   - OR configure to allow the GitHub Actions bot

**Option 2: Use a GitHub App (Advanced)**
- Create a GitHub App with appropriate permissions
- Install it on your repository
- Use the app's token in the workflow
- This is more secure but requires more setup

## Version Strategy

The version bumping follows semantic versioning based on conventional commits:
- `fix:` commits trigger patch version bumps (0.0.X)
- `feat:` commits trigger minor version bumps (0.X.0)
- `BREAKING CHANGE:` triggers major version bumps (X.0.0)

## Differences from Manual Process

### Previous Manual Process (release.sh)
1. Developer runs `./release.sh` locally
2. Script creates a `chore/release` branch
3. Runs `pnpm nx release`
4. Pushes branch and opens PR creation link
5. Developer manually creates PR and merges
6. Developer manually publishes to npm

### New Automated Process
1. Developer pushes/merges to master
2. GitHub Actions automatically:
   - Runs nx release
   - Commits version changes directly to master
   - Publishes to npm
   - Creates GitHub release
3. No manual intervention required

## Monitoring Releases

- **GitHub Actions**: Check the Actions tab for workflow runs
- **npm Registry**: Package updates at https://www.npmjs.com/package/@whoz-oss/coday-web
- **GitHub Releases**: View at https://github.com/whoz-oss/coday/releases
- **Changelog**: Automatically updated in CHANGELOG.md

## Rollback Process

If a release needs to be rolled back:

1. **npm**: Use `npm unpublish @whoz-oss/coday-web@{version}` (within 72 hours)
2. **Git**: Revert the release commit on master
3. **Tags**: Delete the release tag locally and remotely:
   ```bash
   git tag -d release/{version}
   git push origin :refs/tags/release/{version}
   ```

## Retrying Failed Jobs

The workflow is designed to allow retrying individual failed jobs:

1. Navigate to the failed workflow run in GitHub Actions
2. Click on the specific failed job
3. Click "Re-run jobs" and select "Re-run failed jobs"

Each job can be retried independently without re-running the entire workflow:
- If **version-and-build** fails: Retry just that job (version bump and build happen together)
- If **publish-npm** fails: Retry just the publish job (nx release publish will rebuild automatically)
- If **create-github-release** fails: Retry just the release creation

## Troubleshooting

### Common Issues

1. **NPM Authentication Failed** (publish-npm job)
   - Verify NPM_ACCESS_TOKEN secret is set correctly
   - Check token hasn't expired
   - Ensure token has automation permissions
   - Retry the `publish-npm` job after fixing

2. **No Version Bump** (check-release job)
   - Check if commits follow conventional commit format
   - Verify there are actual changes since last release
   - Workflow will skip remaining jobs if no releasable commits found

3. **Build Failures** (version-and-build job)
   - Check build logs in GitHub Actions
   - Ensure all dependencies are properly locked
   - The build runs as part of nx release via `preVersionCommand`
   - Retry the `version-and-build` job after fixing code issues

4. **Push Conflicts** (version-and-build job)
   - Another commit was pushed while release was running
   - The job will attempt to rebase automatically
   - If it fails, retry the `version-bump` job

5. **GitHub Release Creation Failed** (create-github-release job)
   - Check if release tag already exists
   - Verify GITHUB_TOKEN has correct permissions
   - Safe to retry without affecting npm package

### Manual Release (Fallback)

If automation fails, you can still use the original `release.sh` script locally:
```bash
./release.sh
```

Then manually publish to npm:
```bash
cd dist/web
npm publish --access public
```
