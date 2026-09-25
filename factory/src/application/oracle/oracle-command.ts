/**
 * Construction de la commande effective d'un oracle.
 *
 * Application layer: this module touches the filesystem (`node:fs`) and the
 * environment (`process.env`) to discover Nx owner projects and buildable host
 * apps. It is deliberately separate from the pure domain (`domain/oracle/`).
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/oracle-command.mjs`
 * is a stateless compatibility facade re-exporting from that bundle.
 *
 * ## Résolution du projet propriétaire d'un fichier
 * Chaque projet Nx a un `project.json` dans son dossier racine. On remonte les
 * dossiers parents jusqu'au premier `project.json`, puis on lit son `name`.
 *
 * ## Résolution des hôtes buildables
 * Les libs (projets propriétaires) n'ont pas de cible `build` Angular. On les
 * mappe vers des apps hôtes via `FACTORY_FRONT_BUILD_HOST_MAP` (JSON explicite,
 * auditable), en vérifiant que chaque hôte a une cible `build`/`build-angular`.
 */

import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

/**
 * Sentinel returned by `resolveBuildHosts` (and `buildOracleCommand`) when no
 * buildable host can be found. The workflow must treat it as an
 * ORACLE_INFRASTRUCTURE signal — never as an empty success.
 */
export interface NoHostResult {
  noHost: true
  reason: string
  ownerProjects: string[]
}

/** The command to hand to `runCommand`, or a `NoHostResult` sentinel. */
export type OracleCommandResult = string | NoHostResult

/** An oracle definition as consumed by `buildOracleCommand`. */
export interface OracleCommandSpec {
  command: string
  filesArg?: boolean
  buildHostArg?: boolean
}

/**
 * Résout les projets hôtes buildables depuis les projets propriétaires.
 *
 * Voir le mapping `FACTORY_FRONT_BUILD_HOST_MAP` (JSON) :
 *   { "<owner>": ["<host-app>", ...], "*": ["<fallback-host>", ...] }
 *
 * @returns Liste dédupliquée de noms de projets hôtes buildables, ou sentinel.
 */
export function resolveBuildHosts(ownerProjects: readonly string[], repoRoot: string): string[] | NoHostResult {
  const mapRaw = process.env.FACTORY_FRONT_BUILD_HOST_MAP
  if (!mapRaw) {
    return {
      noHost: true,
      reason:
        'FACTORY_FRONT_BUILD_HOST_MAP is not set. ' +
        'Cannot resolve buildable host applications for owner projects: ' +
        ownerProjects.join(', ') +
        '. ' +
        'Set this env var to a JSON map of owner project → host app(s). ' +
        'Example: \'{"*":["aphrodite","admin","agentic-studio","copilot-chat"]}\'. ' +
        'See factory/lib/domains.mjs for documentation.',
      ownerProjects: [...ownerProjects],
    }
  }

  let hostMap: unknown
  try {
    hostMap = JSON.parse(mapRaw)
  } catch (err) {
    return {
      noHost: true,
      reason:
        'FACTORY_FRONT_BUILD_HOST_MAP is not valid JSON: ' + String(err) + '. ' + 'Raw value: ' + mapRaw.slice(0, 200),
      ownerProjects: [...ownerProjects],
    }
  }

  if (typeof hostMap !== 'object' || hostMap === null || Array.isArray(hostMap)) {
    return {
      noHost: true,
      reason: 'FACTORY_FRONT_BUILD_HOST_MAP must be a JSON object, got: ' + typeof hostMap,
      ownerProjects: [...ownerProjects],
    }
  }

  const record = hostMap as Record<string, unknown>
  const fallbackHosts = Array.isArray(record['*']) ? (record['*'] as unknown[]) : []

  const seen = new Set<string>()
  const hosts: string[] = []

  for (const owner of ownerProjects) {
    const mapped = Array.isArray(record[owner]) ? (record[owner] as unknown[]) : fallbackHosts
    for (const host of mapped) {
      if (typeof host === 'string' && !seen.has(host)) {
        seen.add(host)
        hosts.push(host)
      }
    }
  }

  if (hosts.length === 0) {
    return {
      noHost: true,
      reason:
        'No buildable host found for owner projects: ' +
        ownerProjects.join(', ') +
        '. ' +
        'The host map has no entry for these projects and no fallback ("*") is defined. ' +
        'Add entries to FACTORY_FRONT_BUILD_HOST_MAP.',
      ownerProjects: [...ownerProjects],
    }
  }

  // Vérifier que chaque hôte a réellement une cible `build` dans son project.json.
  // Un hôte sans cible `build` est exclu avec avertissement — jamais silencieux.
  const validHosts: string[] = []
  const invalidHosts: string[] = []

  for (const host of hosts) {
    const candidatePaths = [
      join(repoRoot, 'apps', host, 'project.json'),
      join(repoRoot, 'frontend', 'apps', host, 'project.json'),
      join(repoRoot, host, 'project.json'),
    ]

    let hasBuildTarget = false
    let found = false

    for (const candidate of candidatePaths) {
      if (existsSync(candidate)) {
        found = true
        try {
          const json = JSON.parse(readFileSync(candidate, 'utf8')) as {
            targets?: Record<string, unknown>
          }
          if (json.targets && (json.targets['build'] !== undefined || json.targets['build-angular'] !== undefined)) {
            hasBuildTarget = true
          }
        } catch {
          // project.json malformé : considéré comme sans cible build.
        }
        break
      }
    }

    if (!found) {
      console.warn(
        '[oracle-command] resolveBuildHosts: project.json not found for host "' +
          host +
          '" ' +
          'in conventional paths (' +
          candidatePaths.map((p) => p.replace(repoRoot, '<root>')).join(', ') +
          '). ' +
          'Accepting host tentatively — verify that it has a build target.'
      )
      validHosts.push(host)
      continue
    }

    if (hasBuildTarget) {
      validHosts.push(host)
    } else {
      invalidHosts.push(host)
      console.warn(
        '[oracle-command] resolveBuildHosts: host "' +
          host +
          '" has no `build` or ' +
          '`build-angular` target in its project.json. Excluding from build oracle scope. ' +
          'Update FACTORY_FRONT_BUILD_HOST_MAP to use a host with a real build target.'
      )
    }
  }

  if (validHosts.length === 0) {
    return {
      noHost: true,
      reason:
        'All resolved hosts (' +
        hosts.join(', ') +
        ') lack a `build` or `build-angular` ' +
        'target in their project.json. ' +
        'Owner projects: ' +
        ownerProjects.join(', ') +
        '. ' +
        'Excluded hosts: ' +
        invalidHosts.join(', ') +
        '. ' +
        'Update FACTORY_FRONT_BUILD_HOST_MAP to reference apps with real build targets.',
      ownerProjects: [...ownerProjects],
    }
  }

  return validHosts
}

