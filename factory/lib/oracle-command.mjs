/**
 * Construction de la commande effective d'un oracle.
 *
 * Ce module est séparé d'oracle.mjs (qui est stabilisé et interdit de
 * modification) et de domains.mjs (qui ne porte que des données).
 *
 * ## HISTORIQUE DES INCIDENTS ET ÉVOLUTIONS
 *
 * ### Incident F21 (2026-08-20) — `affected --base=sprint` : 1033 projets, tous en cache
 *
 * `pnpm nx affected -t frontend-test` utilise `--base=sprint` (configuré dans
 * le nx.json du dépôt cible). La branche de travail diverge de `sprint` de
 * 16 427 commits et 33 337 fichiers. Nx inclut TOUTE cette divergence dans le
 * périmètre, pas seulement les fichiers du tour courant. Résultat : 1033
 * projets affectés, tous servis par le cache (leurs inputs n'ont pas changé
 * depuis le dernier run sur cet état), `executed: 0`. La garde A8 (succès
 * vide) fait alors échouer le run — non pas parce que le code est incorrect,
 * mais parce que l'instrument est structurellement aveugle dans ce contexte.
 *
 * ### Correctif A2 (C4) — `affected --files=<liste>` : clôture transitive
 *
 * Remplacement par `pnpm nx affected --files=chemin1,chemin2`. Ignore la
 * divergence de branche et calcule le graphe de dépendances à partir des
 * fichiers explicitement listés. Vérifié expérimentalement : 1 fichier
 * modifié → 53 projets dont les dépendants transitifs. La réserve sur la
 * clôture du graphe était levée : `--files` n'est pas un filtre par projet,
 * c'est une clôture du graphe de dépendances.
 *
 * ### Incident suivant — cache partagé + clôture transitive : 216 projets bloqués
 *
 * Avec `--files=<6 fichiers>`, Nx calcule 216 projets (clôture transitive),
 * tous servis depuis le cache. Cause : le cache Nx est partagé entre la
 * factory et l'environnement de développement de l'utilisateur. Ces 216
 * projets ont déjà été exécutés sur cet état modifié — leur cache est valide,
 * Nx ne re-exécute rien. La garde A8 (`executed: 0`) fait échouer le run —
 * correctement, mais le run est bloqué en permanence.
 *
 * `--skip-nx-cache` a été testé expérimentalement : il force l'exécution mais
 * ne réduit pas le périmètre. Résultat avec `--skip-nx-cache --files=<3
 * fichiers>` : 375 projets. Le problème de périmètre reste entier.
 *
 * ### Solution retenue — projets directs, pas clôture transitive
 *
 * L'oracle doit exécuter `frontend-test` uniquement sur les projets qui
 * **contiennent** les fichiers modifiés — pas leurs dépendants.
 *
 * Raisonnement : si un fichier d'une lib est modifié, les tests à exécuter
 * sont **les tests de cette lib**. Si ces tests passent, le code de la lib est
 * correct. Les projets consommateurs (les dépendants transitifs) ont leurs
 * propres tests — mais ils testent leur propre logique, pas les changements
 * dans la lib. Un test unitaire dans `aphrodite` ne valide pas un changement
 * dans `entity-list-base` : il valide `aphrodite`.
 *
 * Pour 6 fichiers modifiés dans 3 libs différentes : 3 projets au lieu de 216.
 *
 * La commande effective finale a la forme :
 *   pnpm nx run-many --target=<cible> --projects=proj1,proj2 --skip-nx-cache
 *
 * `--skip-nx-cache` est obligatoire : le cache est partagé avec l'environnement
 * de dev, et les projets directs peuvent avoir été exécutés sur cet état.
 *
 * **Limite assumée** : des régressions dans les consommateurs ne seront pas
 * détectées. C'est acceptable parce que :
 *   1. Le type-check (`verify-types`) valide déjà que les interfaces sont
 *      compatibles avec les consommateurs principaux.
 *   2. Les consommateurs ont leurs propres tests dans la CI.
 *   3. Un oracle qui ne tourne jamais (bloqué par le cache) ne détecte rien.
 *
 * ## Résolution du projet propriétaire d'un fichier
 *
 * Chaque projet Nx a un `project.json` dans son dossier racine. Pour trouver
 * le projet propriétaire d'un fichier, on remonte les dossiers parents jusqu'à
 * trouver un `project.json`, puis on lit son champ `name`.
 *
 * Cette résolution se fait en Node pur avec `existsSync` et `readFileSync` —
 * aucun appel Nx, aucun appel réseau.
 *
 * ## MARQUAGE `filesArg: true` SUR UN ORACLE
 *
 * Un oracle portant `filesArg: true` dans domains.mjs signale que sa commande
 * doit être construite via `buildOracleCommand` avec la liste des fichiers
 * modifiés. Ce champ est optionnel : un oracle sans `filesArg` (comme `types`
 * et `build`) reçoit sa commande telle quelle — leur périmètre est fixe et
 * indépendant du diff.
 *
 * ## CAS LIMITE : liste vide
 *
 * Si `files.length === 0`, on laisse la commande inchangée plutôt que de
 * produire une commande invalide. Ce cas ne devrait pas survenir en pratique :
 * la garde `wroteNothing` dans les workflows arrête le run avant d'atteindre
 * les oracles si l'agent n'a rien modifié.
 */

