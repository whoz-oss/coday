# Release Pipeline

Releases trigger automatically on push to `master` via `.github/workflows/release.yml`, using `nx release` with conventional commits.

## Adding a new project to the release pipeline

Project inclusion is tag-driven — no CI or `nx.json` changes needed:

- Add `platform:npm` to a Node.js/Electron project → published to npm
- Add `platform:jvm` to a Kotlin/Gradle project → published to GitHub Packages

## Pipeline jobs

1. `release` — runs `nx release`: version bump, changelog, GitHub release, npm publish for `platform:npm` projects
2. `upload-agentos-jars` — builds the AgentOS JARs and uploads them to the GitHub release
3. `compute-jvm-projects` — resolves `tag:platform:jvm` project names at runtime via `nx show projects --json --quiet | jq -c '.'`
4. `publish-agentos-artifacts` — matrix job (one runner per JVM project) that publishes each artifact to GitHub Packages

## Desktop apps (removed from the release)

The `desktop-release` job (macOS `.pkg` build, signing and notarization) has been removed from `release.yml`
for the time being.

`desktop` and `desktop-twin` no longer carry the `platform:npm` tag, so `nx release` does not version,
changelog, tag or publish them. Their `package-pkg` / `package-dist` targets remain available for local builds.
To bring the desktop release back, restore the `desktop-release` job and re-add `platform:npm` to both projects.

## Adding a new JVM plugin

For a new agentos plugin, the local Nx plugin at `tools/plugins/agentos-gradle/` automatically
infers `build`, `test`, `clean`, and `publish` targets for any project that has a `project.json`
and a `build.gradle.kts`. Adding `platform:jvm` to its tags is the only step needed to have it
built, tested (in CI via `validate.yml`) and published (in CI via `release.yml`).
