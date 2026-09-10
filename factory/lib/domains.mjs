/**
 * Commandes d'oracle par domaine.
 *
 * Ces commandes sont écrites ici, en dur, versionnées dans le dépôt.
 * Aucun agent ne les choisit, ne les lance, ni ne rapporte leur résultat.
 * Le fait qu'elles soient ici et non dans un fichier de config est
 * intentionnel : elles font partie du contrat de l'orchestrateur.
 *
 * DOMAINE FRONT — POURQUOI DEUX ORACLES (incident du 2026-08-20)
 * ───────────────────────────────────────────────────────────────────
 * Un run `us-loop` a rendu un verdict `pass` sur du code qui ne compile pas.
 * La cible Nx `frontend-test` exécute des tests unitaires en transpilation seule
 * (SWC/Babel/`isolatedModules`) : elle supprime les types sans les vérifier.
 * Une erreur TypeScript n'a aucun chemin pour faire échouer un test unitaire.
 * Ce n'est pas un problème de cache — même sans cache, cette cible ne détecterait
 * rien. La commande d'oracle front ne mesurait pas la propriété qu'on croyait
 * mesurer.
 *
 * L'oracle de typage réel dans le dépôt cible est la cible Nx `type-check`,
 * qui fait un vrai `tsc --noEmit`. C'est elle qu'agrège le job CI
 * `frontend-type-check` qui bloque les PRs.
 *
 * POURQUOI DEUX PHASES ET PAS UN `&&` : le typage et le comportement sont des
 * propriétés orthogonales. Un `&&` masquerait le résultat de la seconde dès
 * que la première échoue : on perdrait la mesure d'une propriété à cause de
 * l'autre, et le registre ne porterait qu'un verdict pour deux questions.
 *
 * PÉRIMÈTRE FIXE du type-check : la liste de projets (aphrodite, admin,
 * agentic-studio, copilot-chat) est fixe et insensible à la dérive d'`affected`.
 * Un même `nx affected` a donné 175 projets puis 1033 sur le même dépôt à
 * quelques minutes d'intervalle.
 *
 * ANGLE MORT CONNU (non traité ici, documenté délibérément) : 4 projets du
 * dépôt cible n'ont PAS de cible `type-check` — zapier-client, e2e-dashboard,
 * ml-ops-admin, pso-admin. Une régression de types dans du code partagé qu'ils
 * consomment ne sera pas vue par cet oracle. Un angle mort connu et écrit vaut
 * mieux qu'un angle mort ignoré.
 *
 * VERROUS PAR DOMAINE :
 * L'exclusion lecteurs/écrivain viendra ici quand le parallélisme sera
 * introduit. Pour l'instant l'orchestrateur est séquentiel et aucun verrou
 * n'est implémenté. Ne pas ajouter de logique de lock sans mettre à jour
 * ce commentaire et les tests associés.
 *
 * POURQUOI `--rerun-tasks` ET `--console=plain` (incident du 2026-08-19) :
 * Un premier run a rendu un `pass` en 6 secondes sur un build qui en prend
 * plusieurs minutes. Gradle avait trouvé toutes ses tâches `UP-TO-DATE` et
 * n'avait RIEN recompilé ni rejoué — en retournant `exitCode: 0`, ce qui est
 * légitime de son point de vue. Le verdict était donc vrai et vide.
 *
 * `--rerun-tasks` supprime l'ambiguïté : un oracle qui vérifie une modification
 * doit exécuter, pas consulter un cache. Le coût en temps est le prix d'un
 * verdict qui porte sur quelque chose.
 *
 * `--console=plain` désactive la sortie ANSI pour que le décompte de tâches
 * reste analysable (voir `countTaskOutcomes` dans oracle.mjs). Ce décompte
 * n'est PAS un verdict — le verdict reste `exitCode === 0`. C'est un fait
 * enregistré à côté, qui empêche le registre d'afficher un succès sans dire
 * sur quoi il portait.
 */

