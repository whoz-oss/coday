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
 * Trois cas :
 *
 * 1. Oracle sans `filesArg` : périmètre fixe, commande inchangée.
 *    (cas de `types` et `build`)
 *
 * 2. Oracle avec `filesArg: true` et liste vide : commande inchangée.
 *    (passer `--files=` vide ou `--projects=` vide serait invalide pour Nx)
 *
 * 3. Oracle avec `filesArg: true` et fichiers non vides :
 *    - Résoudre les projets propriétaires via `resolveOwnerProjects`.
 *    - Si des projets sont trouvés : construire
 *      `pnpm nx run-many --target=<cible> --projects=proj1,proj2 --skip-nx-cache`.
 *    - Si aucun projet n'est trouvé (cas limite) : retourner la commande
 *      template sans modification, et loguer un avertissement.
 *    - Si la cible ne peut pas être extraite (commande mal formée, surcharge
 *      FACTORY_COMMAND_FRONT sans `-t` ni `--target`) : retourner la commande
 *      template sans modification.
 *
 * @param {{ command: string, filesArg?: boolean }} oracle
 *   L'oracle tel que défini dans domains.mjs.
 * @param {string[]} files
 *   Liste de chemins relatifs à `repoRoot` des fichiers modifiés par l'éditeur.
 *   Calculée via `diffSince(beforeAgent, REPO_ROOT).modified`.
 * @param {string} repoRoot
 *   Chemin absolu de la racine du dépôt cible. Nécessaire pour résoudre les
 *   `project.json` via `resolveOwnerProjects`.
 * @returns {string}
 *   La commande effective, prête à être passée à `runCommand`.
 */
export function buildOracleCommand(oracle, files, repoRoot) {
  // Cas 1 : oracle sans `filesArg` — périmètre fixe, pas d'injection.
  // C'est le cas de `types` (liste de projets figée) et `build` (Gradle).
  if (!oracle.filesArg) {
    return oracle.command
  }

  // Cas 2 : `filesArg: true` mais liste vide.
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

  // Cas 3 : `filesArg: true` avec fichiers — stratégie `run-many --projects`.
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
      '[oracle-command] Impossible d\'extraire la cible Nx depuis la commande template : ' +
        oracle.command + '. ' +
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
        files.join(', ') + '. ' +
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
  return 'pnpm nx run-many' +
    ' --target=' + target +
    ' --projects=' + projects.join(',') +
    ' --skip-nx-cache'
}
