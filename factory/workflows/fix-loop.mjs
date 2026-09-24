/**
 * Workflow fix-loop — la boucle acteur/oracle.
 *
 * C'EST LE PREMIER WORKFLOW OÙ L'INVARIANT OPÈRE RÉELLEMENT.
 * Un agent modifie le code ; l'orchestrateur — et lui seul — lance la commande de
 * vérité et rend le verdict. L'agent ne choisit pas la commande, ne la lance pas, et
 * ne rapporte pas son résultat. Il ne voit que le message d'erreur du compilateur.
 *
 * ## Séquence
 *
 *   préflight du rôle (existence, activation, subAgents vide)
 *     ↓
 *   ┌─ tentative N ───────────────────────────────────────────────────────┐
 *   │ phase agent : case NEUF + brief → l'agent modifie                 │
 *   │ phase code  : snapshot de diff + commande de vérité → verdict     │
 *   └───────────────────────────────────────────────────┘
 *     ↓ échec → tentative N+1 (budget MAX_ATTEMPTS)
 *
 * ## UN CASE NEUF PAR TENTATIVE (décision, 2026-08-19)
 *
 * Réutiliser le case IDLE de la tentative précédente serait plus rapide et moins
 * coûteux en tokens. On ne le fait pas : le contexte conservé contiendrait le RÉCIT
 * de l'agent sur ce qu'il croit avoir fait au tour précédent — exactement ce que cet
 * orchestrateur existe pour ne pas faire circuler.
 *
 * Un agent qui a écrit « j'ai corrigé l'import manquant » relit cette affirmation au
 * tour suivant et raisonne dessus comme sur un fait acquis. Le message d'erreur du
 * compilateur entre alors en concurrence avec sa propre narration, et la narration
 * a l'avantage de la cohérence.
 *
 * Avec un case neuf, l'agent reçoit l'état du dossier et l'erreur brute. Rien d'autre.
 *
 * Effet de bord utile : un case neuf est trivialement quiescent, donc la garde
 * `case_busy` de `runAgentTurn` ne peut pas se déclencher.
 *
 * ## Variables d'environnement
 *
 *   FACTORY_NAMESPACE_ID  — namespace AgentOS (requis)
 *   FACTORY_AGENT         — nom du rôle de phase (requis)
 *   FACTORY_DOMAIN        — `back` ou `front` (défaut : `front`)
 *   FACTORY_TASK          — la tâche à accomplir (requis)
 *   FACTORY_SCOPE         — périmètre autorisé, texte libre (optionnel)
 */

import { createRun, startPhase, passPhase, failPhase, endRun } from '../lib/registry.mjs'
import { runCommand, snapshotDiff, diffSince, countTaskOutcomes } from '../lib/oracle.mjs'
import { createCase, runAgentTurn, preflightAgent, preflightWorkspace } from '../lib/agentos.mjs'
import { domains } from '../lib/domains.mjs'
import { buildOracleCommand } from '../lib/oracle-command.mjs'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))

/**
 * Racine du dépôt cible : là où l'agent écrit et où l'oracle compile.
 *
 * Par défaut : le dépôt qui contient ce script.
 * Surcharge via `FACTORY_ROOT` pour cibler un checkout différent.
 *
 * Doit être cohérent avec le `rootPath` de l'intégration FACTORY_FILES
 * de l'agent : `preflightWorkspace` vérifie l'égalité exacte.
 */
const REPO_ROOT = process.env.FACTORY_ROOT
  ? resolve(process.env.FACTORY_ROOT)
  : join(__dirname, '..', '..')

/**
 * Budget de boucles de correction.
 * Sans budget, un agent qui n'y arrive pas n'y arrive pas indéfiniment.
 */
const MAX_ATTEMPTS = 3

/** Lignes d'erreur transmises à l'agent au tour suivant. */
const ERROR_LINES_FOR_AGENT = 60

/** Lignes conservées dans le registre en cas d'échec (diagnostic humain). */
const TAIL_LINES = 40

/** Budget de démarrage d'un tour d'agent. */
const START_TIMEOUT_MS = 30 * 1000

/** Budget de travail d'un tour d'agent. */
const WORK_TIMEOUT_MS = 15 * 60 * 1000

/** Budget d'une commande de vérité. */
const ORACLE_TIMEOUT_MS = 20 * 60 * 1000

