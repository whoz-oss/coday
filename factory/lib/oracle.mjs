/**
 * Oracle : exécution de commandes de vérité.
 *
 * Le verdict est `exitCode === 0`, rien d'autre.
 * On n'interprète jamais le contenu de la sortie pour DECIDER.
 *
 * NUANCE IMPORTANTE (incident du 2026-08-19) :
 * `countTaskOutcomes` lit la sortie, mais ne rend aucun verdict. Un build
 * Gradle entièrement servi par le cache retourne `exitCode: 0` sans avoir rien
 * exécuté : le verdict est alors vrai et vide. Compter les tâches ne corrige
 * pas le verdict, ça dit sur quoi il portait. La décision reste au code de
 * sortie ; le décompte est un fait enregistré à côté.
 */

import { spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { join } from 'node:path'

const MAX_OUTPUT_CHARS = 100_000

/**
 * Tronque une chaîne à `MAX_OUTPUT_CHARS` caractères.
 *
 * @param {string} s
 * @returns {string}
 */
function truncate(s) {
  if (s.length <= MAX_OUTPUT_CHARS) return s
  return s.slice(0, MAX_OUTPUT_CHARS) + `\n[... tronqué à ${MAX_OUTPUT_CHARS} caractères]`
}

/**
 * Exécute une commande shell et retourne son résultat.
 *
 * Le verdict est `exitCode === 0`. Aucune interprétation du contenu
 * de stdout ou stderr.
 *
 * @param {string} command
 * @param {{ cwd?: string, timeoutMs?: number }} options
 * @returns {{
 *   exitCode: number,
 *   stdout: string,
 *   stderr: string,
 *   durationMs: number,
 *   timedOut: boolean,
 * }}
 */
export function runCommand(command, { cwd, timeoutMs } = {}) {
  const start = Date.now()

  const result = spawnSync(command, {
    shell: true,
    cwd,
    timeout: timeoutMs,
    encoding: 'utf8',
    maxBuffer: 200 * 1024 * 1024, // 200 MB pour éviter les troncatures internes
  })

  const durationMs = Date.now() - start
  const timedOut = result.signal === 'SIGTERM' || result.error?.code === 'ETIMEDOUT'

  return {
    exitCode: timedOut ? -1 : (result.status ?? -1),
    stdout: truncate(result.stdout ?? ''),
    stderr: truncate(result.stderr ?? ''),
    durationMs,
    timedOut,
  }
}

/**
 * Compte les issues de tâches dans la sortie d'un build.
 *
 * CE N'EST PAS UN VERDICT. C'est une mesure de ce que la commande a
 * réellement fait, destinée au registre. Un `exitCode: 0` accompagné de
 * `executed: 0` signale un succès qui ne porte sur rien — information que
 * le code de sortie seul ne peut pas donner.
 *
 * Le comptage est volontairement grossier : il répond à « a-t-on exécuté quelque
 * chose », pas à « quelles tâches exactement ».
 *
 * DEUX GRAMMAIRES DE SORTIE (correctif du 2026-08-19, F16)
 * --------------------------------------------------------
 * La première version ne reconnaissait que Gradle (`> Task :nom UP-TO-DATE`).
 * Appliquée à Nx, elle rendait systématiquement `executed: 0`, y compris sur un
 * build qui venait de compiler pendant treize secondes et de rattraper une erreur
 * TypeScript. L'avertissement « ce succès ne valide rien » se déclenchait donc à
 * chaque run front — sur un verdict parfaitement fondé.
 *
 * C'est le défaut symétrique de F10 : là un succès vide passait pour plein, ici un
 * succès plein passait pour vide. Les deux viennent de la même cause — tirer une
 * conclusion d'une sortie dont on n'a pas vérifié la forme.
 *
 * **Un avertissement qui se déclenche toujours ne s'entend plus.** Le laisser aurait
 * rendu inutile la garde anti-cache qu'il existe précisément pour porter.
 *
 * TROISIÈME VERSION — CORRECTIF DE F16 (v2 était pire que v1)
 * ------------------------------------------------------------
 * v1 : ne connaissait que Gradle. Sur Nx, rendait toujours `executed: 0`.
 *      L'avertissement « ce succès ne valide rien » se déclenchait sur tout run front.
 *
 * v2 (F16) : a ajouté la branche Nx, mais cherchait le marqueur de cache sur la
 *      ligne SUIVANTE (`lines[i + 1]`). C'est faux : la sortie réelle vérifiée :
 *
 *        > nx run client:lint  [existing outputs match the cache, left as is]
 *
 *      Le marqueur est sur la MÊME ligne. Conséquence : toute tâche Nx était comptée
 *      en `executed`, même entièrement servie par le cache. Le registre a affiché
 *      `executed: 175` sur quatre runs consécutifs, dont un de 13 secondes et un de
 *      12 minutes — un chiffre identique sur des durées d'un facteur 60.
 *      v2 était pire que v1 : v1 se taisait, v2 affirmait.
 *
 * v3 (cette version) :
 *   - Détecte le marqueur de cache sur la MÊME ligne que `> nx run `.
 *   - Pas de repli sur la ligne suivante : aucun format de ce type n'a été
 *     observé, et ajouter du code pour un cas non constaté est exactement le
 *     raisonnement qui a produit v2.
 *   - Ajoute une vérification croisée par la ligne de synthèse Nx (cache) :
 *     « Nx read the output from the cache instead of running the command
 *     for N out of M tasks. »
 *     Présente uniquement quand ≥1 tâche est servie par le cache. Absente
 *     sur un run entièrement frais (vérifié sur sortie réelle).
 *   - Ajoute une vérification croisée par la ligne de succès Nx (A9/F32) :
 *     «  NX   Successfully ran target <target> for N projects »
 *     Présente sur TOUS les runs Nx (frais et cachés). Donne le total de
 *     projets et permet à countMismatch de fonctionner même sans cache.
 *     Vérifié sur sortie réelle (pnpm nx reset puis run-many type-check).
 *   - `countMismatch` vaut `false` quand AUCUNE ligne de synthèse n'est
 *     présente (Gradle, sortie vide) : une absence n'est pas un désaccord.
 *   - `summaryAbsenceReason` explique pourquoi summaryFound est false quand
 *     c'est le cas : 'no-nx-tasks' (aucune ligne `> nx run` détectée),
 *     'fresh-run' (tâches détectées mais aucun cache, pas de ligne cache),
 *     ou null quand summaryFound est true.
 *
 * Requiert une sortie non colorée côté Gradle (`--console=plain`). Nx colore ses
 * lignes `> nx run` : le préfixe est donc recherché après décolorisation ANSI.
 *
 * @param {string} output  stdout + stderr concaténés.
 * @returns {{
 *   upToDate: number,
 *   fromCache: number,
 *   skipped: number,
 *   executed: number,
 *   summaryFound: boolean,
 *   summaryAbsenceReason: 'no-nx-tasks'|'fresh-run'|null,
 *   summaryFromCache: number|null,
 *   summaryTotal: number|null,
 *   countMismatch: boolean,
 * }}
 */
export function countTaskOutcomes(output) {
  // Nx colore sa sortie même redirigée. Les séquences ANSI sont retirées avant
  // analyse pour que les préfixes de ligne soient reconnaissables.
  // eslint-disable-next-line no-control-regex
  const plain = output.replace(/\u001b\[[0-9;]*m/g, '')
  const lines = plain.split('\n')

  let upToDate = 0
  let fromCache = 0
  let skipped = 0
  let executed = 0
  let nxTaskLines = 0  // count of '> nx run' lines seen

  // Champs de la vérification croisée par la ligne de synthèse cache Nx.
  // Présente uniquement quand ≥1 tâche est servie par le cache.
  let cacheSummaryFound = false
  let cacheSummaryFromCache = null
  let cacheSummaryTotal = null

  // Champs de la vérification croisée par la ligne de succès Nx (A9/F32).
  // « NX   Successfully ran target <target> for N projects »
  // Présente sur tous les runs Nx (frais et cachés).
  let successSummaryFound = false
  let successSummaryTotal = null

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]

    // --- Gradle : `> Task :chemin:nom [MARQUEUR]`
    if (line.startsWith('> Task ')) {
      if (line.includes('UP-TO-DATE')) upToDate++
      else if (line.includes('FROM-CACHE')) fromCache++
      else if (line.includes('SKIPPED') || line.includes('NO-SOURCE')) skipped++
      else executed++
      continue
    }

    // --- Nx : `> nx run <projet>:<cible>  [existing outputs match the cache, left as is]`
    //
    // Le marqueur de cache est sur la MÊME ligne que `> nx run `, pas sur la suivante.
    // Sortie réelle vérifiée (pnpm nx run-many -t lint -p client 2>&1 | cat) :
    //
    //   > nx run client:lint  [existing outputs match the cache, left as is]
    //
    // v2 cherchait `lines[i + 1]` — c'était faux. Pas de repli ici : aucun format
    // « marqueur sur la ligne suivante » n'a été observé, et en ajouter un est
    // exactement ce qui a produit v2.
    if (line.startsWith('> nx run ')) {
      nxTaskLines++
      if (line.includes('existing outputs match the cache')) fromCache++
      else executed++
      continue
    }

    // --- Ligne de synthèse cache Nx ---
    //
    // Format : « Nx read the output from the cache instead of running the command
    //           for N out of M tasks. »
    // Présente uniquement quand au moins une tâche est servie par le cache.
    // ABSENTE sur une exécution entièrement fraîche (vérifié sur sortie réelle
    // après `pnpm nx reset`) — donc son absence ne signifie PAS un désaccord.
    //
    // La regex est tolérante sur les espaces (la décolorisation ANSI peut
    // laisser des artefacts d'espacement dans certaines versions de Nx).
    const cacheSummaryMatch = line.match(
      /Nx\s+read\s+the\s+output\s+from\s+the\s+cache\s+instead\s+of\s+running\s+the\s+command\s+for\s+(\d+)\s+out\s+of\s+(\d+)\s+tasks/
    )
    if (cacheSummaryMatch) {
      cacheSummaryFound = true
      cacheSummaryFromCache = parseInt(cacheSummaryMatch[1], 10)
      cacheSummaryTotal = parseInt(cacheSummaryMatch[2], 10)
      continue
    }

    // --- Ligne de succès Nx (A9/F32) ---
    //
    // Format : «  NX   Successfully ran target <target> for N projects »
    //          (avec deux espaces avant NX, puis trois espaces)
    // Présente sur TOUS les runs Nx, frais ou cachés.
    // Vérifié sur sortie réelle (nx reset + run-many type-check, 4 projets) :
    //
    //   " NX   Successfully ran target type-check for 4 projects"
    //
    // Donne le total de projets — permet à countMismatch de fonctionner même
    // sur un run entièrement frais où la ligne de synthèse cache est absente.
    //
    // ATTENTION : le total est en « projets », pas en « tâches ». Pour
    // run-many à une seule cible, projets == tâches. Pour un run multi-cibles,
    // ce serait faux. L'oracle factory utilise toujours run-many à une seule
    // cible (type-check ou frontend-test) — l'hypothèse est donc safe.
    const successMatch = line.match(
      /NX\s+Successfully\s+ran\s+target\s+\S+\s+for\s+(\d+)\s+projects?/
    )
    if (successMatch) {
      successSummaryFound = true
      successSummaryTotal = parseInt(successMatch[1], 10)
      continue
    }
  }

  // summaryFound = true si l'une ou l'autre des deux lignes de synthèse a été trouvée.
  const summaryFound = cacheSummaryFound || successSummaryFound

  // summaryFromCache et summaryTotal : priorité à la ligne de synthèse cache
  // (plus précise : donne fromCache ET total). Si absente, on utilise la ligne
  // de succès pour le total uniquement (fromCache reste null).
  const summaryFromCache = cacheSummaryFound ? cacheSummaryFromCache : null
  const summaryTotal = cacheSummaryFound
    ? cacheSummaryTotal
    : successSummaryFound
      ? successSummaryTotal
      : null

  // Raison de l'absence de summaryFound, quand applicable.
  // null si summaryFound est true.
  // 'no-nx-tasks' : aucune ligne `> nx run` vue — probablement une sortie Gradle
  //                 ou une sortie vide. L'absence est normale.
  // 'fresh-run'   : des tâches Nx ont été comptées, mais aucune n'était en cache
  //                 et la ligne de succès n'a pas été trouvée non plus. Indique
  //                 un format de sortie Nx inattendu (pas de ligne de succès).
  //                 À surveiller si Nx change son format.
  let summaryAbsenceReason = null
  if (!summaryFound) {
    summaryAbsenceReason = nxTaskLines === 0 ? 'no-nx-tasks' : 'fresh-run'
  }

  // Désaccord entre les mesures indépendantes.
  // Un désaccord signale que le format de sortie a changé — fait à enregistrer.
  //
  // Deux sources possibles de cross-check :
  //   1. Ligne de synthèse cache : fromCache doit correspondre, total doit correspondre.
  //   2. Ligne de succès (total projets) : total doit correspondre.
  // Si les deux sont présentes, les deux doivent être cohérentes.
  // `false` si aucune ligne de synthèse n'est présente : une absence n'est pas un désaccord.
  let countMismatch = false
  const lineTotal = upToDate + fromCache + skipped + executed
  if (cacheSummaryFound) {
    countMismatch = fromCache !== cacheSummaryFromCache || lineTotal !== cacheSummaryTotal
  }
  if (successSummaryFound) {
    countMismatch = countMismatch || lineTotal !== successSummaryTotal
  }

  return {
    upToDate,
    fromCache,
    skipped,
    executed,
    summaryFound,
    summaryAbsenceReason,
    summaryFromCache,
    summaryTotal,
    countMismatch,
  }
}

