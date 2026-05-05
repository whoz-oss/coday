# Release Pipeline

Releases trigger automatically on push to `master` via `.github/workflows/release.yml`, using `nx release` with conventional commits.

## Adding a new project to the release pipeline

Project inclusion is tag-driven — no CI or `nx.json` changes needed:

- Add `platform:npm` to a Node.js/Electron project → published to npm
- Add `platform:jvm` to a Kotlin/Gradle project → published to GitHub Packages

## Pipeline jobs

1. `check-release` — detects releasable commits since the last `release/*` tag
2. `release` — runs `nx release`: version bump, changelog, GitHub release, npm publish for `platform:npm` projects
3. `desktop-release` — macOS runner, signs and packages `.pkg` installers for desktop apps, uploads to the GitHub release
4. `compute-jvm-projects` — resolves `tag:platform:jvm` project names at runtime via `nx show projects --json --quiet | jq -c '.'`
5. `publish-agentos-artifacts` — matrix job (one runner per JVM project) that publishes each artifact to GitHub Packages

## Adding a new JVM plugin

For a new agentos plugin, the local Nx plugin at `tools/plugins/agentos-gradle/` automatically
infers `build`, `test`, `clean`, and `publish` targets for any project that has a `project.json`
and a `build.gradle.kts`. Adding `platform:jvm` to its tags is the only step needed to have it
built, tested (in CI via `validate.yml`) and published (in CI via `release.yml`).