/**
 * Résout les noms de projets Nx propriétaires d'une liste de fichiers.
 *
 * Les fichiers sans `project.json` dans leur arborescence sont ignorés
 * silencieusement — ils n'appartiennent à aucun projet Nx connu.
 */
export function resolveOwnerProjects(files: readonly string[], repoRoot: string): string[] {
  const seen = new Set<string>()
  const projects: string[] = []

  for (const file of files) {
    const absoluteFile = join(repoRoot, file)
    let dir = dirname(absoluteFile)

    while (dir.length >= repoRoot.length) {
      const candidate = join(dir, 'project.json')

      if (existsSync(candidate)) {
        try {
          const json = JSON.parse(readFileSync(candidate, 'utf8')) as { name?: unknown }
          if (json.name && typeof json.name === 'string') {
            if (!seen.has(json.name)) {
              seen.add(json.name)
              projects.push(json.name)
            }
          }
        } catch {
          // JSON malformé : on arrête la remontée pour ce fichier.
        }
        break
      }

      const parent = dirname(dir)
      if (parent === dir) break
      dir = parent
    }
  }

  return projects
}

/**
 * Extrait la cible Nx depuis une commande template.
 *
 * Cherche `-t <valeur>` ou `--target=<valeur>`. Retourne `null` si absente.
 */
function extractTarget(command: string): string | null {
  const shortMatch = command.match(/(?:^|\s)-t\s+(\S+)/)
  if (shortMatch) return shortMatch[1] ?? null

  const longMatch = command.match(/(?:^|\s)--target=(\S+)/)
  if (longMatch) return longMatch[1] ?? null

  return null
}

/**
 * Construit la commande effective à passer à `runCommand` pour un oracle donné.
 *
 * Quatre cas : périmètre fixe (commande inchangée), `buildHostArg` (résolution
 * des hôtes), `filesArg` avec liste vide (commande inchangée) et `filesArg` avec
 * fichiers (`pnpm nx run-many --target=<cible> --projects=... --skip-nx-cache`).
 *
 * Un `NoHostResult` doit être traité comme ORACLE_INFRASTRUCTURE (gate humain),
 * jamais comme un succès vide.
 */
export function buildOracleCommand(
  oracle: OracleCommandSpec,
  files: readonly string[],
  repoRoot: string
): OracleCommandResult {
  // Cas 2 : `buildHostArg` — résolution des hôtes buildables.
  if (oracle.buildHostArg) {
    const ownerProjects = files.length > 0 ? resolveOwnerProjects(files, repoRoot) : []

    if (ownerProjects.length === 0 && files.length > 0) {
      return {
        noHost: true,
        reason:
          'No Nx owner project found for modified files: ' +
          files.join(', ') +
          '. ' +
          'Modified files may be in root-level directories without a project.json.',
        ownerProjects: [],
      }
    }

    if (ownerProjects.length === 0) {
      return {
        noHost: true,
        reason: 'No files provided to build oracle. Cannot resolve build host applications.',
        ownerProjects: [],
      }
    }

    const hostsResult = resolveBuildHosts(ownerProjects, repoRoot)

    if (!Array.isArray(hostsResult)) {
      return hostsResult
    }

    if (oracle.command.includes('--projects=')) {
      return oracle.command
    }

    return oracle.command + ' --projects=' + hostsResult.join(',')
  }

  // Cas 1 : ni `filesArg` ni `buildHostArg` — périmètre fixe.
  if (!oracle.filesArg) {
    return oracle.command
  }

  // Cas 3 : `filesArg: true` mais liste vide.
  if (files.length === 0) {
    return oracle.command
  }

  // Cas 4 : `filesArg: true` avec fichiers — stratégie `run-many --projects`.
  const target = extractTarget(oracle.command)
  if (!target) {
    console.warn(
      "[oracle-command] Impossible d'extraire la cible Nx depuis la commande template : " +
        oracle.command +
        '. ' +
        'La commande template est retournée sans modification. ' +
        'Vérifier que FACTORY_COMMAND_FRONT contient `-t <cible>` ou `--target=<cible>`.'
    )
    return oracle.command
  }

  const projects = resolveOwnerProjects(files, repoRoot)

  if (projects.length === 0) {
    console.warn(
      '[oracle-command] Aucun projet Nx trouvé pour les fichiers modifiés : ' +
        files.join(', ') +
        '. ' +
        'La commande template est retournée sans modification.'
    )
    return oracle.command
  }

  return 'pnpm nx run-many' + ' --target=' + target + ' --projects=' + projects.join(',') + ' --skip-nx-cache'
}
