/**
 * Updates a version entry in a Gradle version catalog TOML string.
 * Pure function — no file I/O, easy to test.
 */
export function updateTomlVersion(tomlContent: string, key: string, version: string): string {
  return tomlContent.replace(new RegExp(`^(${key}\\s*=\\s*").*("$)`, 'm'), `$1${version}$2`)
}

/**
 * Updates multiple version entries in a Gradle version catalog TOML string in one pass.
 * Pure function — no file I/O, easy to test.
 */
export function updateTomlVersions(tomlContent: string, keys: string[], version: string): string {
  return keys.reduce((content, key) => updateTomlVersion(content, key, version), tomlContent)
}