/**
 * Retourne les N dernières lignes non vides d'une chaîne.
 *
 * @param {string} text
 * @param {number} n
 * @returns {string[]}
 */
function tailLines(text, n) {
  return text
    .split('\n')
    .filter((line) => line.trim().length > 0)
    .slice(-n)
}

/**
 * Construit le brief de la première tentative.
 *
 * Le gabarit est rendu par LE CODE, jamais par un modèle. Il porte quatre choses :
 * la tâche, le périmètre, le critère de terminaison, et la sortie honorable.
 *
 * La sortie honorable n'est pas une politesse. Un agent contraint sans issue de
 * sortie ne s'arrête pas : il bricole dans le périmètre autorisé et produit quelque
 * chose de plausible et de faux. Lui dire que « ce n'est pas le bon endroit » est un
 * résultat acceptable est ce qui rend ce rapport possible.
 *
 * L'agent n'est PAS informé de la commande de vérité. C'est délibéré : un acteur qui
 * connaît son oracle optimise pour l'oracle.
 *
 * @param {string} task
 * @param {string|null} scope
 * @returns {string}
 */
function buildInitialBrief(task, scope) {
  const sections = [`## Task\n${task}`]

  if (scope) {
    sections.push(`## Scope\n${scope}`)
  }

  sections.push(
    '## Done when\n' +
      'The change is written to disk. Do not attempt to build, compile, lint or test — ' +
      'verification is performed independently and is not your responsibility.'
  )

  sections.push(
    '## If this is not the right place\n' +
      'If the work required is outside the scope above, or if you determine the real ' +
      'problem lies elsewhere, say so explicitly and stop. Reporting "this is not the ' +
      'right place to fix it" is a successful outcome, not a failure.'
  )

  return sections.join('\n\n')
}

/**
 * Construit le brief d'une tentative de correction.
 *
 * Contient la tâche d'origine et la sortie brute du compilateur. AUCUN commentaire
 * de l'orchestrateur sur ce qui a échoué, AUCUN récit du tour précédent : le case
 * est neuf, l'agent découvre l'état du disque et l'erreur, et rien de plus.
 *
 * @param {string} task
 * @param {string|null} scope
 * @param {string[]} errorLines
 * @param {number} attempt
 * @returns {string}
 */
function buildFixBrief(task, scope, errorLines, attempt) {
  const sections = [
    `## Task\n${task}`,
    '## Current state\n' +
      `A previous attempt (#${attempt - 1}) left changes on disk that do not compile. ` +
      'Read the current state of the files before changing anything — do not assume ' +
      'what was done.',
    `## Compiler output\n\`\`\`\n${errorLines.join('\n')}\n\`\`\``,
  ]

  if (scope) {
    sections.push(`## Scope\n${scope}`)
  }

  sections.push(
    '## Done when\n' +
      'The change is written to disk. Do not attempt to build, compile, lint or test — ' +
      'verification is performed independently and is not your responsibility.'
  )

  sections.push(
    '## If this is not the right place\n' +
      'If the error indicates the real problem lies outside the scope above, say so ' +
      'explicitly and stop. Reporting that is a successful outcome, not a failure.'
  )

  return sections.join('\n\n')
}

/**
 * Point d'entrée du workflow fix-loop.
 *
 * @param {object} log  Logger fourni par `run.mjs`.
 */