import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))

/**
 * Racine du dépôt cible.
 *
 * Par défaut : le dépôt qui contient ce script (un niveau au-dessus de
 * `factory/lib/`). Surcharge possible via `FACTORY_ROOT` pour cibler un
 * checkout différent de celui qui héberge la factory.
 *
 * Exemple :
 *   FACTORY_ROOT=/path/to/version node factory/run.mjs fix-loop
 *
 * Le chemin est résolu en absolu pour éviter toute ambiguïté sur le `cwd`
 * des commandes oracle.
 */
const REPO_ROOT = process.env.FACTORY_ROOT
  ? resolve(process.env.FACTORY_ROOT)
  : join(__dirname, '..', '..')

/**
 * Définition d'un oracle.
 *
 * @typedef {{
 *   name: string,
 *   command: string,
 *   cwd: string,
 * }} Oracle
 */

/**
 * Définition d'un domaine de compilation.
 *
 * Un domaine expose une liste d'oracles nommés. La liste est uniforme pour
 * tous les domaines, même si un domaine n'a qu'un seul oracle — structure
 * uniforme, un seul chemin de code dans les workflows.
 *
 * @typedef {{
 *   oracles: Oracle[],
 *   // lock?: Lock  — viendra ici quand le parallélisme sera introduit
 * }} Domain
 */

/**
 * Commandes oracle par domaine.
 *
 * Surchargeables via variables d'env pour les repos qui n'utilisent pas
 * les commandes par défaut du monorepo cible :
 *
 *   FACTORY_COMMAND_FRONT_BUILD="pnpm nx run-many --target=build --projects=app1,app2 --configuration=development --skip-nx-cache"
 *   FACTORY_COMMAND_FRONT="pnpm nx run PARAMETERS:frontend-test"
 *   FACTORY_COMMAND_BACK="./gradlew :mon-module:build --rerun-tasks"
 *   FACTORY_CWD_BACK="/chemin/vers/sous-dossier"  (optionnel, défaut : REPO_ROOT/agentos)
 *   FACTORY_CWD_FRONT="/chemin/vers/repo"          (optionnel, défaut : REPO_ROOT)
 *
 * Historique des surcharges :
 *   - FACTORY_COMMAND_FRONT surcharge la commande de l'oracle `tests` (comportement
 *     historique — des utilisateurs s'en servent, ne pas le changer).
 *   - FACTORY_COMMAND_FRONT_TYPES était la surcharge de l'oracle `types`, désormais
 *     désactivé (voir commentaire ci-dessous).
 *   - FACTORY_COMMAND_FRONT_BUILD surcharge la commande de l'oracle `build` front.
 *   - FACTORY_CWD_FRONT s'applique aux oracles `build` et `tests`.
 *
 * ORACLE BUILD HOST MAPPING (front domain)
 * ─────────────────────────────────────────
 * L'oracle `build` du domaine front doit exécuter `pnpm nx run-many --target=build`
 * sur des projets qui ont réellement une cible `build` dans leur `project.json`.
 * Les projets propriétaires des fichiers modifiés (libs) n'ont souvent pas de cible
 * `build` angulaire — ils sont consommés par des apps hôtes.
 *
 * La résolution des hôtes buildables se fait via `FACTORY_FRONT_BUILD_HOST_MAP`,
 * une variable d'env JSON au format :
 *   { "<owner-project>": ["<host-app-1>", "<host-app-2>"], "*": ["<fallback-host>"] }
 *
 * La clé `"*"` est un fallback : si un projet propriétaire n'est pas dans la map,
 * les hôtes du fallback sont utilisés. Si la map est absente ou si aucun hôte n'est
 * trouvé, l'oracle retourne un signal ORACLE_INFRASTRUCTURE (gate humain).
 *
 * Exemple pour le dépôt cible biznet.io/app :
 *   FACTORY_FRONT_BUILD_HOST_MAP='{
 *     "*": ["aphrodite", "admin", "agentic-studio", "copilot-chat"]
 *   }'
 *
 * Ce mapping est intentionnellement explicite et versionnée côté Factory :
 * le graphe Nx peut contenir des centaines de projets, la résolution transitive
 * est instable (F21). Un mapping explicite est prévisible et auditeable.
 */

