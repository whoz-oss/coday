/**
 * Parsing et comparaison de plans d'analyste.
 *
 * Ce module porte les fonctions utilisées par us-loop et testées
 * par test-us-loop.mjs. Pas d'appel réseau, pas d'état mutable.
 * Une seule fonction touche le système de fichiers en lecture seule :
 * `checkPlanFiles` via `existsSync`.
 *
 * ## Ce qu'est un plan
 *
 * L'analyste rend un objet JSON dans sa réponse markdown. Forme minimale :
 *
 *   {
 *     "files": ["chemin/relatif/un.ts", "chemin/relatif/deux.ts"],
 *     "doneWhen": "critère de terminaison vérifiable",
 *     "steps": ["étape 1", "étape 2"]   // optionnel
 *   }
 *
 * - files    : chemins RELATIFS à la racine du dépôt, fichiers à modifier.
 *              Requis, non vide. Chemin absolu ou contenant `..` → rejeté.
 * - doneWhen : requis, non vide.
 * - steps    : optionnel.
 *
 * ## Limite assumée du plan-gate
 *
 * Le plan-gate vérifie que chaque chemin de `plan.files` EXISTE déjà sur disque.
 * Un plan qui doit créer un fichier ne passe pas ce gate : `existsSync` retourne
 * false sur un chemin inexistant, même intentionnellement.
 *
 * Extension possible sans affaiblir le gate : un champ `newFiles` distinct dont
 * on vérifierait que le répertoire PARENT existe (`dirname(path)`). Cela couvrirait
 * la création de fichiers dans des dossiers existants, sans ouvrir la porte à des
 * chemins arbitraires. Non implémenté ici pour rester dans le périmètre minimal.
 */

import { existsSync } from 'node:fs'
import { join, isAbsolute } from 'node:path'

// --------------------------------------------------------------------------
// Parsing
// --------------------------------------------------------------------------

/**
 * Extrait le premier bloc JSON d'un texte markdown.
 *
 * Stratégie d'extraction en trois passes :
 *   1. Bloc ```json ... ``` (fence avec langage)
 *   2. Bloc ``` ... ``` (fence sans langage)
 *   3. Première accolade équilibrée dans le texte brut
 *
 * @param {string} text
 * @returns {string|null}  Le fragment JSON brut, ou null si rien trouvé.
 */
export function extractJsonFragment(text) {
  // Passe 1 : bloc ```json
  const jsonFenceMatch = text.match(/```json\s*([\s\S]*?)```/)
  if (jsonFenceMatch) return jsonFenceMatch[1].trim()

  // Passe 2 : bloc ``` sans langage
  const plainFenceMatch = text.match(/```\s*([\s\S]*?)```/)
  if (plainFenceMatch) return plainFenceMatch[1].trim()

  // Passe 3 : première accolade équilibrée
  const start = text.indexOf('{')
  if (start < 0) return null

  let depth = 0
  for (let i = start; i < text.length; i++) {
    if (text[i] === '{') depth++
    else if (text[i] === '}') {
      depth--
      if (depth === 0) return text.slice(start, i + 1)
    }
  }
  return null
}

/**
 * Valide qu'un chemin de fichier est sûr pour le plan-gate.
 *
 * Un chemin est rejeté s'il est absolu ou s'il contient `..`.
 * Un plan ne sort pas du dépôt.
 *
 * @param {string} p
 * @returns {boolean}
 */
export function isSafePath(p) {
  if (typeof p !== 'string') return false
  if (isAbsolute(p)) return false
  if (p.split('/').includes('..')) return false
  return true
}

/**
 * Parse et valide un plan à partir du texte brut de la réponse de l'analyste.
 *
 * @param {string} agentMessage  Texte brut de la réponse (markdown).
 * @returns {{ ok: true, plan: Plan } | { ok: false, error: string }}
 *
 * @typedef {{
 *   files: string[],
 *   doneWhen: string,
 *   steps?: string[],
 * }} Plan
 */