import { existsSync, readFileSync } from 'node:fs'
import { join, dirname } from 'node:path'

// ---------------------------------------------------------------------------
// Build host resolution
// ---------------------------------------------------------------------------

/**
 * Sentinel returned by resolveBuildHosts when the mapping is absent or when
 * no host can be found for the given owner projects.
 *
 * The workflow treats this sentinel as an ORACLE_INFRASTRUCTURE signal:
 * it opens the human gate with explicit evidence rather than proceeding with
 * an empty or invalid command.
 *
 * @typedef {{ noHost: true, reason: string, ownerProjects: string[] }} NoHostResult
 */

/**
 * Résout les projets hôtes buildables depuis les projets propriétaires.
 *
 * Les libs (projets propriétaires des fichiers modifiés) n'ont généralement
 * pas de cible `build` Angular. Elles sont consommées par des apps hôtes qui,
 * elles, ont une cible `build`.
 *
 * La résolution se fait via un mapping explicite, chargé depuis la variable
 * d'environnement `FACTORY_FRONT_BUILD_HOST_MAP` (JSON). Ce mapping est
 * intentionnellement statique : le graphe Nx est instable et peut contenir
 * des centaines de projets. Un mapping explicite est prévisible et auditeable.
 *
 * Format du mapping :
 *   { "<owner>": ["<host-app>", ...], "*": ["<fallback-host>", ...] }
 *
 * La clé `"*"` est un fallback global : si un propriétaire n'est pas dans
 * la map, les hôtes du fallback sont utilisés. Si la map est absente, ou si
 * aucun hôte n'est trouvé, retourne `{ noHost: true, reason, ownerProjects }`.
 *
 * Cette fonction vérifie aussi que chaque hôte a réellement une cible `build`
 * dans son `project.json` (champ `targets.build` ou `targets.build-angular`).
 * Si un hôte référencé dans la map n'a pas de cible build, il est exclu avec
 * un avertissement — jamais silencieusement accepté.
 *
 * @param {string[]} ownerProjects  Projets propriétaires des fichiers modifiés.
 * @param {string}   repoRoot       Chemin absolu de la racine du dépôt cible.
 * @returns {string[] | NoHostResult}
 *   Liste dédupliquée de noms de projets hôtes buildables, ou sentinel NoHostResult.
 */