/** @type {Record<string, Domain>} */
export const domains = {
  back: {
    oracles: [
      {
        name: 'build',
        command: process.env.FACTORY_COMMAND_BACK
          ?? './gradlew :agentos-service:build --rerun-tasks --console=plain',
        cwd: process.env.FACTORY_CWD_BACK
          ?? join(REPO_ROOT, 'agentos'),
      },
    ],
    // lock: null  — placeholder pour le verrou lecteurs/écrivain à venir
  },
  front: {
    oracles: [
      {
        // Oracle de build Angular — exécuté EN PREMIER (fail-fast).
        //
        // POURQUOI `build` ET PAS `type-check` (décision du 2026-08-XX)
        // ─────────────────────────────────────────────────────────────────
        // L'oracle `types` (`tsc --noEmit` via la cible Nx `type-check`) échoue
        // systématiquement avec TS5090 (`Non-relative paths are not allowed when
        // baseUrl is not set`) sur plusieurs apps du dépôt cible (admin, agentic-studio,
        // etc.). TS5090 est une erreur de configuration tsconfig, pas une régression
        // produit — mais elle fait échouer l'oracle à chaque run, même sans modification.
        //
        // L'oracle `build` (`@angular/build:application` via `pnpm nx run-many
        // --target=build`) résout les chemins via le bundler esbuild, qui n'émet pas
        // TS5090. Il valide en plus les templates Angular, que `tsc --noEmit` ne vérifie
        // pas. C'est la commande équivalente à ce que CI exécute pour valider les PRs.
        //
        // RÉSOLUTION DES HÔTES BUILDABLES
        // ─────────────────────────────────────────────────────────────────
        // Les libs (projets propriétaires des fichiers modifiés) n'ont pas de cible
        // `build` angulaire. On les mappe vers des apps hôtes via `buildHostArg: true`
        // (voir oracle-command.mjs, `resolveBuildHosts`). Si aucun hôte n'est trouvé,
        // l'oracle retourne ORACLE_INFRASTRUCTURE (gate humain), pas un succès vide.
        //
        // POURQUOI `--configuration=development` ET PAS `production`
        // ─────────────────────────────────────────────────────────────────
        // La configuration `production` active les optimisations (minification, tree
        // shaking, budget de taille), qui augmentent significativement la durée du
        // build. La configuration `development` compile les templates, vérifie les
        // types Angular, et détecte les erreurs d'import — c'est suffisant pour
        // valider une PR. Le coût en temps est acceptable (~2-5 min vs ~10-15 min).
        //
        // `--skip-nx-cache` est obligatoire : le cache Nx est partagé avec
        // l'environnement de dev, et les apps hôtes peuvent avoir été buildées sur
        // cet état. Sans `--skip-nx-cache`, on tomberait dans le piège
        // `executed: 0` (garde A8 bloquante).
        //
        // ANGLE MORT CONNU : le build `development` ne détecte pas les erreurs de
        // budget de taille (production uniquement). Acceptable pour un oracle de
        // validation de PR.
        //
        // ORACLE `types` DÉSACTIVÉ TEMPORAIREMENT
        // ─────────────────────────────────────────────────────────────────
        // L'oracle `types` est commenté ci-dessous. Il sera réactivé quand les
        // tsconfigs des apps cibles auront été réparés (ajout de `baseUrl` dans
        // la chaîne d'extension vers `tsconfig.base.json`). Ne pas le supprimer —
        // il documente l'intention et la raison de la désactivation.
        name: 'build',
        command: process.env.FACTORY_COMMAND_FRONT_BUILD
          ?? 'pnpm nx run-many --target=build --configuration=development --skip-nx-cache',
        cwd: process.env.FACTORY_CWD_FRONT
          ?? REPO_ROOT,
        buildHostArg: true,
      },
      // ORACLE `types` DÉSACTIVÉ — TS5090 systématique dans le dépôt cible.
      // Réactiver quand les tsconfigs seront réparés (baseUrl manquant).
      // {
      //   name: 'types',
      //   command: process.env.FACTORY_COMMAND_FRONT_TYPES
      //     ?? 'pnpm nx run-many --target=type-check --projects=aphrodite,admin,agentic-studio,copilot-chat --parallel=4',
      //   cwd: process.env.FACTORY_CWD_FRONT
      //     ?? REPO_ROOT,
      // },
      {
        // Oracle de comportement — exécuté EN SECOND (seulement si `build` passe).
        //
        // Transpile sans vérifier les types (SWC/Babel/isolatedModules) —
        // c'est pour ça que `build` le précède.
        //
        // FACTORY_COMMAND_FRONT surcharge cette commande (comportement historique).
        // La commande surchargée doit contenir `-t <cible>` ou `--target=<cible>`
        // pour que `buildOracleCommand` puisse extraire la cible et construire
        // la commande `run-many` effective. FACTORY_FRONT_TEST_TARGET configure
        // aussi la cible par défaut (frontend-test) sans remplacer la commande.
        //
        // POURQUOI `filesArg: true` — HISTORIQUE DES INCIDENTS
        // ─────────────────────────────────────────────────────────────────────
        // F21 : `affected --base=sprint` incluait 16 427 commits de divergence
        // → 1033 projets, tous en cache, `executed: 0`.
        //
        // A2 (C4) : remplacement par `affected --files=<liste>`. 1 fichier → 53
        // projets (clôture transitive). Correctif efficace sur le périmètre.
        //
        // Incident suivant : avec `--files=<6 fichiers>`, Nx calcule 216 projets
        // (clôture transitive), tous servis depuis le cache. Cause : le cache Nx
        // est partagé entre la factory et l'environnement de dev. `--skip-nx-cache`
        // testé expérimentalement : ne réduit pas le périmètre (375 projets avec
        // 3 fichiers), le problème de périmètre reste entier.
        //
        // SOLUTION RETENUE : `run-many --projects=<projets directs> --skip-nx-cache`
        // ─────────────────────────────────────────────────────────────────────
        // `filesArg: true` signale à `buildOracleCommand` de résoudre les projets
        // propriétaires des fichiers modifiés (pas leurs dépendants transitifs),
        // puis de construire :
        //   pnpm nx run-many --target=<FACTORY_FRONT_TEST_TARGET> --projects=proj1,proj2 --skip-nx-cache
        //
        // La résolution se fait par remontée de dossiers jusqu'au premier
        // `project.json`, en Node pur, sans appel Nx. Pour 6 fichiers dans 3 libs :
        // 3 projets au lieu de 216.
        //
        // LIMITE ASSUMÉE : des régressions dans les consommateurs ne seront pas
        // détectées. Acceptable parce que `verify-build` valide la compatibilité
        // des interfaces via les apps hôtes, les consommateurs ont leurs propres
        // tests en CI, et un oracle bloqué en permanence ne détecte rien du tout.
        //
        // Un oracle sans `filesArg` ni `buildHostArg` (comme `build` Gradle) reçoit
        // sa commande telle quelle — périmètre fixe, indépendant du diff.
        name: 'tests',
        command: process.env.FACTORY_COMMAND_FRONT
          ?? `pnpm nx affected -t ${process.env.FACTORY_FRONT_TEST_TARGET ?? 'frontend-test'}`,
        cwd: process.env.FACTORY_CWD_FRONT
          ?? REPO_ROOT,
        filesArg: true,
      },
    ],
    // lock: null  — placeholder pour le verrou lecteurs/écrivain à venir
  },
}