export function parsePlan(agentMessage) {
  const fragment = extractJsonFragment(agentMessage)
  if (!fragment) {
    return { ok: false, error: 'Aucun bloc JSON trouvé dans la réponse de l\'analyste.' }
  }

  let raw
  try {
    raw = JSON.parse(fragment)
  } catch (err) {
    return { ok: false, error: `JSON invalide : ${err.message}` }
  }

  if (!Array.isArray(raw.files) || raw.files.length === 0) {
    return { ok: false, error: 'Le plan doit contenir un champ "files" non vide.' }
  }

  for (const f of raw.files) {
    if (!isSafePath(f)) {
      return {
        ok: false,
        error:
          `Chemin invalide dans "files" : "${f}". ` +
          'Les chemins doivent être relatifs à la racine du dépôt (pas de chemin absolu, pas de "..")',
      }
    }
  }

  if (typeof raw.doneWhen !== 'string' || raw.doneWhen.trim() === '') {
    return { ok: false, error: 'Le plan doit contenir un champ "doneWhen" non vide.' }
  }

  return {
    ok: true,
    plan: {
      files: raw.files,
      doneWhen: raw.doneWhen,
      ...(Array.isArray(raw.steps) ? { steps: raw.steps } : {}),
    },
  }
}

// --------------------------------------------------------------------------
// Plan-gate : vérification d'existence des fichiers
// --------------------------------------------------------------------------

/**
 * Vérifie que chaque fichier déclaré dans le plan existe sur disque.
 *
 * Limite assumée : ce gate ne couvre pas la création de fichiers neufs.
 * Un plan qui doit créer un fichier ne passe pas ce gate, car `existsSync`
 * retourne false sur un chemin inexistant même si c'est intentionnel.
 * Voir la note en tête de module pour l'extension possible via `newFiles`.
 *
 * @param {string[]} files     Chemins relatifs issus de plan.files.
 * @param {string}   repoRoot  Racine absolue du dépôt.
 * @returns {{
 *   plannedFiles: string[],
 *   missingFiles: string[],
 *   fileCount: number,
 * }}
 */
export function checkPlanFiles(files, repoRoot) {
  const missingFiles = files.filter((f) => !existsSync(join(repoRoot, f)))
  return {
    plannedFiles: files,
    missingFiles,
    fileCount: files.length,
  }
}

// --------------------------------------------------------------------------
// Claims-gate : comparaison plan vs réalité
// --------------------------------------------------------------------------

/**
 * Compare les fichiers réellement modifiés aux fichiers annoncés par le plan.
 *
 * UN ÉCART NE FAIT PAS ÉCHOUER LE RUN — c'est un fait à porter, pas une faute.
 * Un éditeur peut légitimement devoir toucher un fichier voisin non annoncé.
 * L'écart doit être visible dans le registre.
 *
 * @param {string[]} plannedFiles   Chemins relatifs issus de plan.files.
 * @param {string[]} actualModified Chemins relatifs réellement modifiés (diffSince.modified).
 * @param {string[]} actualUntracked Chemins relatifs réellement créés (diffSince.untracked).
 * @returns {{
 *   plannedFiles: string[],
 *   actualFiles: string[],
 *   unplannedFiles: string[],
 *   untouchedPlannedFiles: string[],
 *   claimsMatch: boolean,
 * }}
 */
export function compareClaims(plannedFiles, actualModified, actualUntracked) {
  const actualFiles = [...actualModified, ...actualUntracked]
  const plannedSet = new Set(plannedFiles)
  const actualSet = new Set(actualFiles)

  const unplannedFiles = actualFiles.filter((f) => !plannedSet.has(f))
  const untouchedPlannedFiles = plannedFiles.filter((f) => !actualSet.has(f))
  const claimsMatch = unplannedFiles.length === 0 && untouchedPlannedFiles.length === 0

  return {
    plannedFiles,
    actualFiles,
    unplannedFiles,
    untouchedPlannedFiles,
    claimsMatch,
  }
}