/*
 * POURQUOI LE SNAPSHOT PORTE DES EMPREINTES ET PAS DES NOMS (incident du 2026-08-19, F15)
 * ---------------------------------------------------------------------------------------
 * Une première version comparait deux LISTES DE CHEMINS. Elle a produit un faux négatif
 * dès la première boucle de correction réussie :
 *
 *   edit-1  modifie case-chat.component.ts        → liste = [case-chat.component.ts]
 *   verify-1 échoue (erreur TypeScript réelle)
 *   edit-2  RE-modifie le MÊME fichier            → liste = [case-chat.component.ts]
 *
 * Les deux listes sont identiques, donc « aucun changement », donc la garde
 * `wroteNothing` se déclenche — sur un correctif parfaitement valide. Le run a échoué
 * en annonçant que l'agent n'avait rien fait, alors qu'il venait de réussir.
 *
 * C'est le miroir exact de F10 : là, `exitCode === 0` était vrai et vide ; ici,
 * « la liste n'a pas changé » est vrai et vide. La mesure était juste et ne mesurait
 * pas ce qu'on croyait.
 *
 * PREMIER CORRECTIF, INSUFFISANT (même journée)
 * ----------------------------------------------
 * L'empreinte a d'abord été les compteurs de `--numstat` : `"<ajouts>,<suppressions>"`.
 * Le run suivant a produit le MÊME faux négatif, parce que la correction était un
 * remplacement d'UNE ligne par UNE ligne :
 *
 *   event as Record<string, unknown>
 *   event as unknown as Record<string, unknown>
 *
 * Un ajout, une suppression, avant comme après. Les compteurs sont rigoureusement
 * identiques alors que le contenu a changé. Or **le remplacement à nombre de lignes
 * constant est la forme la plus courante d'un correctif de compilation** : un cast,
 * un type, un nom de symbole. Le cas le plus fréquent était le seul non couvert.
 *
 * Leçon : compter des lignes n'est pas lire un contenu. Seul un condensat du contenu
 * répond à « ce fichier a-t-il changé » sans supposer la forme du changement.
 */