export function resolveBuildHosts(ownerProjects, repoRoot) {
  // Charger le mapping depuis l'env.
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
      ownerProjects,
    }
  }

  let hostMap
  try {
    hostMap = JSON.parse(mapRaw)
  } catch (err) {
    return {
      noHost: true,
      reason:
        'FACTORY_FRONT_BUILD_HOST_MAP is not valid JSON: ' + String(err) + '. ' + 'Raw value: ' + mapRaw.slice(0, 200),
      ownerProjects,
    }
  }

  if (typeof hostMap !== 'object' || hostMap === null || Array.isArray(hostMap)) {
    return {
      noHost: true,
      reason: 'FACTORY_FRONT_BUILD_HOST_MAP must be a JSON object, got: ' + typeof hostMap,
      ownerProjects,
    }
  }

  const fallbackHosts = Array.isArray(hostMap['*']) ? hostMap['*'] : []

  // Collecter les hôtes pour chaque propriétaire.
  const seen = new Set()
  const hosts = []

  for (const owner of ownerProjects) {
    const mapped = Array.isArray(hostMap[owner]) ? hostMap[owner] : fallbackHosts
    for (const host of mapped) {
      if (typeof host === 'string' && !seen.has(host)) {
        seen.add(host)
        hosts.push(host)
      }
    }
  }

  // Si aucun hôte trouvé via la map ni le fallback.
  if (hosts.length === 0) {
    return {
      noHost: true,
      reason:
        'No buildable host found for owner projects: ' +
        ownerProjects.join(', ') +
        '. ' +
        'The host map has no entry for these projects and no fallback ("*") is defined. ' +
        'Add entries to FACTORY_FRONT_BUILD_HOST_MAP.',
      ownerProjects,
    }
  }

  // Vérifier que chaque hôte a réellement une cible `build` dans son project.json.
  // Un hôte sans cible `build` est exclu avec avertissement — jamais silencieux.
  const validHosts = []
  const invalidHosts = []

  for (const host of hosts) {
    // Chercher le project.json du hôte en remontant depuis des chemins probables.
    // Stratégie : chercher `**/project.json` dont le `name` === host dans repoRoot.
    // Pour éviter une recherche récursive coûteuse, on tente d'abord les chemins
    // conventionnels (apps/<host>/project.json, frontend/apps/<host>/project.json).
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
          const json = JSON.parse(readFileSync(candidate, 'utf8'))
          // Accepter 'build' ou 'build-angular' comme cible de build Angular.
          if (json.targets && (json.targets['build'] !== undefined || json.targets['build-angular'] !== undefined)) {
            hasBuildTarget = true
          }
        } catch {
          // project.json malformé : on considère qu'il n'a pas de cible build.
        }
        break
      }
    }

    if (!found) {
      // project.json non trouvé dans les chemins conventionnels.
      // On l'accepte quand même (le dépôt peut avoir une structure non standard)
      // mais on loggue un avertissement.
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
      ownerProjects,
    }
  }

  return validHosts
}

/**
 * Résout les noms de projets Nx propriétaires d'une liste de fichiers.
 *
 * Pour chaque fichier, remonte les dossiers parents jusqu'à trouver un
 * `project.json` contenant un champ `name`. Retourne la liste dédupliquée
 * des noms de projets trouvés.
 *
 * Les fichiers sans `project.json` dans leur arborescence sont ignorés
 * silencieusement — ils n'appartiennent à aucun projet Nx connu.
 *
 * @param {string[]} files    Chemins relatifs à `repoRoot` des fichiers modifiés.
 * @param {string}   repoRoot Chemin absolu de la racine du dépôt.
 * @returns {string[]} Noms de projets Nx dédupliqués, dans l'ordre de première
 *                     apparition.
 */
export function resolveOwnerProjects(files, repoRoot) {
  const seen = new Set()
  const projects = []

  for (const file of files) {
    // Chemin absolu du fichier, puis on part du dossier parent.
    const absoluteFile = join(repoRoot, file)
    let dir = dirname(absoluteFile)

    // Remontée vers la racine : on cherche le premier `project.json`.
    // On s'arrête quand on atteint la racine du dépôt (ou le système de
    // fichiers) pour ne pas traverser des projets non liés au dépôt cible.
    while (dir.length >= repoRoot.length) {
      const candidate = join(dir, 'project.json')

      if (existsSync(candidate)) {
        // `project.json` trouvé : lire le champ `name`.
        // Si le JSON est malformé ou si `name` est absent, on ignore ce fichier.
        try {
          const json = JSON.parse(readFileSync(candidate, 'utf8'))
          if (json.name && typeof json.name === 'string') {
            if (!seen.has(json.name)) {
              seen.add(json.name)
              projects.push(json.name)
            }
          }
          // `project.json` trouvé mais sans `name` : pas de projet propriétaire,
          // on arrête la remontée pour ce fichier (inutile de continuer).
        } catch {
          // JSON malformé : on arrête la remontée pour ce fichier.
        }
        // Qu'on ait trouvé un `name` ou non, le premier `project.json` est
        // le propriétaire — on n'en cherche pas d'autre plus haut.
        break
      }

      const parent = dirname(dir)
      // Arrêt si on est arrivé à la racine du système de fichiers
      // (dirname('/') === '/' sur Unix, dirname('C:\\') === 'C:\\' sur Windows).
      if (parent === dir) break
      dir = parent
    }
  }

  return projects
}