export async function run(log) {
  const namespaceId = process.env.FACTORY_NAMESPACE_ID
  const agentName = process.env.FACTORY_AGENT
  const task = process.env.FACTORY_TASK
  const scope = process.env.FACTORY_SCOPE ?? null
  const domainName = process.env.FACTORY_DOMAIN ?? 'front'

  const missing = []
  if (!namespaceId) missing.push('FACTORY_NAMESPACE_ID')
  if (!agentName) missing.push('FACTORY_AGENT')
  if (!task) missing.push('FACTORY_TASK')
  if (missing.length > 0) {
    throw new Error(
      `Variables d'environnement manquantes : ${missing.join(', ')}\n` +
        `  FACTORY_NAMESPACE_ID : ID du namespace AgentOS\n` +
        `  FACTORY_AGENT        : nom du rôle de phase\n` +
        `  FACTORY_TASK         : la tâche à accomplir\n` +
        `  FACTORY_SCOPE        : (optionnel) périmètre autorisé\n` +
        `  FACTORY_DOMAIN       : (optionnel) back | front, défaut front`
    )
  }

  const domain = domains[domainName]
  if (!domain) {
    throw new Error(
      `Domaine inconnu : "${domainName}". Valeurs acceptées : ${Object.keys(domains).join(', ')}`
    )
  }

  const theRun = createRun('fix-loop', { namespaceId })
  let allPass = false

  // -------------------------------------------------------------------------
  // Phase 0 : préflight du rôle (code)
  // -------------------------------------------------------------------------
  {
    const phase = startPhase(theRun, 'preflight', 'code')
    log.phaseStart('preflight', 'code')

    const check = await preflightAgent(namespaceId, agentName)

    if (!check.ok) {
      failPhase(phase, { agentName, reason: check.reason })
      log.phaseEnd('preflight', 'fail', { agentName })
      log.error(check.reason)
      endRun(theRun, 'fail')
      return { allPass: false, filePath: theRun.filePath }
    }

    // Colocalisation : l'agent doit écrire dans l'arbre que l'oracle va compiler.
    // Sans cette vérification, un run peut faire modifier un dépôt et en compiler un
    // autre — verdict vert sur un travail invisible (incident F13).
    const workspace = await preflightWorkspace(namespaceId, check.agent, REPO_ROOT)

    if (!workspace.ok) {
      failPhase(phase, {
        agentName,
        repoRoot: REPO_ROOT,
        rootPath: workspace.rootPath,
        reason: workspace.reason,
      })
      log.phaseEnd('preflight', 'fail', { agentName, rootPath: workspace.rootPath })
      log.error(workspace.reason)
      endRun(theRun, 'fail')
      return { allPass: false, filePath: theRun.filePath }
    }

    passPhase(phase, {
      agentName,
      subAgents: check.agent.subAgents ?? [],
      domain: domainName,
      rootPath: workspace.rootPath,
    })
    log.phaseEnd('preflight', 'pass', { agentName, domain: domainName })
  }

  // -------------------------------------------------------------------------
  // Boucle de tentatives
  // -------------------------------------------------------------------------
  let errorLines = null

  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    // -----------------------------------------------------------------------
    // Phase agent : l'acteur modifie
    // -----------------------------------------------------------------------
    const agentPhaseName = `edit-${attempt}`
    const agentPhase = startPhase(theRun, agentPhaseName, 'agent')
    log.phaseStart(agentPhaseName, 'agent')

    const brief =
      attempt === 1
        ? buildInitialBrief(task, scope)
        : buildFixBrief(task, scope, errorLines, attempt)

    const beforeAgent = snapshotDiff(REPO_ROOT)

    // Case NEUF — voir l'en-tête de ce fichier.
    let caseId
    try {
      const newCase = await createCase(namespaceId, `factory/fix-loop — attempt ${attempt}`)
      caseId = newCase.id
    } catch (err) {
      failPhase(agentPhase, { attempt, agentStatus: 'error', error: String(err) })
      log.phaseEnd(agentPhaseName, 'fail', { error: String(err) })
      endRun(theRun, 'fail')
      return { allPass: false, filePath: theRun.filePath }
    }

    const turn = await runAgentTurn(caseId, agentName, brief, {
      startTimeoutMs: START_TIMEOUT_MS,
      workTimeoutMs: WORK_TIMEOUT_MS,
    })

    const agentChanged = diffSince(beforeAgent, REPO_ROOT)

    // Contrôle a posteriori de la substitution d'agent. Le préflight a vérifié la
    // configuration ; ceci vérifie qui a réellement travaillé. `selectAgent` bascule
    // silencieusement sur l'agent par défaut quand une @mention ne résout pas.
    const wrongAgent =
      turn.agentsSelected.length > 0 && !turn.agentsSelected.includes(agentName)

    const agentFacts = {
      attempt,
      caseId,
      agentStatus: turn.status,
      caseStatus: turn.caseStatus,
      agentsSelected: turn.agentsSelected,
      agentTurns: turn.agentTurns,
      toolCallCount: turn.toolCallCount,
      failedToolCalls: turn.failedToolCalls,
      killedByBudget: turn.killedByBudget,
      anchored: turn.anchored,
      llmModels: turn.llmModels,
      filesModified: agentChanged.modified,
      filesUntracked: agentChanged.untracked,
      // NB : le texte produit par l'agent n'est PAS enregistré (invariant registre)
    }

    if (wrongAgent) {
      failPhase(agentPhase, { ...agentFacts, expectedAgent: agentName })
      log.phaseEnd(agentPhaseName, 'fail', { agentsSelected: turn.agentsSelected })
      log.error(
        `L'agent "${agentName}" n'a pas traité ce case. ` +
          `Agents sélectionnés : ${turn.agentsSelected.join(', ')}. ` +
          `Substitution silencieuse côté AgentOS — le verdict porterait sur le mauvais travail.`
      )
      endRun(theRun, 'fail')
      return { allPass: false, filePath: theRun.filePath }
    }

    if (turn.status !== 'finished') {
      failPhase(agentPhase, agentFacts)
      log.phaseEnd(agentPhaseName, 'fail', { agentStatus: turn.status })
      log.error(`Tour d'agent non abouti : ${turn.status}`)
      endRun(theRun, 'fail')
      return { allPass: false, filePath: theRun.filePath }
    }

    // Un agent qui termine sans avoir rien écrit n'a pas fait le travail. Ce n'est
    // pas nécessairement une faute — c'est peut-être la sortie honorable — mais dans
    // les deux cas relancer l'oracle serait absurde : il rendrait le verdict de la
    // tentative précédente, ou celui de l'arbre inchangé.
    const wroteNothing =
      agentChanged.modified.length === 0 && agentChanged.untracked.length === 0

    if (wroteNothing) {
      failPhase(agentPhase, { ...agentFacts, wroteNothing: true })
      log.phaseEnd(agentPhaseName, 'fail', { wroteNothing: true })
      log.error(
        "L'agent a terminé sans modifier aucun fichier. " +
          'Soit la tâche est hors périmètre (sortie honorable), soit elle a été mal comprise. ' +
          `Consulter le case ${caseId} dans AgentOS pour lire sa réponse.`
      )
      endRun(theRun, 'fail')
      return { allPass: false, filePath: theRun.filePath }
    }

    passPhase(agentPhase, agentFacts)
    log.phaseEnd(agentPhaseName, 'pass', {
      agentTurns: turn.agentTurns,
      toolCallCount: turn.toolCallCount,
      filesModified: agentChanged.modified.length,
      filesUntracked: agentChanged.untracked.length,
    })

    // -----------------------------------------------------------------------
    // Phase(s) code : les oracles rendent le verdict, dans l'ordre de la liste.
    //
    // Chaque oracle produit sa propre phase dans le registre. Au premier échec,
    // on s'arrête : les oracles suivants ne tournent pas, leurs phases n'existent
    // pas dans le registre (une phase non exécutée n'est ni pass ni fail).
    //
    // La vérification ne passe que si TOUS les oracles passent.
    // -----------------------------------------------------------------------
    let oraclePass = true
    let oracleErrorLines = null

    for (const oracle of domain.oracles) {
      const oraclePhaseName = `verify-${oracle.name}-${attempt}`
      const oraclePhase = startPhase(theRun, oraclePhaseName, 'code')
      log.phaseStart(oraclePhaseName, 'code')

      // Construction de la commande effective.
      //
      // Si l'oracle porte `filesArg: true`, on injecte `--files=<liste>` à
      // partir des fichiers modifiés par l'agent lors de ce tour
      // (`agentChanged.modified`). Cette liste est stable pendant toute la
      // boucle des oracles : elle a été calculée par `diffSince(beforeAgent,
      // REPO_ROOT)` avant d'entrer dans la boucle, et aucun oracle ne modifie
      // l'arbre (vérifié par le snapshot `beforeOracle`/`oracleChanged`).
      //
      // Un oracle sans `filesArg` (comme `types`) reçoit sa commande telle
      // quelle — périmètre fixe, indépendant du diff.
      //
      // C'est la commande EFFECTIVE (avec `--files`) qui est enregistrée dans
      // le registre, pas la commande template. Le registre doit dire ce qui a
      // réellement tourné.
      const effectiveCommand = buildOracleCommand(oracle, agentChanged.modified, REPO_ROOT)

      log.info(`Oracle : ${oracle.name}`)
      log.info('Commande : ' + effectiveCommand)
      log.info('Répertoire : ' + oracle.cwd)
      log.info('La commande peut prendre plusieurs minutes sans sortie intermédiaire.')

      const beforeOracle = snapshotDiff(REPO_ROOT)

      const result = runCommand(effectiveCommand, {
        cwd: oracle.cwd,
        timeoutMs: ORACLE_TIMEOUT_MS,
      })

      const oracleChanged = diffSince(beforeOracle, REPO_ROOT)

      // Le verdict est `exitCode === 0`, et rien d'autre. On ne cherche jamais une
      // chaîne comme "BUILD SUCCESSFUL" dans la sortie : elle peut être tronquée,
      // localisée ou absente selon la version de l'outil.
      const passed = result.exitCode === 0

      // Ce décompte n'est PAS un verdict. Il répond à « sur quoi ce verdict portait-il »,
      // question à laquelle le code de sortie seul ne répond pas.
      const tasks = countTaskOutcomes(result.stdout + '\n' + result.stderr)

      const oracleFacts = {
        attempt,
        oracle: oracle.name,
        command: effectiveCommand,
        domain: domainName,
        exitCode: result.exitCode,
        timedOut: result.timedOut,
        commandDurationMs: result.durationMs,
        tasks,
        filesModified: oracleChanged.modified,
        filesUntracked: oracleChanged.untracked,
      }

      // TIMEOUT DE L'ORACLE — arrêt immédiat, pas de tentative suivante (décision, C2)
      //
      // Un timeout ne dit RIEN sur le travail de l'agent : le code n'a été ni validé
      // ni invalidé. C'est un échec de l'INSTRUMENT (budget trop court, commande trop
      // large), pas un verdict sur le travail.
      //
      // Incident réel : `pnpm nx run-many -t frontend-test` a tourné 20m01s avant
      // d'être tué. L'éditeur avait reçu le brief, n'avait rien trouvé à corriger,
      // n'avait rien écrit, et la garde `wroteNothing` avait fait échouer le run.
      // Un tour d'agent dépensé pour rien.
      //
      // Améliorer le brief serait traiter le symptôme. Relancer l'éditeur sur une
      // sortie tronquée d'une commande interrompue lui demande de corriger un problème
      // qu'on n'a pas mesuré. Un modèle à qui l'on demande de corriger sans lui donner
      // d'erreur produit quelque chose de plausible — travail parasite avec verdict
      // cohérent, la famille de panne la plus coûteuse de ce système.
      //
      // Le bon geste : ne pas relancer. Enregistrer le fait, logger un message explicite
      // qui dit que ce n'est PAS un verdict sur le travail de l'agent, et terminer.
      if (result.timedOut) {
        failPhase(oraclePhase, oracleFacts)
        log.phaseEnd(oraclePhaseName, 'fail', {
          oracle: oracle.name,
          exitCode: result.exitCode,
          timedOut: true,
          commandDurationMs: result.durationMs,
        })
        log.error(
          `Timeout de l'oracle [${oracle.name}] : la vérification n'a pas abouti en ${ORACLE_TIMEOUT_MS / 1000}s ` +
            `(durée réelle : ${Math.round(result.durationMs / 1000)}s). ` +
            `Ce n'est PAS un verdict sur le travail de l'agent — le code n'a été ni validé ` +
            `ni invalidé. C'est le budget ou le périmètre de la commande qu'il faut réévaluer ` +
            `(ORACLE_TIMEOUT_MS ou domain.oracles).`
        )
        endRun(theRun, 'fail')
        return { allPass: false, filePath: theRun.filePath }
      }

      // SUCCÈS VIDE (executed === 0) — arrêt immédiat, pas de tentative suivante
      // (correctif A8, incident du 2026-08-20)
      //
      // Quand `exitCode === 0` mais `tasks.executed === 0`, l'oracle n'a rien
      // exécuté : tout a été servi par le cache. Le verdict est vrai et VIDE —
      // il ne dit ni que le code est correct, ni qu'il est incorrect.
      //
      // C'est un échec de l'INSTRUMENT, pas un verdict sur le travail de l'agent.
      // La phase est donc enregistrée en `failPhase` avec le fait `emptySuccess: true`.
      //
      // POURQUOI NE PAS RELANCER L'ÉDITEUR :
      // Relancer l'éditeur sur cette non-information lui demanderait de corriger un
      // problème qu'on n'a pas mesuré. Un modèle à qui l'on demande de corriger sans
      // lui donner d'erreur produit quelque chose de plausible — travail parasite
      // avec verdict cohérent, la famille de panne la plus coûteuse de ce système.
      //
      // Incident réel sur ce schéma avec un timeout : l'éditeur avait reçu une sortie
      // tronquée sous un titre `## Compiler output`, n'avait rien trouvé à corriger,
      // n'avait rien écrit, et la garde `wroteNothing` avait fait échouer le run.
      if (passed && tasks.executed === 0) {
        failPhase(oraclePhase, { ...oracleFacts, emptySuccess: true })
        log.phaseEnd(oraclePhaseName, 'fail', {
          oracle: oracle.name,
          exitCode: result.exitCode,
          emptySuccess: true,
          commandDurationMs: result.durationMs,
        })
        log.error(
          `Oracle [${oracle.name}] : succès vide — aucune tâche exécutée ` +
            `(up-to-date=${tasks.upToDate}, from-cache=${tasks.fromCache}). ` +
            `Ce n'est PAS un verdict sur le travail de l'agent — le code n'a été ` +
            `ni validé ni invalidé. Causes plausibles : soit les fichiers modifiés ` +
            `ne sont pas dans le graphe d'entrée de cet oracle, soit le cache doit ` +
            `être invalidé (pnpm nx reset dans le dépôt cible).`
        )
        endRun(theRun, 'fail')
        return { allPass: false, filePath: theRun.filePath }
      }

      if (tasks.countMismatch) {
        log.error(
          'ATTENTION : les deux mesures de décompte Nx divergent ' +
            `(ligne à ligne : fromCache=${tasks.fromCache}, ` +
            `synthèse : fromCache=${tasks.summaryFromCache} sur ${tasks.summaryTotal}). ` +
            'Le format de sortie de Nx a probablement changé — ' +
            'le chiffre executed du registre n\'est pas fiable pour ce run.'
        )
      }

      if (passed) {
        passPhase(oraclePhase, oracleFacts)
        log.phaseEnd(oraclePhaseName, 'pass', {
          oracle: oracle.name,
          exitCode: result.exitCode,
          commandDurationMs: result.durationMs,
          tasksExecuted: tasks.executed,
        })
        // Oracle passé, on continue avec le suivant.
        continue
      }

      // Échec : on enregistre, et on prépare le brief de la tentative suivante.
      // Ces extraits viennent d'un compilateur, pas d'un modèle — l'invariant
      // « aucune sortie de LLM dans le registre » n'est pas violé.
      const stderrTail = tailLines(result.stderr, TAIL_LINES)
      const stdoutTail = tailLines(result.stdout, TAIL_LINES)

      failPhase(oraclePhase, { ...oracleFacts, stderrTail, stdoutTail })
      log.phaseEnd(oraclePhaseName, 'fail', {
        oracle: oracle.name,
        exitCode: result.exitCode,
        timedOut: result.timedOut,
        commandDurationMs: result.durationMs,
      })

      // Gradle et Nx écrivent souvent leurs erreurs sur stdout plutôt que stderr.
      const source = result.stderr.trim().length > 0 ? result.stderr : result.stdout
      oracleErrorLines = tailLines(source, ERROR_LINES_FOR_AGENT)

      log.error(
        `Oracle [${oracle.name}] verdict négatif (exitCode=${result.exitCode}) ` +
          `— tentative ${attempt}/${MAX_ATTEMPTS}`
      )
      for (const line of oracleErrorLines.slice(-TAIL_LINES)) {
        log.error(line)
      }

      oraclePass = false
      break // Au premier oracle échoué, on s'arrête : les suivants ne tournent pas.
    } // fin boucle oracles

    if (oraclePass) {
      allPass = true
      log.info(`Boucle terminée après ${attempt} tentative(s).`)
      break
    }

    // Propager les lignes d'erreur pour le brief de la tentative suivante.
    errorLines = oracleErrorLines

    if (attempt === MAX_ATTEMPTS) {
      log.error(`Budget de ${MAX_ATTEMPTS} tentatives épuisé.`)
    }
  }

  endRun(theRun, allPass ? 'pass' : 'fail')
  return { allPass, filePath: theRun.filePath }
}