/**
 * Empreinte d'un fichier : condensat SHA-256 de son contenu.
 *
 * Employée pour les fichiers trackés comme non trackés — une seule mesure, aucune
 * hypothèse sur la forme du changement (nombre de lignes, taille, horodatage).
 *
 * Pourquoi pas `git diff --numstat` : ses compteurs sont invariants sur un
 * remplacement ligne pour ligne (voir F15 ci-dessus).
 *
 * Pourquoi pas `size:mtime` : la taille est invariante sur un remplacement de même
 * longueur, et l'horodatage répond à « a-t-on écrit », pas à « le contenu a-t-il
 * changé ». Une réécriture à l'identique compterait comme une modification.
 *
 * Pourquoi pas `git add -N` pour faire apparaître les non trackés dans `--numstat` :
 * cela écrit dans l'index, or l'orchestrateur ne doit pas modifier l'état git qu'il
 * mesure — c'est la raison pour laquelle l'intégration `GIT` est refusée au rôle de
 * phase, et elle vaut pour lui-même.
 *
 * Le coût est une lecture par fichier listé par git, soit quelques unités par phase.
 *
 * @param {string} cwd
 * @param {string} relPath
 * @returns {string}  Condensat hexadécimal, ou une sentinelle si le fichier est illisible.
 */