/**
 * Extrait la cible Nx depuis une commande template.
 *
 * Cherche `-t <valeur>` ou `--target=<valeur>` dans la commande.
 * Retourne `null` si aucune des deux formes n'est présente.
 *
 * Cette extraction est nécessaire pour construire `run-many --target=<cible>`
 * sans coder la cible en dur : l'utilisateur peut avoir surchargé la commande
 * via `FACTORY_COMMAND_FRONT`, et la cible peut être différente de
 * `frontend-test`.
 *
 * @param {string} command  La commande template (ex. `pnpm nx affected -t frontend-test`).
 * @returns {string|null}   La cible extraite, ou `null` si non trouvée.
 */
function extractTarget(command) {
  // Forme courte : `-t <valeur>` (séparés par un espace)
  const shortMatch = command.match(/(?:^|\s)-t\s+(\S+)/)
  if (shortMatch) return shortMatch[1]

  // Forme longue : `--target=<valeur>`
  const longMatch = command.match(/(?:^|\s)--target=(\S+)/)
  if (longMatch) return longMatch[1]

  return null
}

/**
 * Construit la commande effective à passer à `runCommand` pour un oracle donné.
 *
 * Quatre cas :
 *
 * 1. Oracle sans `filesArg` ni `buildHostArg` : périmètre fixe, commande inchangée.
 *    (cas de `build` Gradle)
 *
 * 2. Oracle avec `buildHostArg: true` :
 *    - Résoudre les projets propriétaires via `resolveOwnerProjects`.
 *    - Mapper les propriétaires vers des apps hôtes buildables via `resolveBuildHosts`.
 *    - Si aucun hôte trouvé : retourner un sentinel `{ noHost: true, reason, ownerProjects }`.
 *    - Si des hôtes trouvés : construire
 *      `<command-template> --projects=host1,host2`
 *      (la commande template contient déjà `--skip-nx-cache` et `--configuration=development`).
 *
 * 3. Oracle avec `filesArg: true` et liste vide : commande inchangée.
 *    (passer `--projects=` vide serait invalide pour Nx)
 *
 * 4. Oracle avec `filesArg: true` et fichiers non vides :
 *    - Résoudre les projets propriétaires via `resolveOwnerProjects`.
 *    - Si des projets sont trouvés : construire
 *      `pnpm nx run-many --target=<cible> --projects=proj1,proj2 --skip-nx-cache`.
 *    - Si aucun projet n'est trouvé (cas limite) : retourner la commande
 *      template sans modification, et loguer un avertissement.
 *    - Si la cible ne peut pas être extraite (commande mal formée, surcharge
 *      FACTORY_COMMAND_FRONT sans `-t` ni `--target`) : retourner la commande
 *      template sans modification.
 *
 * SENTINEL `noHost`
 * ─────────────────
 * Quand `buildHostArg: true` et qu'aucun hôte buildable n'est trouvé, la
 * fonction retourne `{ noHost: true, reason, ownerProjects }` au lieu d'une
 * chaîne. Le workflow doit tester `typeof result !== 'string'` avant d'utiliser
 * la commande. Un sentinel `noHost` doit être traité comme ORACLE_INFRASTRUCTURE
 * (gate humain avec la raison explicitée), jamais comme un succès vide.
 *
 * @param {{ command: string, filesArg?: boolean, buildHostArg?: boolean }} oracle
 *   L'oracle tel que défini dans domains.mjs.
 * @param {string[]} files
 *   Liste de chemins relatifs à `repoRoot` des fichiers modifiés par l'éditeur.
 *   Calculée via `diffSince(beforeAgent, REPO_ROOT).modified`.
 * @param {string} repoRoot
 *   Chemin absolu de la racine du dépôt cible. Nécessaire pour résoudre les
 *   `project.json` via `resolveOwnerProjects`.
 * @returns {string | import('./oracle-command.mjs').NoHostResult}
 *   La commande effective, prête à être passée à `runCommand`, ou un sentinel
 *   NoHostResult si aucun hôte buildable n'est trouvé.
 */
