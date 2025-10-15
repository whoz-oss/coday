# Automated Release Process

## Overview

The Coday project now uses a fully automated release workflow that triggers on every push to the master branch. This replaces the previous manual `release.sh` script with GitHub Actions automation.

## Workflow Details

### Trigger
- **Event**: Push to master branch
- **File**: `.github/workflows/release.yml`

### Process Steps

1. **Setup Environment**
   - Checks out code with full history (required for nx release)
   - Sets up Node.js 22 with pnpm
   - Configures Git with GitHub Actions bot credentials

2. **Version Bumping**
   - Runs `nx release` which:
     - Determines the next version based on conventional commits
     - Updates version in `apps/web/package.json`
     - Generates/updates CHANGELOG.md
     - Creates a git commit with message `chore(release): {version}`
     - Creates a git tag `release/{version}`

3. **Build**
   - Builds the web application (triggered by nx release preVersionCommand)
   - Creates the distribution package in `dist/web`

4. **Publish**
   - Pushes commits and tags back to master
   - Publishes the npm package to registry
   - Creates a GitHub Release

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
      "preVersionCommand": "npx nx run web:build",
      "currentVersionResolver": "registry",
      "preserveLocalDependencyProtocols": false,
      "manifestRootsToUpdate": ["apps/{projectName}"]
    }
  }
}
```

## Required Setup

### GitHub Secrets

You need to configure the following secret in your GitHub repository:

1. **NPM_TOKEN**
   - Generate at: https://www.npmjs.com/settings/[your-username]/tokens
   - Required permissions: "Automation" token type
   - Add to repository: Settings → Secrets and variables → Actions → New repository secret

### Repository Permissions

The workflow requires these permissions (already configured in the workflow):
- `contents: write` - To push commits and create releases
- `pull-requests: write` - For future PR creation if needed
- `packages: write` - For package publishing

## Version Strategy

The version bumping follows semantic versioning based on conventional commits:
- `fix:` commits trigger patch version bumps (0.0.X)
- `feat:` commits trigger minor version bumps (0.X.0)
- `BREAKING CHANGE:` triggers major version bumps (X.0.0)

## Differences from Manual Process

### Previous Manual Process (release.sh)
1. Developer runs `./release.sh` locally
2. Script creates a `chore/release` branch
3. Runs `yarn nx release`
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

## Troubleshooting

### Common Issues

1. **NPM Authentication Failed**
   - Verify NPM_TOKEN secret is set correctly
   - Check token hasn't expired
   - Ensure token has automation permissions

2. **No Version Bump**
   - Check if commits follow conventional commit format
   - Verify there are actual changes since last release

3. **Build Failures**
   - Check build logs in GitHub Actions
   - Ensure all dependencies are properly locked

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