function contentFingerprint(cwd, relPath) {
  try {
    return createHash('sha256').update(readFileSync(join(cwd, relPath))).digest('hex')
  } catch {
    // Fichier disparu ou illisible entre le listing et la lecture.
    // Sentinelle distincte de tout condensat : une disparition est un changement.
    return 'unreadable'
  }
}

/**
 * Prend un snapshot de l'état Git courant.
 *
 * Chaque entrée porte le CONDENSAT du contenu du fichier, pas seulement son chemin.
 * C'est ce qui permet à [diffSince] de détecter une seconde écriture dans un fichier
 * déjà modifié, y compris à nombre de lignes constant — voir le commentaire F15.
 *
 * Git ne sert plus qu'à établir la LISTE des fichiers à surveiller (modifiés vs non
 * trackés) ; la détection du changement, elle, vient du contenu.
 *
 * @param {string} cwd  Répertoire de travail (racine du dépôt).
 * @returns {{ modified: Map<string, string>, untracked: Map<string, string> }}
 */
export function snapshotDiff(cwd) {
  // `--name-only` suffit désormais : les compteurs de `--numstat` ne servaient qu'à
  // une empreinte qui s'est révélée aveugle aux remplacements ligne pour ligne.
  const diffResult = runCommand('git diff HEAD --name-only', { cwd })
  const untrackedResult = runCommand('git ls-files --others --exclude-standard', { cwd })

  /** @type {Map<string, string>} */
  const modified = new Map()
  for (const path of diffResult.stdout.split('\n').filter(Boolean)) {
    modified.set(path, contentFingerprint(cwd, path))
  }

  /** @type {Map<string, string>} */
  const untracked = new Map()
  for (const path of untrackedResult.stdout.split('\n').filter(Boolean)) {
    untracked.set(path, contentFingerprint(cwd, path))
  }

  return { modified, untracked }
}

/**
 * Retourne les chemins dont le CONTENU a changé depuis un snapshot précédent.
 *
 * Un chemin est retenu s'il est apparu, ou si son condensat diffère. Un fichier déjà
 * modifié avant l'action et réécrit pendant est donc bien détecté — c'est le cas
 * nominal d'une boucle de correction, et celui que les deux versions précédentes
 * rataient.
 *
 * La valeur de retour reste une liste de chemins : les condensats sont un moyen de
 * détection, pas une donnée destinée au registre.
 *
 * @param {{ modified: Map<string, string>, untracked: Map<string, string> }} before
 * @param {string} cwd
 * @returns {{ modified: string[], untracked: string[] }}
 */
export function diffSince(before, cwd) {
  const after = snapshotDiff(cwd)

  /** @type {string[]} */
  const modified = []
  for (const [path, fingerprint] of after.modified) {
    if (before.modified.get(path) !== fingerprint) modified.push(path)
  }

  /** @type {string[]} */
  const untracked = []
  for (const [path, fingerprint] of after.untracked) {
    if (before.untracked.get(path) !== fingerprint) untracked.push(path)
  }

  return { modified, untracked }
}