export function buildOracleCommand(oracle, files, repoRoot) {
  // Cas 2 : oracle avec `buildHostArg: true` — résolution des hôtes buildables.
  //
  // Le propriétaire direct (lib) n'a pas de cible `build` Angular. On le mappe
  // vers des apps hôtes via resolveBuildHosts. Si aucun hôte n'est trouvé,
  // on retourne un sentinel NoHostResult — jamais un succès vide.
  if (oracle.buildHostArg) {
    // Résoudre les propriétaires des fichiers modifiés.
    const ownerProjects = files.length > 0 ? resolveOwnerProjects(files, repoRoot) : []

    if (ownerProjects.length === 0 && files.length > 0) {
      // Aucun fichier sous un projet Nx connu. Pas de propriétaires → pas d'hôtes.
      // Retourner le sentinel avec une raison explicite.
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
      // Aucun fichier modifié (liste vide) : retourner le sentinel.
      // Ce cas ne devrait pas survenir (garde `wroteNothing` dans le workflow),
      // mais on le traite explicitement.
      return {
        noHost: true,
        reason: 'No files provided to build oracle. Cannot resolve build host applications.',
        ownerProjects: [],
      }
    }

    // Mapper les propriétaires vers des hôtes buildables.
    const hostsResult = resolveBuildHosts(ownerProjects, repoRoot)

    // Si la résolution échoue, propager le sentinel.
    if (!Array.isArray(hostsResult)) {
      return hostsResult
    }

    // Hôtes trouvés : construire la commande `run-many` avec les hôtes.
    //
    // La commande template contient déjà `--skip-nx-cache` et
    // `--configuration=development` — on y ajoute `--projects=<hôtes>`.
    // Si la commande template a été surchargée via FACTORY_COMMAND_FRONT_BUILD,
    // elle peut déjà contenir `--projects=...` — dans ce cas, on ne l'ajoute
    // pas (la surcharge est autoritaire).
    if (oracle.command.includes('--projects=')) {
      // La commande template contient déjà `--projects` : retourner telle quelle.
      // C'est le cas d'une surcharge FACTORY_COMMAND_FRONT_BUILD avec périmètre fixe.
      return oracle.command
    }

    return oracle.command + ' --projects=' + hostsResult.join(',')
  }

  // Cas 1 : oracle sans `filesArg` ni `buildHostArg` — périmètre fixe, pas d'injection.
  // C'est le cas de `build` Gradle (command fixe, cwd=agentos).
  if (!oracle.filesArg) {
    return oracle.command
  }

  // Cas 3 : `filesArg: true` mais liste vide.
  // Passer `--projects=` vide serait un argument invalide pour Nx.
  // On retourne la commande template.
  //
  // Ce cas ne devrait pas survenir en pratique : la garde `wroteNothing` dans
  // les workflows arrête le run avant d'atteindre les oracles si l'agent n'a
  // rien modifié. Si on arrive ici, c'est un bug de séquence — la commande
  // template dans le registre rend le problème visible.
  if (files.length === 0) {
    return oracle.command
  }

  // Cas 4 : `filesArg: true` avec fichiers — stratégie `run-many --projects`.
  //
  // On résout les projets propriétaires des fichiers modifiés, puis on
  // construit une commande `run-many` ciblée. Voir le bloc de commentaire
  // du module pour le raisonnement complet.

  // Extraire la cible depuis la commande template.
  // Si la commande a été surchargée via FACTORY_COMMAND_FRONT sans `-t` ni
  // `--target`, on ne sait pas quelle cible utiliser : retourner la commande
  // template sans modification plutôt que de construire une commande invalide.
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

  // Résoudre les projets propriétaires des fichiers modifiés.
  const projects = resolveOwnerProjects(files, repoRoot)

  if (projects.length === 0) {
    // Cas limite : aucun fichier ne se trouve sous un projet Nx connu.
    // Cela peut arriver si les fichiers modifiés sont dans des dossiers
    // racine (scripts, config) sans `project.json`.
    // On retourne la commande template pour ne pas bloquer le run.
    console.warn(
      '[oracle-command] Aucun projet Nx trouvé pour les fichiers modifiés : ' +
        files.join(', ') +
        '. ' +
        'La commande template est retournée sans modification.'
    )
    return oracle.command
  }

  // Construction de la commande `run-many` avec les projets directs.
  //
  // `--skip-nx-cache` est obligatoire : le cache Nx est partagé entre la
  // factory et l'environnement de dev de l'utilisateur. Les projets directs
  // peuvent avoir été exécutés sur cet état modifié — sans `--skip-nx-cache`,
  // on tomberait dans le même piège que l'incident `affected --files` (216
  // projets tous en cache, executed: 0, garde A8 bloquante).
  return 'pnpm nx run-many' + ' --target=' + target + ' --projects=' + projects.join(',') + ' --skip-nx-cache'
}
